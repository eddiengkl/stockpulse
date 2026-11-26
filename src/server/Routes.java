package server;

import java.util.Locale;

import java.time.LocalDate;

import data.Snapshot;
import exception.DataFileException;
import exception.InvalidInputException;
import exception.ItemNotFoundException;
import exception.RoleNotPermittedException;
import exception.StorageFullException;
import model.Batch;
import model.LongLifeItem;
import model.PerishableItem;
import model.Season;
import model.SaleRecord;
import model.Settings;
import model.StockItem;
import model.WasteRecord;
import service.InventoryService;
import service.ItemAnalysis;
import service.ReadService;
import store.SeasonStore;

/**
 * The agreed route table.
 *
 * <p>The contract is fixed: {@code GET} to read, {@code POST} for a stock-changing
 * action, {@code PUT} for settings and master data. No verbs hidden in query strings and
 * no authentication headers - the demo role travels in {@code X-Demo-Role}.</p>
 *
 * <p>Every route that changes something follows the same order: take a snapshot, run the
 * business rule, save, then answer. If the save fails the snapshot is restored so memory
 * matches the files again, the write guard blocks any further change, and the caller is
 * told exactly that in a 503. A change is never reported as saved when it is not on
 * disk.</p>
 */
public final class Routes {

    private final Route[] routes;
    private final ReadService read;
    private final InventoryService inventory;
    private final SeasonStore seasons;
    private final Settings settings;
    private final WriteGuard guard;
    private final SnapshotFactory snapshots;
    private final ai.AdvisorService advisor;

    /** Takes a snapshot of everything a change can touch. */
    @FunctionalInterface
    public interface SnapshotFactory {
        Snapshot take();
    }

    /**
     * @param guard     refuses writes once a save has failed
     * @param snapshots so a failed save can be rolled back to the last saved state
     * @param advisor   the AI briefing; read-only, and it decides no business rule
     */
    public Routes(ReadService read, InventoryService inventory, SeasonStore seasons,
                  Settings settings, WriteGuard guard, SnapshotFactory snapshots,
                  ai.AdvisorService advisor) {
        this.read = read;
        this.inventory = inventory;
        this.seasons = seasons;
        this.settings = settings;
        this.guard = guard;
        this.snapshots = snapshots;
        this.advisor = advisor;
        this.routes = build();
    }

    /** True when a failed save has stopped the server accepting changes. */
    public WriteGuard guard() {
        return guard;
    }

    // ------------------------------------------------------------- matching

    /** The matched route, or null when no route handles this method and path. */
    public Route.Match match(String method, String path) {
        Route route = routeFor(method, path);
        return (route == null) ? null : route.match(method, path);
    }

    /** The route itself, so the handler can reach its function. */
    public Route routeFor(String method, String path) {
        for (int i = 0; i < routes.length; i++) {
            if (routes[i].match(method, path) != null) {
                return routes[i];
            }
        }
        return null;
    }

    /** The methods that would work on this path, for a 405 message. */
    public String allowedMethods(String path) {
        StringBuilder allowed = new StringBuilder();
        for (int i = 0; i < routes.length; i++) {
            // A path matches when matching against any method succeeds.
            if (routes[i].match("GET", path) != null || routes[i].match("POST", path) != null
                    || routes[i].match("PUT", path) != null) {
                if (allowed.length() > 0) {
                    allowed.append(", ");
                }
                allowed.append(routes[i].method());
            }
        }
        return (allowed.length() == 0) ? null : allowed.toString();
    }

    /** Every route as "GET /api/dashboard", for a self-describing API index. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < routes.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(routes[i].method()).append(' ').append(routes[i].template());
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- table

    private Route[] build() {
        return new Route[]{
                new Route("GET", "/api", request -> index()),
                new Route("GET", "/api/bootstrap", request -> Payloads.bootstrap(read,
                        role(request), guard)),
                new Route("GET", "/api/dashboard", request -> Payloads.dashboard(read)),
                new Route("GET", "/api/items", this::itemsList),
                new Route("GET", "/api/items/{id}", this::itemDetail),
                new Route("GET", "/api/activity", request ->
                        Payloads.activity(read.recentActivity(), role(request))),
                // Read-only, so no role and no snapshot: the advisor never changes data.
                new Route("GET", "/api/advisor/briefing",
                        request -> Payloads.briefing(advisor, read)),

                new Route("POST", "/api/items/{id}/sale", this::recordSale),
                new Route("POST", "/api/items/{id}/undo-sale", this::undoSale),
                new Route("POST", "/api/items/{id}/delivery", this::receiveDelivery),
                new Route("POST", "/api/items/{id}/waste", this::recordWaste),
                new Route("POST", "/api/items", this::createItem),

                new Route("PUT", "/api/items/{id}", this::updateItem),
                new Route("PUT", "/api/settings", this::updateSettings),
                new Route("PUT", "/api/seasons/{name}", this::updateSeason)
        };
    }

    // ----------------------------------------------------------------- reads

    private String index() {
        Json json = new Json();
        json.beginObject();
        json.put("service", "StockPulse");
        json.comma();
        json.put("routes", describe());
        json.comma();
        json.put("roleHeader", ApiRequest.ROLE_HEADER);
        json.comma();
        json.put("roleDesign",
                "The page stores the demo role the user picked and sends it in the "
                        + ApiRequest.ROLE_HEADER + " header on every request. This is a "
                        + "demo device, not authentication: the role is enforced in Java, "
                        + "but anyone could claim any role.");
        json.comma();
        json.put("writesBlocked", guard.isBlocked());
        json.endObject();
        return json.toString();
    }

    /** The item list, optionally filtered by search text, category or status. */
    private String itemsList(ApiRequest request) throws Exception {
        String search = request.query().get("search");
        String category = request.query().get("category");
        String status = request.query().get("status");

        ItemAnalysis[] analyses = read.analyses();
        ItemAnalysis[] filtered = filter(analyses, search, category, status);

        Json json = new Json();
        json.beginObject();
        json.put("count", filtered.length);
        json.comma();
        json.put("totalBeforeFilter", analyses.length);
        json.comma();
        json.name("items").beginArray();
        for (int i = 0; i < filtered.length; i++) {
            json.comma();
            Payloads.putAnalysis(json.beginObject(), filtered[i]).endObject();
        }
        json.endArray();
        return json.endObject().toString();
    }

    /**
     * Applies the list filters. Done in Java so the page shows a filtered list without
     * holding its own copy of the rules; the status test reuses the same risk methods
     * the dashboard uses, so the two screens cannot disagree.
     */
    private ItemAnalysis[] filter(ItemAnalysis[] analyses, String search, String category,
                                  String status) {
        String want = (status == null) ? "" : status.trim().toLowerCase(Locale.ROOT);
        ItemAnalysis[] kept = new ItemAnalysis[analyses.length];
        int n = 0;
        for (int i = 0; i < analyses.length; i++) {
            ItemAnalysis analysis = analyses[i];
            if (search != null && !search.isBlank()
                    && !matchesText(analysis.item, search.trim())) {
                continue;
            }
            if (category != null && !category.isBlank()
                    && !category.trim().equalsIgnoreCase(analysis.item.getCategory())) {
                continue;
            }
            if (!want.isEmpty() && !"all".equals(want) && !hasStatus(analysis, want)) {
                continue;
            }
            kept[n++] = analysis;
        }
        ItemAnalysis[] result = new ItemAnalysis[n];
        for (int i = 0; i < n; i++) {
            result[i] = kept[i];
        }
        return result;
    }

    private static boolean matchesText(StockItem item, String needle) {
        return contains(item.getItemId(), needle) || contains(item.getName(), needle)
                || contains(item.getCategory(), needle)
                || contains(item.getSupplierName(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private boolean hasStatus(ItemAnalysis analysis, String status) {
        return switch (status) {
            case "stockout" -> analysis.stockoutRisk;
            case "expiry" -> hasExpiryRisk(analysis);
            case "expired" -> hasExpiredBatch(analysis);
            case "ok" -> !analysis.stockoutRisk && !hasExpiryRisk(analysis)
                    && !hasExpiredBatch(analysis);
            default -> true;
        };
    }

    private boolean hasExpiryRisk(ItemAnalysis analysis) {
        service.RiskEngine.ExpiryProjection[] projections =
                inventory.risk().projectExpiry(analysis.item.getItemId(),
                        analysis.seasonalVelocity, analysis.today);
        for (int i = 0; i < projections.length; i++) {
            if (inventory.risk().isExpiryRisk(projections[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExpiredBatch(ItemAnalysis analysis) {
        Batch[] batches = read.batchesOf(analysis.item.getItemId());
        for (int i = 0; i < batches.length; i++) {
            if (!batches[i].isDepleted() && batches[i].isExpired(analysis.today)) {
                return true;
            }
        }
        return false;
    }

    private String itemDetail(ApiRequest request) throws Exception {
        StockItem item = read.requireItem(request.param("id"));
        return Payloads.itemDetail(read, item);
    }

    // --------------------------------------------------------------- actions

    private String recordSale(ApiRequest request) throws Exception {
        String itemId = request.param("id");
        double quantity = request.body().requireDouble("qty");
        // A blank price means "use the item's own price", which the service expresses
        // as NaN; sending 0 here would silently record a free sale.
        double unitPrice = request.body().has("unitPrice")
                ? request.body().requireDouble("unitPrice")
                : Double.NaN;
        String role = role(request);

        return change(() -> {
            SaleRecord sale = inventory.recordSale(itemId, quantity, unitPrice, role);
            StockItem item = inventory.requireItem(itemId);
            return Payloads.saleResult(sale, item, read.dashboard().analyse(item));
        });
    }

    private String undoSale(ApiRequest request) throws Exception {
        String itemId = request.param("id");
        String role = role(request);

        return change(() -> {
            boolean undone = inventory.undoLastSale(itemId, role);
            StockItem item = inventory.requireItem(itemId);
            return Payloads.undoResult(undone, item, read.dashboard().analyse(item));
        });
    }

    private String receiveDelivery(ApiRequest request) throws Exception {
        String itemId = request.param("id");
        double quantity = request.body().requireDouble("qty");
        double unitCost = request.body().requireDouble("unitCost");
        LocalDate expiry = request.body().optionalDate("expiryDate");
        String role = role(request);

        return change(() -> {
            Batch batch = inventory.receiveDelivery(itemId, quantity, expiry, unitCost, role);
            StockItem item = inventory.requireItem(itemId);
            return Payloads.deliveryResult(batch, item, read.dashboard().analyse(item));
        });
    }

    private String recordWaste(ApiRequest request) throws Exception {
        String itemId = request.param("id");
        String batchId = request.body().require("batchId");
        double quantity = request.body().requireDouble("qty");
        String reason = request.body().require("reason");
        String role = role(request);

        return change(() -> {
            WasteRecord record = inventory.recordWaste(itemId, batchId, quantity, reason, role);
            StockItem item = inventory.requireItem(itemId);
            return Payloads.wasteResult(record, item, read.dashboard().analyse(item));
        });
    }

    // ------------------------------------------------------------------- items

    /**
     * Adds an item to the catalogue. Manager only.
     *
     * <p>Every field is read and validated before the item is built, and the item is
     * built before anything is added to the store, so a bad field leaves the catalogue
     * untouched rather than half-populated. The duplicate-id check runs in Java here as
     * well as in the loader, because the loader only ever sees the file at startup: two
     * items with the same id saved today would make tomorrow's start-up fail.</p>
     */
    private String createItem(ApiRequest request) throws Exception {
        requireManage(request, "add an item");

        String itemId = request.body().require("itemId");
        if (inventory.items().containsId(itemId)) {
            throw new InvalidInputException("itemId",
                    "There is already an item with the id '" + itemId
                            + "'. Choose a different id, or edit the existing item.");
        }
        if (inventory.items().isFull()) {
            throw new StorageFullException("item", inventory.items().capacity());
        }

        StockItem item = buildItem(request, itemId);
        String role = role(request);
        return change(() -> {
            inventory.items().add(item);
            inventory.activityLog().add(role, "ITEM",
                    "Added item " + item.getItemId() + " - " + item.getName());
            return itemResult(item, "Added " + item.getName() + " (" + item.getItemId() + ").");
        });
    }

    /**
     * Edits an item's fields. Manager only.
     *
     * <p>The changes are applied to a <em>copy</em> of the item first. Only if every
     * field is acceptable is the copy's state written back into the stored item, which
     * means a bad price cannot leave a name already changed. The id itself is the key in
     * the URL and is not editable - changing it would orphan the item's batches, sales
     * and waste records, so the route simply does not offer it.</p>
     *
     * <p>The item's <strong>type</strong> is not editable either. A perishable item and a
     * long-life item are different subclasses, and the update below copies only the fields
     * the two share; the type is what decides which object exists in the store. Switching
     * a batch-holding perishable into a long-life item would leave its batches with expiry
     * dates that the new object would stop reporting on, and the stock figures would
     * quietly disagree with the data. Rather than half-convert an item and its history,
     * the route refuses the change and says what to do instead.</p>
     */
    private String updateItem(ApiRequest request) throws Exception {
        requireManage(request, "edit an item");

        StockItem stored = inventory.requireItem(request.param("id"));
        requireSameType(stored, request.body());
        // Built and validated before the snapshot is taken, so a bad field costs
        // nothing and the reported 400 still names the field.
        StockItem candidate = buildItem(request, stored.getItemId());
        String role = role(request);

        return change(() -> {
            // The field rules live in the model and have already run against the
            // candidate, so copying across cannot fail at this point.
            stored.setName(candidate.getName());
            stored.setCategory(candidate.getCategory());
            stored.setUnit(candidate.getUnit());
            stored.setSellingPrice(candidate.getSellingPrice());
            stored.setUnitCost(candidate.getUnitCost());
            stored.setShelfLifeDays(candidate.getShelfLifeDays());
            stored.setPackSize(candidate.getPackSize());
            stored.setSupplierName(candidate.getSupplierName());
            stored.setLeadTimeDays(candidate.getLeadTimeDays());
            stored.setSeason(candidate.getSeason());
            stored.setSeasonalMultiplier(candidate.getSeasonalMultiplier());

            inventory.activityLog().add(role, "ITEM",
                    "Edited item " + stored.getItemId() + " - " + stored.getName());
            return itemResult(stored, "Saved changes to " + stored.getName() + ".");
        });
    }

    /**
     * Refuses an edit that would change the item's type.
     *
     * <p>Checked before the candidate is built, so the refusal happens as early as the
     * URL and body allow and nothing is constructed that could be mistaken for an update.
     * The message names both types and the practical alternative, because "not allowed"
     * on its own leaves the manager wondering what to do.</p>
     *
     * @throws InvalidInputException when the body asks for a different subclass
     */
    private static void requireSameType(StockItem stored, RequestMap body)
            throws InvalidInputException {
        if (!body.has("itemType")) {
            // An edit that does not mention the type cannot be changing it.
            return;
        }
        String requested = body.require("itemType");
        // readItemType validates the word and answers "is this perishable".
        boolean requestedPerishable = readItemType(requested);
        if (requestedPerishable == stored.canExpire()) {
            return;
        }
        throw new InvalidInputException("itemType",
                "Item " + stored.getItemId() + " is a "
                        + stored.itemType() + " item, so it cannot be changed into a "
                        + (requestedPerishable ? StockItem.TYPE_PERISHABLE
                                : StockItem.TYPE_LONGLIFE)
                        + " item. An item's type is fixed once it has been added, because"
                        + " its batches, sales and waste records were recorded against that"
                        + " type. Add a new item instead, and keep this one for its"
                        + " existing history.");
    }

    /**
     * Builds an item from the request body without touching any store.
     *
     * <p>Shared by both routes so that adding and editing accept exactly the same fields
     * and enforce exactly the same rules. Every model
     * {@link IllegalArgumentException} is translated into the
     * {@link InvalidInputException} the API answers 400 with, and the offending field
     * name is supplied here because the model only knows the human-readable label.</p>
     *
     * @param itemId the id to build with; from the body on create, from the URL on edit
     * @throws InvalidInputException when any field is missing or not acceptable
     */
    private StockItem buildItem(ApiRequest request, String itemId) throws InvalidInputException {
        RequestMap body = request.body();
        String type = body.require("itemType");
        boolean perishable = readItemType(type);

        String name = body.require("name");
        String category = body.require("category");
        String unit = body.require("unit");
        double sellingPrice = body.requireDouble("sellingPrice");
        double unitCost = body.requireDouble("unitCost");
        int shelfLifeDays = body.requireInt("shelfLifeDays");
        int packSize = body.requireInt("packSize");
        String supplierName = body.require("supplierName");
        int leadTimeDays = body.requireInt("leadTimeDays");

        StockItem item;
        try {
            item = perishable
                    ? new PerishableItem(itemId, name, category, unit, sellingPrice,
                            unitCost, shelfLifeDays, packSize, supplierName, leadTimeDays)
                    : new LongLifeItem(itemId, name, category, unit, sellingPrice,
                            unitCost, shelfLifeDays, packSize, supplierName, leadTimeDays);
        } catch (IllegalArgumentException e) {
            throw fieldError(e);
        }

        applySeason(request, item);
        return item;
    }

    /**
     * Decides which subclass to build from the type tag in the request.
     *
     * <p>Accepts the two tags the data file writes plus the plain words a form would
     * use, so the page does not have to know the file format.</p>
     */
    private static boolean readItemType(String type) throws InvalidInputException {
        String text = type.trim();
        if (text.equalsIgnoreCase(StockItem.TYPE_PERISHABLE) || text.equalsIgnoreCase("perishable")) {
            return true;
        }
        if (text.equalsIgnoreCase(StockItem.TYPE_LONGLIFE)
                || text.equalsIgnoreCase("long_life")
                || text.equalsIgnoreCase("long life")
                || text.equalsIgnoreCase("longlife")) {
            return false;
        }
        throw new InvalidInputException("itemType", "The field 'itemType' should be '"
                + StockItem.TYPE_PERISHABLE + "' or " + StockItem.TYPE_LONGLIFE
                + " but was '" + text + "'.");
    }

    /**
     * Works out which field the model objected to, so the 400 names it.
     *
     * <p>The model's message already contains the field label in words ("Selling price
     * must be a normal non-negative number..."), so the field name is recovered by
     * matching that label rather than by re-validating every field here. Keeping the
     * rules in one place - the model - is worth the small amount of string matching.</p>
     *
     * <p>The order matters: "unit cost" has to be tested before "unit", and "shelf life"
     * before "item name" would ever be reached by a subclass of the same words. Each test
     * uses the longest distinctive label the model can produce, longest first.</p>
     */
    private static InvalidInputException fieldError(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "The item is not valid." : e.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        String field;
        if (lower.contains("item id")) {
            field = "itemId";
        } else if (lower.contains("item name")) {
            field = "name";
        } else if (lower.contains("selling price")) {
            field = "sellingPrice";
        } else if (lower.contains("unit cost")) {
            field = "unitCost";
        } else if (lower.contains("shelf life")) {
            field = "shelfLifeDays";
        } else if (lower.contains("pack size")) {
            field = "packSize";
        } else if (lower.contains("supplier")) {
            field = "supplierName";
        } else if (lower.contains("lead time")) {
            field = "leadTimeDays";
        } else if (lower.contains("category")) {
            field = "category";
        } else if (lower.contains("unit")) {
            field = "unit";
        } else {
            field = "item";
        }
        return new InvalidInputException(field, message, e);
    }

    /**
     * Applies the optional season and multiplier.
     *
     * <p>A season that is not configured is refused rather than silently dropped: an item
     * pointing at a season that does not exist would look seasonal in the form and behave
     * unseasonally in the figures, with nothing to show why. A multiplier of zero or less
     * is refused for the same reason.</p>
     */
    private void applySeason(ApiRequest request, StockItem item) throws InvalidInputException {
        RequestMap body = request.body();
        String seasonName = body.get("season");
        if (seasonName == null || seasonName.isBlank() || seasonName.trim().equals("-")) {
            item.setSeason(null);
            item.setSeasonalMultiplier(1.00);
            return;
        }
        Season season = seasons.findByName(seasonName.trim());
        if (season == null) {
            throw new InvalidInputException("season",
                    "There is no season called '" + seasonName.trim() + "'.");
        }
        double multiplier = body.has("seasonalMultiplier")
                ? body.requireDouble("seasonalMultiplier")
                : 1.00;
        if (!(multiplier > 0)) {
            throw new InvalidInputException("seasonalMultiplier",
                    "The seasonal multiplier must be more than 0.");
        }
        item.setSeason(season);
        item.setSeasonalMultiplier(multiplier);
    }

    /** The saved item as the API reports it after an add or an edit. */
    private String itemResult(StockItem item, String message) {
        ItemAnalysis analysis = read.dashboard().analyse(item);
        Json json = new Json();
        json.beginObject();
        json.put("message", message);
        json.comma();
        json.name("item").beginObject();
        Payloads.putAnalysis(json, analysis).endObject();
        return json.endObject().toString();
    }

    // -------------------------------------------------------------- settings

    /**
     * Updates the thresholds, the active season and the demo date.
     *
     * <p>The demo date is applied to memory only. It is deliberately not written to
     * settings.txt: a forgotten demo date would otherwise make tomorrow's real session
     * report figures for yesterday's invented one.</p>
     *
     * <p>Only the fields present in the request are changed, so the page can send one
     * changed number without repeating the rest.</p>
     */
    private String updateSettings(ApiRequest request) throws Exception {
        requireManage(request, "change settings");

        int window = settings.getSalesWindowDays();
        int margin = settings.getSafetyMarginDays();
        int horizon = settings.getReorderHorizonDays();
        String season = settings.getActiveSeasonName();
        boolean fromDate = settings.isSeasonFromDate();
        LocalDate demoDate = settings.getDemoDate();
        boolean demoChanged = false;

        RequestMap body = request.body();
        if (body.has("salesWindowDays")) {
            window = body.requireInt("salesWindowDays");
        }
        if (body.has("safetyMarginDays")) {
            margin = body.requireInt("safetyMarginDays");
        }
        if (body.has("reorderHorizonDays")) {
            horizon = body.requireInt("reorderHorizonDays");
        }
        if (body.has("activeSeasonName")) {
            season = body.require("activeSeasonName");
            if (seasons.findByName(season) == null) {
                throw new InvalidInputException("activeSeasonName",
                        "There is no season called '" + season + "'.");
            }
        }
        if (body.has("seasonFromDate")) {
            fromDate = readFlag(body.require("seasonFromDate"), "seasonFromDate");
        }
        if (body.has("demoDate")) {
            String raw = body.get("demoDate").trim();
            demoDate = raw.isEmpty() || raw.equals("0") || raw.equals("-") ? null
                    : body.requireDate("demoDate");
            demoChanged = true;
        }

        // Validate before assigning: the setters check the ranges, and a bad value must
        // leave every previous setting untouched rather than applying half the request.
        Settings candidate = new Settings();
        candidate.copySavedValuesFrom(settings);
        try {
            candidate.setSalesWindowDays(window);
            candidate.setSafetyMarginDays(margin);
            candidate.setReorderHorizonDays(horizon);
            candidate.setActiveSeasonName(season);
            candidate.setSeasonFromDate(fromDate);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("settings", e.getMessage(), e);
        }

        // The demo date is session-only, so it is applied outside the rollback: undoing a
        // failed save must put the saved thresholds back, not silently change which date
        // the figures are being shown for.
        final boolean applyDemo = demoChanged;
        final LocalDate chosenDate = demoDate;
        return change(() -> {
            settings.copySavedValuesFrom(candidate);
            if (applyDemo) {
                settings.setDemoDate(chosenDate);
            }

            Json json = new Json();
            json.beginObject();
            json.put("message", applyDemo && chosenDate != null
                    ? "Settings saved. Figures now use the demo date "
                            + model.DateText.format(chosenDate) + "."
                    : "Settings saved.");
            json.comma();
            json.name("settings").beginObject();
            json.put("salesWindowDays", settings.getSalesWindowDays());
            json.comma();
            json.put("safetyMarginDays", settings.getSafetyMarginDays());
            json.comma();
            json.put("reorderHorizonDays", settings.getReorderHorizonDays());
            json.comma();
            json.put("activeSeasonName", settings.getActiveSeasonName());
            json.comma();
            json.put("seasonFromDate", settings.isSeasonFromDate());
            json.comma();
            json.put("demoDate", settings.getDemoDate() == null
                    ? null : model.DateText.format(settings.getDemoDate()));
            json.endObject();
            return json.endObject().toString();
        });
    }

    /** Changes a season's months. The name is the key and is not itself editable. */
    private String updateSeason(ApiRequest request) throws Exception {
        requireManage(request, "change a season");

        String name = request.param("name");
        Season existing = seasons.findByName(name);
        if (existing == null) {
            throw new ItemNotFoundException(name, "Season");
        }
        int start = request.body().requireInt("startMonth");
        int end = request.body().requireInt("endMonth");

        return change(() -> {
            // updateMonths validates the range and throws before changing anything.
            seasons.updateMonths(existing.getName(), start, end);

            Json json = new Json();
            json.beginObject();
            json.put("message", "Season " + existing.getName() + " now covers "
                    + existing.monthRangeText() + ".");
            json.comma();
            json.name("season").beginObject();
            Payloads.putSeason(json, existing).endObject();
            return json.endObject().toString();
        });
    }

    private static boolean readFlag(String raw, String field) throws InvalidInputException {
        String text = raw.trim();
        if (text.equals("1") || text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equals("0") || text.equalsIgnoreCase("false")) {
            return false;
        }
        throw new InvalidInputException(field, "The field '" + field
                + "' should be 0 or 1 but was '" + text + "'.");
    }

    // --------------------------------------------------------------- helpers

    /** The demo role on this request, trimmed, never null. */
    private static String role(ApiRequest request) {
        return request.role() == null ? "" : request.role();
    }

    /**
     * Manager-only routes are refused here <em>and</em> again inside the services that
     * need it. The duplicate is deliberate: this one produces a good message naming the
     * action, and the service one means a future route cannot forget the check.
     */
    private static void requireManage(ApiRequest request, String action)
            throws RoleNotPermittedException {
        String role = role(request);
        if (role.equalsIgnoreCase(InventoryService.ROLE_MANAGER)) {
            return;
        }
        throw new RoleNotPermittedException(role.isBlank() ? "not set" : role, action);
    }

    /**
     * Runs one change: snapshot, mutate, save, and roll back only what the disk rolled
     * back.
     *
     * <p>This is the only way a route is allowed to change anything, because getting the
     * order wrong here is exactly the bug this class exists to prevent. The sequence is:</p>
     * <ol>
     *   <li>{@link WriteGuard#requireWritable()} refuses <em>before</em> the business rule
     *       runs, so a server that has already lost a save cannot stack another change on
     *       top of it - and cannot have half-applied one in memory either.</li>
     *   <li>A {@link Snapshot} is taken <em>before</em> the mutation, so it holds the state
     *       the files last agreed on.</li>
     *   <li>The mutation runs. If it throws, nothing has been saved and nothing needs
     *       undoing; the exception carries on to the caller.</li>
     *   <li>The save runs. If it throws, no data file holds the new records, so the
     *       snapshot is restored and memory goes back to what the files hold.</li>
     * </ol>
     *
     * <p><strong>The snapshot is restored only when the disk was restored too.</strong>
     * That condition is the whole point of this method, and it used to be missing: a save
     * that replaced all seven files and then failed while tidying up threw, this code put
     * the old figures back, and memory then disagreed with files that held the new records.
     * {@link WriteGuard#saveOrBlock()} now returns instead of throwing for exactly that
     * case - the data committed - so memory keeps the change and only the guard is blocked.
     * Restoring in that situation would have replaced the right figures with the wrong
     * ones.</p>
     *
     * <p>Taking the snapshot before rather than after the mutation is the other half: a
     * snapshot taken afterwards would restore the very change that failed to save.</p>
     *
     * @param change the business rule to run
     * @return whatever the change produced, for the response payload
     * @throws DataFileException when no data file holds the new records; memory has been
     *                           rolled back and the guard is blocked by the time this
     *                           returns
     */
    private <T> T change(Change<T> change) throws Exception {
        guard.requireWritable();
        Snapshot before = snapshots.take();
        T result = change.run();
        try {
            guard.saveOrBlock();
        } catch (DataFileException e) {
            try {
                before.restore();
            } catch (RuntimeException restoreFailure) {
                // The disk facts stay exactly as the save reported them: this branch knows
                // nothing about the folder and must not overwrite what does. Only the
                // memory side is added, and briefly - the message already says the restore
                // failed, so this names the underlying reason rather than repeating the
                // sentence. The save's own recovery hint is kept too, because it describes
                // leftover files that a failed restore says nothing about.
                guard.blockWithMemoryStillChanged(e,
                        "the figures in memory could not be put back ("
                                + restoreFailure.getMessage() + ")");
                throw e;
            }
            throw e;
        }
        // The data committed, so memory keeps the change - but the save left recovery files
        // behind and the guard is blocked. Returning the payload here would send a 200 for
        // a folder a restart will refuse, so the blocked state is turned into the failure
        // it is. The exception the guard recorded is the one built from the cleanup report,
        // so its message and facts are already the truthful ones.
        if (guard.isBlocked()) {
            guard.requireWritable();
        }
        return result;
    }

    /** A business rule that changes something and returns what the response needs. */
    @FunctionalInterface
    private interface Change<T> {
        T run() throws Exception;
    }
}
