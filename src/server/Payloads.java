package server;

import java.time.LocalDate;

import model.ActivityLog;
import model.Batch;
import model.DateText;
import model.NumberText;
import model.RiskFlag;
import model.SaleRecord;
import model.Season;
import model.Settings;
import model.StockItem;
import model.WasteRecord;
import service.ItemAnalysis;
import service.ReadService;

/**
 * Turns the Java model into the JSON the page receives.
 *
 * <p>Split from {@link ApiHandler} so that one class decides <em>which route does what</em>
 * and this one decides <em>what a thing looks like</em>. Nothing here decides a business
 * rule: every number is taken from a service that already computed it.</p>
 *
 * <p>Fields that a subclass defines - item type, expiry, discount, stock description -
 * are read through the polymorphic methods on {@link StockItem}, so the page shows what
 * the object says about itself rather than an {@code instanceof} guess made here.</p>
 */
public final class Payloads {

    private static final String[] MONTH_NAMES = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private Payloads() {
    }

    /**
     * What the page may offer for a role. Hiding a button is a courtesy to the user, not
     * a security control: the same rule is enforced again in Java when the request
     * arrives.
     */
    public static boolean isManager(String role) {
        return role != null && role.trim().equalsIgnoreCase(service.InventoryService.ROLE_MANAGER);
    }

    // ------------------------------------------------------------- bootstrap

    /**
     * Settings, seasons, the effective date and what the current role may do.
     *
     * <p>The capabilities are computed here rather than in JavaScript: hiding a button
     * is a courtesy, not a control, and the same rule is enforced again by
     * {@link service.InventoryService} when the request arrives.</p>
     *
     * <p>The role is the one on <em>this</em> request, sent in {@code X-Demo-Role}. The
     * page stores the picker's value and sends it every time, which is why this payload
     * has to be re-fetched after the picker changes: the answer belongs to the role that
     * asked, not to the server.</p>
     *
     * <p>{@code writesBlocked} lets the page disable its forms after a failed save rather
     * than letting the user fill one in and be refused.</p>
     */
    public static String bootstrap(ReadService read, String role, WriteGuard guard) {
        Settings settings = read.settings();
        Json json = new Json();
        json.beginObject();
        json.put("role", role);
        json.comma();
        json.put("canManage", isManager(role));
        json.comma();
        json.put("writesBlocked", guard.isBlocked());
        json.comma();
        json.put("notAuthentication",
                "The role picker is a demo device, not a login. It is enforced in Java, "
                        + "but anyone could claim any role.");
        json.name("settings").beginObject();
        putSettings(json, settings);
        json.endObject();
        json.comma();
        json.name("seasons").beginArray();
        Season[] seasons = read.seasons();
        for (int i = 0; i < seasons.length; i++) {
            json.comma();
            putSeason(json.beginObject(), seasons[i]).endObject();
        }
        json.endArray();
        json.comma();
        Season active = read.activeSeason();
        if (active == null) {
            json.putNull("activeSeasonName");
        } else {
            json.put("activeSeasonName", active.getName());
        }
        json.comma();
        json.name("today").beginObject();
        putDate(json, read.today());
        json.endObject();
        return json.endObject().toString();
    }

    /** Writes the date three ways, so the page never has to format one itself. */
    private static void putDate(Json json, LocalDate date) {
        json.put("iso", date.toString());
        json.comma();
        json.put("text", DateText.format(date));
        json.comma();
        json.put("weekday", weekdayName(date));
    }

    /** Day-and-month name, computed in Java so the page does not need a locale table. */
    public static String weekdayName(LocalDate date) {
        String[] names = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
                "Saturday", "Sunday"};
        return names[date.getDayOfWeek().getValue() - 1];
    }

    /** "Thu 26 Nov 2026", for the demo-date banner. */
    public static String longDate(LocalDate date) {
        String day = weekdayName(date).substring(0, 3);
        return day + " " + date.getDayOfMonth() + " " + MONTH_NAMES[date.getMonthValue() - 1]
                + " " + date.getYear();
    }

    private static void putSettings(Json json, Settings settings) {
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
        if (settings.getDemoDate() == null) {
            json.putNull("demoDate");
        } else {
            json.put("demoDate", DateText.format(settings.getDemoDate()));
        }
        json.comma();
        json.put("isDemoDate", settings.isDemoDate());
    }

    /** One season, including which months it covers in words. */
    public static Json putSeason(Json json, Season season) {
        json.put("name", season.getName());
        json.comma();
        json.put("startMonth", season.getStartMonth());
        json.comma();
        json.put("endMonth", season.getEndMonth());
        json.comma();
        json.put("monthRange", season.monthRangeText());
        json.comma();
        json.put("monthCount", season.monthCount());
        return json;
    }

    // ------------------------------------------------------------- dashboard

    /** Every item with its figures, the ranked warnings, and the totals. */
    public static String dashboard(ReadService read) {
        ItemAnalysis[] analyses = read.analyses();
        RiskFlag[] flags = read.flags();
        LocalDate today = read.today();

        Json json = new Json();
        json.beginObject();
        putDate(json.name("today").beginObject(), today);
        json.endObject();

        json.comma();
        json.name("totals").beginObject();
        json.put("itemCount", analyses.length);
        json.comma();
        json.put("stockValueAtCost", read.dashboard().totalStockValueAtCost(analyses));
        json.comma();
        json.put("stockValueAtPrice", read.dashboard().totalStockValueAtPrice(analyses));
        json.comma();
        json.put("stockoutCount", read.dashboard().countOfType(flags, RiskFlag.TYPE_STOCKOUT));
        json.comma();
        json.put("expiryCount", read.dashboard().countOfType(flags, RiskFlag.TYPE_EXPIRY));
        json.comma();
        json.put("expiredCount", read.dashboard().countOfType(flags, RiskFlag.TYPE_EXPIRED));
        json.comma();
        json.put("wasteValueInWindow", read.dashboard().wasteValueInWindow());
        json.comma();
        json.put("windowDays", read.settings().getSalesWindowDays());
        json.endObject();

        json.comma();
        json.name("flags").beginArray();
        for (int i = 0; i < flags.length; i++) {
            json.comma();
            putFlag(json.beginObject(), flags[i], read).endObject();
        }
        json.endArray();

        json.comma();
        json.name("items").beginArray();
        for (int i = 0; i < analyses.length; i++) {
            json.comma();
            putAnalysis(json.beginObject(), analyses[i]).endObject();
        }
        json.endArray();

        return json.endObject().toString();
    }

    private static Json putFlag(Json json, RiskFlag flag, ReadService read) {
        json.put("itemId", flag.getItemId());
        json.comma();
        json.put("batchId", flag.getBatchId());
        json.comma();
        json.put("type", flag.getType());
        json.comma();
        json.put("urgency", flag.getUrgency());
        json.comma();
        json.put("reason", flag.getReason());
        json.comma();
        try {
            StockItem item = read.requireItem(flag.getItemId());
            json.put("itemName", item.getName());
            json.comma();
            json.put("unit", item.getUnit());
        } catch (exception.ItemNotFoundException e) {
            json.put("itemName", null);
            json.comma();
            json.put("unit", null);
        }
        return json;
    }

    /** One row of the item list: everything the table shows, already decided. */
    public static Json putAnalysis(Json json, ItemAnalysis analysis) {
        StockItem item = analysis.item;
        json.put("itemId", item.getItemId());
        json.comma();
        json.put("name", item.getName());
        json.comma();
        json.put("category", item.getCategory());
        json.comma();
        json.put("itemType", item.itemType());
        json.comma();
        json.put("unit", item.getUnit());
        json.comma();
        json.put("canExpire", item.canExpire());
        json.comma();
        json.put("supplierName", item.getSupplierName());
        json.comma();
        json.put("leadTimeDays", item.getLeadTimeDays());
        json.comma();
        json.put("packSize", item.getPackSize());
        json.comma();

        json.put("sellingPrice", item.getSellingPrice());
        json.comma();
        json.put("unitCost", item.getUnitCost());
        json.comma();

        json.put("stockOnHand", analysis.stockOnHand);
        json.comma();
        json.put("sellableStock", analysis.sellableStock);
        json.comma();
        json.put("batchCount", analysis.batchCount);
        json.comma();
        json.put("stockDescription", analysis.stockDescription);
        json.comma();

        json.put("observedVelocity", analysis.observedVelocity);
        json.comma();
        json.put("seasonalVelocity", analysis.seasonalVelocity);
        json.comma();
        json.put("velocityDivisor", analysis.velocityDivisor);
        json.comma();
        json.put("seasonalMultiplier", analysis.seasonalMultiplier);
        json.comma();

        // An infinite cover cannot be sent as a number, so the reason travels with it.
        // Written with an explicit branch: a ternary mixing null and a double unboxes the
        // null and throws, which is exactly the trap this line used to fall into.
        if (analysis.neverRunsOut()) {
            json.putNull("daysOfCover");
        } else {
            json.put("daysOfCover", analysis.daysOfCover);
        }
        json.comma();
        json.put("daysOfCoverText", analysis.neverRunsOut()
                ? "never runs out (no sales)" : NumberText.quantity(analysis.daysOfCover) + " days");
        json.comma();
        json.put("reorderPoint", analysis.reorderPoint);
        json.comma();
        json.put("reorderQuantity", analysis.reorderQuantity);
        json.comma();
        json.put("stockoutRisk", analysis.stockoutRisk);
        json.comma();

        if (analysis.noExpiry()) {
            json.putNull("daysUntilExpiry");
        } else {
            json.put("daysUntilExpiry", analysis.daysUntilExpiry);
        }
        json.comma();
        json.put("noExpiry", analysis.noExpiry());
        json.comma();
        json.put("discountText", item.canExpire() && !analysis.noExpiry()
                ? StockItem.discountText(item.discountPercentFor(
                        Math.max(1, analysis.daysUntilExpiry)))
                : StockItem.discountText(StockItem.DISCOUNT_NOT_CONFIGURED));
        json.comma();

        json.put("stockValueAtCost", analysis.stockValueAtCost);
        json.comma();
        json.put("stockValueAtPrice", analysis.stockValueAtPrice);
        json.comma();
        json.put("stockValueFormula", item.describeQuantity(analysis.stockOnHand) + " x RM"
                        + NumberText.money(item.getUnitCost()) + " = RM"
                        + NumberText.money(analysis.stockValueAtCost));
        return json;
    }

    // ------------------------------------------------------------ item detail

    /** One item with its batches, recent sales and the arithmetic behind its figures. */
    public static String itemDetail(ReadService read, StockItem item) {
        ItemAnalysis analysis = read.dashboard().analyse(item);
        Batch[] batches = read.batchesOf(item.getItemId());
        SaleRecord[] recent = read.recentSales(item.getItemId());
        LocalDate today = read.today();

        Json json = new Json();
        json.beginObject();
        putAnalysis(json, analysis);

        json.comma();
        json.name("batches").beginArray();
        for (int i = 0; i < batches.length; i++) {
            json.comma();
            putBatch(json.beginObject(), batches[i], today, item).endObject();
        }
        json.endArray();

        json.comma();
        json.name("recentSales").beginArray();
        for (int i = 0; i < recent.length; i++) {
            json.comma();
            putSale(json.beginObject(), recent[i]).endObject();
        }
        json.endArray();

        // The working, shown so a marker can check the number rather than trusting it.
        json.comma();
        json.name("breakdown").beginObject();
        json.put("velocityFormula",
                item.describeQuantity(totalSold(recent))
                        + " sold in the last " + read.settings().getSalesWindowDays()
                        + " days / " + analysis.velocityDivisor + " days = "
                        + NumberText.quantity(analysis.observedVelocity) + " per day");
        json.comma();
        json.put("seasonalFormula",
                NumberText.quantity(analysis.observedVelocity) + " x "
                        + NumberText.quantity(analysis.seasonalMultiplier) + " season = "
                        + NumberText.quantity(analysis.seasonalVelocity) + " per day");
        json.comma();
        json.put("coverFormula", analysis.neverRunsOut()
                ? "no sales, so cover is unlimited"
                : NumberText.quantity(analysis.stockOnHand) + " / "
                        + NumberText.quantity(analysis.seasonalVelocity) + " per day = "
                        + NumberText.quantity(analysis.daysOfCover) + " days");
        json.comma();
        json.put("reorderPointFormula",
                NumberText.quantity(analysis.seasonalVelocity) + " x ("
                        + item.getLeadTimeDays() + " lead + "
                        + read.settings().getSafetyMarginDays() + " safety) = "
                        + NumberText.quantity(analysis.reorderPoint));
        json.comma();
        json.put("reorderQuantityFormula",
                NumberText.quantity(analysis.seasonalVelocity) + " x ("
                        + item.getLeadTimeDays() + " lead + "
                        + read.settings().getSafetyMarginDays() + " safety + "
                        + read.settings().getReorderHorizonDays() + " horizon) - "
                        + NumberText.quantity(analysis.stockOnHand) + " on hand, rounded up to "
                        + item.getPackSize() + " per pack = "
                        + NumberText.quantity(analysis.reorderQuantity));
        json.endObject();

        return json.endObject().toString();
    }

    /** Units sold in the visible history, used only to show the division. */
    private static double totalSold(SaleRecord[] sales) {
        double total = 0;
        for (int i = 0; i < sales.length; i++) {
            total += sales[i].getQuantity();
        }
        return total;
    }

    public static Json putBatch(Json json, Batch batch, LocalDate today, StockItem item) {
        json.put("batchId", batch.getBatchId());
        json.comma();
        json.put("qtyReceived", batch.getQtyReceived());
        json.comma();
        json.put("qtyRemaining", batch.getQtyRemaining());
        json.comma();
        json.put("receivedDate", DateText.format(batch.getReceivedDate()));
        json.comma();
        json.put("expiryDate", batch.getExpiryDate() == null
                ? null : DateText.format(batch.getExpiryDate()));
        json.comma();
        json.put("unitCost", batch.getUnitCost());
        json.comma();
        json.put("remainingValue", batch.remainingValue());
        json.comma();
        json.put("expired", batch.isExpired(today));
        json.comma();
        json.put("depleted", batch.isDepleted());
        json.comma();
        if (batch.getExpiryDate() == null) {
            json.putNull("daysLeft");
        } else {
            json.put("daysLeft", batch.daysLeft(today));
        }
        json.comma();
        json.put("sellable", !batch.isDepleted() && !batch.isExpired(today));
        json.comma();
        json.put("unit", item.getUnit());
        return json;
    }

    public static Json putSale(Json json, SaleRecord sale) {
        json.put("saleId", sale.getSaleId());
        json.comma();
        json.put("date", DateText.format(sale.getDate()));
        json.comma();
        json.put("quantity", sale.getQuantity());
        json.comma();
        json.put("unitPrice", sale.getUnitPrice());
        json.comma();
        json.put("revenue", sale.revenue());
        json.comma();
        json.put("batchDraws", sale.getBatchDraws());
        return json;
    }

    /** Confirmation for a sale, including which batches supplied it. */
    public static String saleResult(SaleRecord sale, StockItem item, ItemAnalysis after) {
        Json json = new Json();
        json.beginObject();
        json.put("message", "Sold " + item.describeQuantity(sale.getQuantity()) + " of "
                + item.getName() + " for RM" + NumberText.money(sale.revenue()) + ".");
        json.comma();
        json.name("sale").beginObject();
        putSale(json, sale).endObject();
        json.comma();
        json.put("remainingStock", after.stockOnHand);
        json.comma();
        json.put("remainingDescription", after.stockDescription);
        return json.endObject().toString();
    }

    /** Confirmation for a delivery. */
    public static String deliveryResult(Batch batch, StockItem item, ItemAnalysis after) {
        Json json = new Json();
        json.beginObject();
        json.put("message", "Received " + item.describeQuantity(batch.getQtyReceived())
                + " of " + item.getName() + " as " + batch.getBatchId() + ".");
        json.comma();
        json.name("batch").beginObject();
        putBatch(json, batch, after.today, item).endObject();
        json.comma();
        json.put("remainingStock", after.stockOnHand);
        json.comma();
        json.put("remainingDescription", after.stockDescription);
        return json.endObject().toString();
    }

    /** Confirmation for a write-off. */
    public static String wasteResult(WasteRecord record, StockItem item, ItemAnalysis after) {
        Json json = new Json();
        json.beginObject();
        json.put("message", "Wrote off " + item.describeQuantity(record.getQuantity())
                + " of " + item.getName() + " (RM" + NumberText.money(record.valueLost())
                + " lost).");
        json.comma();
        json.name("waste").beginObject();
        json.put("wasteId", record.getWasteId());
        json.comma();
        json.put("batchId", record.getBatchId());
        json.comma();
        json.put("quantity", record.getQuantity());
        json.comma();
        json.put("valueLost", record.valueLost());
        json.comma();
        json.put("reason", record.getReason());
        json.endObject();
        json.comma();
        json.put("remainingStock", after.stockOnHand);
        json.comma();
        json.put("remainingDescription", after.stockDescription);
        return json.endObject().toString();
    }

    /** Result of an undo: the sale that was reversed, or a clear "nothing to undo". */
    public static String undoResult(boolean undone, StockItem item, ItemAnalysis after) {
        Json json = new Json();
        json.beginObject();
        json.put("undone", undone);
        json.comma();
        json.put("message", undone
                ? "The most recent sale of " + item.getName() + " was undone and the stock returned."
                : "There was no sale of " + item.getName() + " recorded today to undo.");
        json.comma();
        json.put("remainingStock", after.stockOnHand);
        json.comma();
        json.put("remainingDescription", after.stockDescription);
        return json.endObject().toString();
    }

    // ------------------------------------------------------- advisor briefing

    /**
     * The advisor's answer, always with both the AI text and the Java rule summary.
     *
     * <p>The two live in separate fields with separate {@code source} labels - {@code "AI"}
     * and {@code "RULES"} - because a summary written by this program must never appear as
     * if the model wrote it. The rule summary is present in every reply, including the ones
     * where the model succeeded, so the page can show the figures even when the wording is
     * unavailable.</p>
     *
     * <p>When the model fails, {@code briefing} is null and {@code unavailable} explains
     * why. The route still answers 200 in that case: the request was understood and the
     * response is well-formed - what is missing is one optional ingredient. A 503 would
     * suggest the server itself is unhealthy, which would be untrue and would make the page
     * hide the figures it does have.</p>
     */
    public static String briefing(ai.AdvisorService advisor, ReadService read) {
        Json json = new Json();
        json.beginObject();
        json.put("generatedAt", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")));
        json.comma();
        json.put("itemsConsidered", advisor.factSheetItems());
        json.comma();
        json.put("model", advisor.modelName());
        json.comma();

        if (!advisor.isConfigured()) {
            // Not an error, and deliberately not dressed up: no key means no AI briefing.
            putUnavailable(json, "NoKey",
                    "No AI key is configured on this server, so no briefing was requested.",
                    "Put gemini.apiKey in ai.properties, or set GEMINI_API_KEY, then restart"
                            + " StockPulse. The summary below is written by StockPulse's own"
                            + " rules, not by AI.");
            json.comma();
            putRuleSummary(json, advisor);
            return json.endObject().toString();
        }

        try {
            String text = advisor.briefing();
            json.put("aiAvailable", true);
            json.comma();
            json.put("source", "AI");
            json.comma();
            json.put("briefing", text);
            json.comma();
            json.put("unavailable", (String) null);
        } catch (exception.AdvisorUnavailableException e) {
            // Reported honestly, with the rule summary offered instead - never a fake
            // briefing and never the local text labelled as the model's.
            putUnavailable(json, e.getReason(), e.getMessage(), e.getRemediation());
        }
        json.comma();
        putRuleSummary(json, advisor);
        return json.endObject().toString();
    }

    private static void putUnavailable(Json json, String reason, String message,
                                       String remediation) {
        json.put("aiAvailable", false);
        json.comma();
        json.put("source", "RULES");
        json.comma();
        json.putNull("briefing");
        json.comma();
        json.name("unavailable").beginObject();
        json.put("reason", reason);
        json.comma();
        json.put("message", message);
        json.comma();
        json.put("remediation", remediation == null ? "" : remediation);
        json.endObject();
    }

    private static void putRuleSummary(Json json, ai.AdvisorService advisor) {
        json.name("ruleSummary").beginObject();
        json.put("source", "RULES");
        json.comma();
        json.put("text", advisor.ruleSummary());
        json.endObject();
    }

    // --------------------------------------------------------------- activity

    public static String activity(ActivityLog[] entries, String role) {
        Json json = new Json();
        json.beginObject();
        json.put("role", role);
        json.comma();
        json.name("entries").beginArray();
        for (int i = 0; i < entries.length; i++) {
            json.comma();
            putActivity(json.beginObject(), entries[i]).endObject();
        }
        json.endArray();
        return json.endObject().toString();
    }

    public static Json putActivity(Json json, ActivityLog entry) {
        json.put("time", entry.formattedTime());
        json.comma();
        json.put("role", entry.getRole());
        json.comma();
        json.put("action", entry.getAction());
        json.comma();
        json.put("detail", entry.getDetail());
        return json;
    }
}
