import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import app.Wire;
import data.Snapshot;
import exception.DataFileException;
import server.ApiHandler;
import server.WebServer;
import server.WriteGuard;

/**
 * Starts a real server on a scratch data folder and drives it over HTTP.
 *
 * <p>This is a manual check program in the same style as the others: no test framework,
 * plain assertions, readable output. It deliberately does not reuse the service layer
 * directly - the point is to prove the wire format, the status codes and the routing,
 * which only exist at the HTTP boundary.</p>
 *
 * <p>It runs against {@code out/api-check/data} so it never touches the real data
 * folder, and it restarts the server at the end to prove the data was saved.</p>
 */
public final class ApiCheck {

    private static int passed = 0;
    private static int failed = 0;
    private static HttpClient client;
    private static String base;

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of("out", "api-check");
        deleteTree(scratch);

        // Run from the scratch folder so DataStore's relative data path points there.
        // The server is built by hand here rather than via main(), so a failure cannot
        // call System.exit and kill the check.
        Wire wiring = Wire.build(scratch.resolve("data"));
        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        base = server.baseUrl();
        client = HttpClient.newHttpClient();

        try {
            section("Startup and the route index");
            Response index = get("/api");
            check("GET /api answers 200", index.status == 200);
            check("the index lists the agreed routes",
                    index.body.contains("POST /api/items/{id}/sale")
                            && index.body.contains("PUT /api/settings"));
            check("the index lists adding and editing items",
                    index.body.contains("POST /api/items,")
                            && index.body.contains("PUT /api/items/{id},"));
            check("the index lists the advisor briefing",
                    index.body.contains("GET /api/advisor/briefing"));
            check("the index names the role header",
                    index.body.contains("\"roleHeader\":\"X-Demo-Role\""));
            check("the index states the per-request role design",
                    index.body.contains("\"roleDesign\"")
                            && index.body.contains("not authentication"));
            check("the index reports that writes are open",
                    index.body.contains("\"writesBlocked\":false"));

            section("Bootstrap");
            Response boot = get("/api/bootstrap");
            check("GET /api/bootstrap answers 200", boot.status == 200);
            check("bootstrap carries ok:true", boot.body.startsWith("{\"ok\":true,\"data\":{"));
            check("settings are included", boot.body.contains("\"salesWindowDays\":14"));
            check("seasons are included", boot.body.contains("\"name\":\"Rainy\""));
            check("the effective date is sent three ways",
                    boot.body.contains("\"iso\":") && boot.body.contains("\"text\":"));
            check("non-JSON garbage is absent", !boot.body.contains("\"\"\""));

            Response asManager = get("/api/bootstrap", "Manager");
            check("Manager may manage", asManager.body.contains("\"canManage\":true"));
            check("bootstrap echoes the role that asked",
                    asManager.body.contains("\"role\":\"Manager\""));
            Response asStaff = get("/api/bootstrap", "Staff");
            check("Staff may not manage", asStaff.body.contains("\"canManage\":false"));
            check("bootstrap echoes Staff on a Staff request",
                    asStaff.body.contains("\"role\":\"Staff\""));
            Response asNobody = get("/api/bootstrap");
            check("bootstrap with no role defaults to the empty role",
                    asNobody.body.contains("\"role\":\"\"")
                            && asNobody.body.contains("\"canManage\":false"));
            check("bootstrap reports that writes are open",
                    asNobody.body.contains("\"writesBlocked\":false"));

            section("Dashboard");
            Response dash = get("/api/dashboard");
            check("GET /api/dashboard answers 200", dash.status == 200);
            check("totals are computed in Java", dash.body.contains("\"stockValueAtCost\":")
                    && dash.body.contains("\"stockoutCount\":"));
            check("flags are ranked and typed",
                    dash.body.contains("\"type\":\"STOCKOUT\"")
                            || dash.body.contains("\"type\":\"EXPIRY\"")
                            || dash.body.contains("\"type\":\"EXPIRED\""));
            check("every item carries its own figures",
                    dash.body.contains("\"daysOfCoverText\":")
                            && dash.body.contains("\"stockValueFormula\":"));
            check("an infinite cover is not sent as a number",
                    !dash.body.contains("\"daysOfCover\":Infinity")
                            && !dash.body.contains("NaN"));
            // The "5 bottle" bug: a countable unit has to be pluralised in the stock line.
            // The assertion is on the exact singular form followed by a space, because
            // "bottles" also contains "bottle" - an earlier version of this check passed
            // for the wrong reason for exactly that reason.
            check("stock descriptions pluralise countable units",
                    dash.body.contains("5 bottles in 1 batch")
                            && !dash.body.contains(" bottle in")
                            && !dash.body.contains(" bag in"));
            check("measurement symbols are not pluralised",
                    dash.body.contains("24 L in 2 batches")
                            && !dash.body.contains(" Ls ")
                            && !dash.body.contains(" pcss"));
            check("units already written in the plural are left alone",
                    dash.body.contains("4 pcs in 1 batch"));

            section("Item list and detail");
            Response items = get("/api/items");
            check("GET /api/items answers 200", items.status == 200);
            check("the item count is reported", items.body.contains("\"count\":6"));

            Response filtered = get("/api/items?search=milk");
            check("search filters the list", filtered.body.contains("\"count\":1"));
            Response dairy = get("/api/items?category=Dairy");
            check("category filters the list", dairy.body.contains("\"count\":"));
            check("a nonsense category returns none", get("/api/items?category=Nope")
                    .body.contains("\"count\":0"));

            Response detail = get("/api/items/I001");
            check("GET /api/items/I001 answers 200", detail.status == 200);
            check("detail includes the batches", detail.body.contains("\"batchId\":\"B0001\""));
            check("detail includes recent sales", detail.body.contains("\"recentSales\":"));
            check("detail shows the arithmetic", detail.body.contains("\"breakdown\":")
                    && detail.body.contains("per day"));

            section("Error contract");
            Response missing = get("/api/items/NOPE");
            check("unknown item is 404", missing.status == 404);
            check("unknown item names the exception",
                    missing.body.contains("\"code\":\"ItemNotFoundException\""));
            check("unknown item echoes the id looked for",
                    missing.body.contains("NOPE"));

            Response badRoute = get("/api/nothing-here");
            check("unknown route is 404", badRoute.status == 404);
            check("unknown route is distinguishable",
                    badRoute.body.contains("\"code\":\"RouteNotFound\""));

            Response wrongMethod = post("/api/dashboard", "qty=1", "");
            check("wrong method on a read route is 405", wrongMethod.status == 405);
            check("405 names the methods that work",
                    wrongMethod.body.contains("\"code\":\"MethodNotAllowed\"")
                            && wrongMethod.body.contains("GET"));

            section("Selling");
            Response sale = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a sale succeeds with 200", sale.status == 200);
            check("the sale is confirmed with its batches",
                    sale.body.contains("\"batchDraws\":") && sale.body.contains("\"message\":"));
            check("the remaining stock is reported",
                    sale.body.contains("\"remainingDescription\""));

            Response tooMany = post("/api/items/I001/sale", "qty=9999", "Staff");
            check("selling more than there is gives 409", tooMany.status == 409);
            check("the shortfall is named",
                    tooMany.body.contains("\"code\":\"InsufficientStockException\"")
                            && tooMany.body.contains("available="));

            Response badQty = post("/api/items/I001/sale", "qty=abc", "Staff");
            check("a non-numeric quantity gives 400", badQty.status == 400);
            check("the bad field is named", badQty.body.contains("field=qty"));

            Response zeroQty = post("/api/items/I001/sale", "qty=0", "Staff");
            check("a zero quantity gives 400", zeroQty.status == 400);

            Response dupe = post("/api/items/I001/sale", "qty=1&qty=2", "Staff");
            check("a repeated field is refused", dupe.status == 400);
            check("the repeated field is named", dupe.body.contains("qty"));

            Response noRole = post("/api/items/I001/sale", "qty=1", "");
            check("no role cannot sell", noRole.status == 403);
            check("the refusal names the action",
                    noRole.body.contains("\"code\":\"RoleNotPermittedException\"")
                            && noRole.body.contains("record a sale"));

            section("Role gating on manager actions");
            Response staffUndo = post("/api/items/I001/undo-sale", "", "Staff");
            check("Staff cannot undo a sale", staffUndo.status == 403);
            check("the refusal names the undo action",
                    staffUndo.body.contains("undo a sale"));

            Response managerUndo = post("/api/items/I001/undo-sale", "", "Manager");
            check("Manager can undo the sale just made", managerUndo.status == 200);
            check("the undo reports what it reversed", managerUndo.body.contains("\"undone\":true"));

            Response staffSettings = put("/api/settings", "salesWindowDays=21", "Staff");
            check("Staff cannot change settings", staffSettings.status == 403);

            section("Deliveries");
            Response delivery = post("/api/items/I001/delivery",
                    "qty=12&expiryDate=20/12/2026&unitCost=2.20", "Staff");
            check("a delivery succeeds", delivery.status == 200);
            check("the new batch is returned",
                    delivery.body.contains("\"expiryDate\":\"20/12/2026\""));

            Response noExpiry = post("/api/items/I001/delivery", "qty=12&unitCost=2.20", "Staff");
            check("a perishable delivery without expiry gives 400", noExpiry.status == 400);
            check("the missing expiry is named", noExpiry.body.contains("expiryDate"));

            Response pastExpiry = post("/api/items/I001/delivery",
                    "qty=12&expiryDate=01/01/2020&unitCost=2.20", "Staff");
            check("an already-expired delivery gives 400", pastExpiry.status == 400);

            Response badDate = post("/api/items/I001/delivery",
                    "qty=1&expiryDate=2026-12-20&unitCost=2.20", "Staff");
            check("an ISO date is refused with a format message", badDate.status == 400);
            check("the expected format is stated", badDate.body.contains("dd/MM/yyyy"));

            section("Waste");
            Response wrongBatch = post("/api/items/I001/waste",
                    "batchId=B0006&qty=1&reason=Damaged", "Staff");
            check("waste against another item's batch is 404", wrongBatch.status == 404);

            Response waste = post("/api/items/I001/waste",
                    "batchId=B0001&qty=1&reason=Damaged in transit", "Staff");
            check("waste succeeds", waste.status == 200);
            check("the loss is valued", waste.body.contains("\"valueLost\":"));

            Response noReason = post("/api/items/I001/waste", "batchId=B0001&qty=1", "Staff");
            check("waste without a reason gives 400", noReason.status == 400);
            check("the missing reason is named", noReason.body.contains("reason"));

            section("Adding and editing items");
            // Staff must be refused, and refused with a message naming the action.
            Response staffAdd = post("/api/items",
                    "itemId=I900&itemType=PERISHABLE&name=Test&category=Test&unit=kg"
                            + "&sellingPrice=10&unitCost=6&shelfLifeDays=5&packSize=1"
                            + "&supplierName=Test&leadTimeDays=1", "Staff");
            check("Staff cannot add an item", staffAdd.status == 403);
            check("the refusal names the add action", staffAdd.body.contains("add an item"));

            Response staffEdit = put("/api/items/I001",
                    "itemType=PERISHABLE&name=Hijacked&category=Dairy&unit=L"
                            + "&sellingPrice=1&unitCost=1&shelfLifeDays=5&packSize=1"
                            + "&supplierName=X&leadTimeDays=1", "Staff");
            check("Staff cannot edit an item", staffEdit.status == 403);
            check("the refusal names the edit action", staffEdit.body.contains("edit an item"));

            // A duplicate id must be refused before anything is stored.
            Response dupAdd = post("/api/items",
                    "itemId=I001&itemType=PERISHABLE&name=Dup&category=Dairy&unit=L"
                            + "&sellingPrice=1&unitCost=1&shelfLifeDays=5&packSize=1"
                            + "&supplierName=X&leadTimeDays=1", "Manager");
            check("a duplicate item id is refused", dupAdd.status == 400);
            check("the duplicate is blamed on itemId", dupAdd.body.contains("field=itemId"));

            Response added = post("/api/items",
                    "itemId=I900&itemType=LONG_LIFE&name=Test+Beans&category=Dry"
                            + "&unit=bag&sellingPrice=18.5&unitCost=12&shelfLifeDays=180"
                            + "&packSize=6&supplierName=Test+Supplier&leadTimeDays=3",
                    "Manager");
            check("Manager can add an item", added.status == 200);
            check("the new item is echoed", added.body.contains("\"itemId\":\"I900\""));
            check("the item type is carried through", added.body.contains("\"itemType\":\"LONG_LIFE\""));
            check("a long-life item reports no expiry", added.body.contains("\"noExpiry\":true"));
            check("a new item starts out of stock", added.body.contains("out of stock"));

            Response nowListed = get("/api/items");
            check("the new item is in the list", nowListed.body.contains("\"count\":7"));

            // A bad numeric field must name that field, not a generic message.
            Response badPrice = post("/api/items",
                    "itemId=I901&itemType=LONG_LIFE&name=Bad&category=Dry&unit=bag"
                            + "&sellingPrice=abc&unitCost=1&shelfLifeDays=1&packSize=1"
                            + "&supplierName=X&leadTimeDays=1", "Manager");
            check("a non-numeric price is refused", badPrice.status == 400);
            check("the bad price field is named", badPrice.body.contains("field=sellingPrice"));

            // A negative cost reaches the model, which rejects it; the field must survive.
            Response negativeCost = post("/api/items",
                    "itemId=I902&itemType=LONG_LIFE&name=Bad&category=Dry&unit=bag"
                            + "&sellingPrice=1&unitCost=-5&shelfLifeDays=1&packSize=1"
                            + "&supplierName=X&leadTimeDays=1", "Manager");
            check("a negative cost is refused", negativeCost.status == 400);
            check("the cost field is named, not the unit field",
                    negativeCost.body.contains("field=unitCost"));

            Response badType = post("/api/items",
                    "itemId=I903&itemType=SPARKLY&name=Bad&category=Dry&unit=bag"
                            + "&sellingPrice=1&unitCost=1&shelfLifeDays=1&packSize=1"
                            + "&supplierName=X&leadTimeDays=1", "Manager");
            check("an unknown item type is refused", badType.status == 400);
            check("the type field is named", badType.body.contains("field=itemType"));

            Response badPack = post("/api/items",
                    "itemId=I904&itemType=LONG_LIFE&name=Bad&category=Dry&unit=bag"
                            + "&sellingPrice=1&unitCost=1&shelfLifeDays=1&packSize=0"
                            + "&supplierName=X&leadTimeDays=1", "Manager");
            check("a pack size of zero is refused", badPack.status == 400);

            Response noName = post("/api/items",
                    "itemId=I905&itemType=LONG_LIFE&category=Dry&unit=bag"
                            + "&sellingPrice=1&unitCost=1&shelfLifeDays=1&packSize=1"
                            + "&supplierName=X&leadTimeDays=1", "Manager");
            check("a missing name is refused", noName.status == 400);
            check("the missing name field is named", noName.body.contains("field=name"));

            Response badItemSeason = post("/api/items",
                    "itemId=I906&itemType=LONG_LIFE&name=S&category=Dry&unit=bag"
                            + "&sellingPrice=1&unitCost=1&shelfLifeDays=1&packSize=1"
                            + "&supplierName=X&leadTimeDays=1&season=Monsoon", "Manager");
            check("an unknown season is refused", badItemSeason.status == 400);
            check("the season field is named", badItemSeason.body.contains("field=season"));

            Response edited = put("/api/items/I900",
                    "itemType=LONG_LIFE&name=Test+Beans+Large&category=Dry&unit=bags"
                            + "&sellingPrice=20&unitCost=13&shelfLifeDays=200&packSize=6"
                            + "&supplierName=Test+Supplier&leadTimeDays=4", "Manager");
            check("Manager can edit an item", edited.status == 200);
            check("the edit is reflected in the response",
                    edited.body.contains("Test Beans Large")
                            && edited.body.contains("\"sellingPrice\":20"));
            check("the edited unit is used for plurals",
                    edited.body.contains("bags") && !edited.body.contains("bagss"));

            Response editMissing = put("/api/items/NOPE",
                    "itemType=LONG_LIFE&name=X&category=Dry&unit=bag&sellingPrice=1"
                            + "&unitCost=1&shelfLifeDays=1&packSize=1&supplierName=X"
                            + "&leadTimeDays=1", "Manager");
            check("editing an unknown item is 404", editMissing.status == 404);

            section("Settings and seasons");
            Response settings = put("/api/settings",
                    "salesWindowDays=21&safetyMarginDays=2", "Manager");
            check("Manager can change settings", settings.status == 200);
            check("the new window is echoed", settings.body.contains("\"salesWindowDays\":21"));

            Response badWindow = put("/api/settings", "salesWindowDays=0", "Manager");
            check("a zero window is refused", badWindow.status == 400);

            Response demo = put("/api/settings", "demoDate=15/04/2026", "Manager");
            check("a demo date is accepted", demo.status == 200);
            check("the response marks the demo date", demo.body.contains("\"demoDate\":\"15/04/2026\""));

            Response rolled = get("/api/bootstrap");
            check("the demo date changes the effective date",
                    rolled.body.contains("\"iso\":\"2026-04-15\""));

            Response cleared = put("/api/settings", "demoDate=", "Manager");
            check("the demo date can be cleared",
                    cleared.status == 200 && cleared.body.contains("\"demoDate\":null"));

            Response badSeason = put("/api/seasons/Dry", "startMonth=13&endMonth=2", "Manager");
            check("an impossible month is refused", badSeason.status == 400);
            check("the season exception is named",
                    badSeason.body.contains("InvalidSeasonException")
                            || badSeason.body.contains("InvalidInputException"));

            Response season = put("/api/seasons/Dry", "startMonth=6&endMonth=9", "Manager");
            check("a valid month change succeeds", season.status == 200);
            check("the new range is described", season.body.contains("\"monthRange\":\"Jun - Sep\""));

            Response unknownSeason = put("/api/seasons/Monsoon", "startMonth=1&endMonth=2", "Manager");
            check("an unknown season is 404", unknownSeason.status == 404);

            section("An item's type cannot be changed by an edit");
            // I900 was added as a LONG_LIFE item and I001 is a PERISHABLE one, so the two
            // refusals together cover both directions. Each attempt is a full, otherwise
            // valid body: only the type is wrong, so a 400 can only be about the type.
            String longLifeBody = "itemType=LONG_LIFE&name=Test+Beans&category=Dry&unit=bag"
                    + "&sellingPrice=18.5&unitCost=12&shelfLifeDays=180&packSize=6"
                    + "&supplierName=Test+Supplier&leadTimeDays=3";
            String perishableBody = "itemType=PERISHABLE&name=Fresh+Milk+1L&category=Dairy&unit=L"
                    + "&sellingPrice=3.4&unitCost=2.2&shelfLifeDays=7&packSize=1"
                    + "&supplierName=Dairy+Farm&leadTimeDays=2";

            Response perishableToLongLife = put("/api/items/I001", longLifeBody, "Manager");
            check("PERISHABLE to LONG_LIFE is refused", perishableToLongLife.status == 400);
            check("the type change is blamed on itemType",
                    perishableToLongLife.body.contains("field=itemType"));
            check("the refusal names both types",
                    perishableToLongLife.body.contains("PERISHABLE")
                            && perishableToLongLife.body.contains("LONG_LIFE"));
            check("the refusal suggests adding a new item",
                    perishableToLongLife.body.contains("Add a new item"));

            Response longLifeToPerishable = put("/api/items/I900", perishableBody, "Manager");
            check("LONG_LIFE to PERISHABLE is refused", longLifeToPerishable.status == 400);
            check("the reverse change is blamed on itemType",
                    longLifeToPerishable.body.contains("field=itemType"));

            // The refusals must have changed nothing at all: not the stored object, not
            // the saved file. Both are checked, because a rule that threw after the copy
            // had started would pass a response-only check.
            check("the refused edit left the stored items alone",
                    "PERISHABLE".equals(itemOf(wiring, "I001").itemType())
                            && "LONG_LIFE".equals(itemOf(wiring, "I900").itemType()));
            // The names are the ones the last successful edit left, which is the point:
            // a refused edit must not have touched them, and must not have been allowed
            // to change anything else either.
            check("the refused edit left the names alone",
                    "Fresh Milk 1L".equals(itemOf(wiring, "I001").getName())
                            && "Test Beans Large".equals(itemOf(wiring, "I900").getName()));

            String savedItems = Files.readString(scratch.resolve("data").resolve("items.txt"));
            check("the saved file still says I001 is PERISHABLE",
                    savedItems.contains("I001|Fresh Milk 1L|Dairy|PERISHABLE|"));
            // The edit earlier in this run renamed I900, so the line is checked with the
            // name as it stands now rather than the name it was added with.
            check("the saved file still says I900 is LONG_LIFE",
                    savedItems.contains("I900|Test Beans Large|Dry|LONG_LIFE|"));
            check("no failed type change was written to the activity log",
                    !Files.readString(scratch.resolve("data").resolve("activity.txt"))
                            .contains("Edited item I001"));

            section("A failed season save is rolled back, seasons included");
            checkFailedSeasonSave(scratch);

            section("Activity log");
            Response activity = get("/api/activity");
            check("GET /api/activity answers 200", activity.status == 200);
            check("the sale we made is logged", activity.body.contains("\"action\":\"SALE\""));
            check("the delivery we made is logged", activity.body.contains("DELIVERY"));
            check("the waste we made is logged", activity.body.contains("WASTE"));
            check("the item we added is logged", activity.body.contains("\"action\":\"ITEM\""));

            section("A failed save is rolled back and blocks further writes");
            checkFailedSave(scratch);

            section("A save that fails part way through the files");
            checkPartiallyFailedSave(scratch);

            section("The advisor briefing over HTTP");
            checkAdvisorOverHttp();
        } finally {
            server.stop();
        }

        section("Saved data survives a restart");
        String salesBefore = Files.readString(scratch.resolve("data").resolve("sales.txt"));
        String settingsBefore = Files.readString(scratch.resolve("data").resolve("settings.txt"));

        check("sales.txt was written", salesBefore.contains("|26/11/2026|"));
        check("the changed window was saved", settingsBefore.contains("SALES_WINDOW_DAYS=21"));
        check("the demo date was NOT saved", !settingsBefore.contains("2026-04-15"));

        Wire reloaded = Wire.build(scratch.resolve("data"));
        check("the reloaded store has the sale",
                reloaded.sales.count() > 0);
        check("the reloaded settings keep the new window",
                reloaded.settings.getSalesWindowDays() == 21);
        check("the reloaded settings have no demo date",
                reloaded.settings.getDemoDate() == null);
        check("the reloaded season has the new months",
                reloaded.seasons.findByName("Dry").getStartMonth() == 6);

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------ failed-save check

    /**
     * Proves the save-failure story end to end, on a throwaway folder.
     *
     * <p>The guard's save is replaced by one that can be switched from working to
     * failing. That is the only way to reach the branch honestly: a real disk failure is
     * not something a check program can produce on demand, and faking the exception
     * inside the route would test the fake rather than the route.</p>
     *
     * <p>Three things must be true afterwards: the write answered 503 and said the change
     * was not saved, the change was undone so reads still match the files, and every
     * later write is refused without touching the store.</p>
     */
    private static void checkFailedSave(Path parent) throws Exception {
        Path scratch = parent.resolve("fail-save");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        final boolean[] failNextSave = {false};

        // The guard is built here, not in Wire, so the save can be made to fail. The
        // snapshot factory still uses the same real stores, which is what makes the
        // rollback observable.
        WriteGuard guard = new WriteGuard(() -> {
            if (failNextSave[0]) {
                throw new DataFileException("items.txt", 0, "the disk is full (simulated)");
            }
            return wiring.dataStore.saveAll(wiring.items, wiring.batches, wiring.sales,
                    wiring.waste, wiring.seasons, wiring.activity);
        });
        server.Routes routes = new server.Routes(wiring.read, wiring.inventory, wiring.seasons,
                wiring.settings, guard,
                () -> new Snapshot(wiring.items, wiring.batches, wiring.sales, wiring.waste,
                        wiring.seasons, wiring.activity, wiring.settings), wiring.advisor);

        WebServer failing = WebServer.start(0, new ApiHandler(routes));
        String savedBase = base;
        base = failing.baseUrl();
        try {
            double stockBefore = stockOf(wiring, "I001");
            Response healthy = post("/api/items/I001/sale", "qty=1", "Staff");
            check("a sale still works while the disk is fine", healthy.status == 200);
            check("the healthy sale really moved stock",
                    Math.abs(stockOf(wiring, "I001") - stockBefore) > 0.0001);

            double stockNow = stockOf(wiring, "I001");
            int salesNow = wiring.sales.count();

            // Switch the disk off, then try to sell.
            failNextSave[0] = true;
            Response broken = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a failed save answers 503, not 500", broken.status == 503);
            check("the failure is coded as blocked storage",
                    broken.body.contains("\"code\":\"StorageBlockedException\""));
            check("the message says the change was not saved",
                    broken.body.contains("was not saved"));
            check("the message says it was undone in memory and on disk",
                    broken.body.contains("undone in memory and on disk"));
            check("the detail records the rollback",
                    broken.body.contains("rolledBack=true")
                            && broken.body.contains("writesBlocked=true"));
            check("no further write is accepted after a failure",
                    routes.guard().isBlocked());

            // The rollback must have put memory back: same stock, same sale count.
            check("the failed sale was rolled back out of memory",
                    Math.abs(stockOf(wiring, "I001") - stockNow) < 0.0001);
            check("no sale record survived the failed save",
                    wiring.sales.count() == salesNow);

            // Fixing the disk must NOT unblock writing: only a restart may.
            failNextSave[0] = false;
            Response afterFix = post("/api/items/I001/sale", "qty=1", "Staff");
            check("writes stay refused even once the disk is fixed", afterFix.status == 503);
            check("the refusal keeps the original reason",
                    afterFix.body.contains("the disk is full"));
            check("the stock did not move on a refused write",
                    Math.abs(stockOf(wiring, "I001") - stockNow) < 0.0001);

            // A refused write must stop before the business rule runs, not after.
            int batchesBefore = countBatchesOf(wiring, "I001");
            Response refusedDelivery = post("/api/items/I001/delivery",
                    "qty=5&expiryDate=20/12/2026&unitCost=2.20", "Staff");
            check("a delivery is refused too", refusedDelivery.status == 503);
            check("the delivery did not create a batch",
                    countBatchesOf(wiring, "I001") == batchesBefore);

            // Reads keep working, which is what lets the page stay useful.
            Response stillReads = get("/api/dashboard");
            check("reads keep working while writes are blocked", stillReads.status == 200);
            check("bootstrap reports that writes are blocked",
                    get("/api/bootstrap").body.contains("\"writesBlocked\":true"));
            check("the index reports that writes are blocked",
                    get("/api").body.contains("\"writesBlocked\":true"));

            // A restarted server on the same folder is writable again.
            WebServer retry = WebServer.start(0, new ApiHandler(Wire.build(
                    scratch.resolve("data")).routes));
            base = retry.baseUrl();
            try {
                Response afterRestart = post("/api/items/I001/sale", "qty=1", "Staff");
                check("a restarted server accepts writes again", afterRestart.status == 200);
            } finally {
                retry.stop();
            }
        } finally {
            failing.stop();
            base = savedBase;
        }
    }

    // ----------------------------------------------------- failed-save checks

    /**
     * Proves a failed save is reversed on disk as well as in memory.
     *
     * <p>The scenario is the one the design admits is possible: the save gets part of the
     * way through and then fails. That is arranged by making one of the real data files
     * impossible to replace half way through the set, so the swap loop really does
     * replace some files before it fails. The failure is not injected into
     * {@code DataStore} - it happens to {@code DataStore} - which is what makes the
     * staging and restore code the thing under test rather than a stand-in for it.</p>
     *
     * <p>The outcome is stated precisely, because an earlier version of this check got it
     * wrong and only passed because of a bug. The four files replaced before the failure
     * <em>are</em> put back byte for byte - that is the reversal this check is about - but
     * the folder is left one data file short, and no backup of that file exists (it was a
     * directory, not a file). A load would therefore fail on the missing file, so the
     * dataset is reported as <em>not</em> consistent and the marker is kept. Calling it a
     * clean rollback, as the check used to, would have told the user to restart into a
     * folder that cannot be loaded.</p>
     *
     * <p>After the 503 the folder is reloaded from scratch, so the assertions are about
     * what is actually on disk, not about what memory claims.</p>
     */
    private static void checkPartiallyFailedSave(Path parent) throws Exception {
        Path scratch = parent.resolve("partial-save");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");

        // A sale is the smallest change that touches several files: the batch loses
        // quantity, a sale line is added and the activity log grows.
        String salesBefore = Files.readString(dataDir.resolve("sales.txt"));
        double stockBefore = stockOf(wiring, "I001");

        // Waste is written fifth of seven, so blocking it leaves seasons, items, batches
        // and sales already replaced: a genuine half-finished save.
        Path blocked = dataDir.resolve("waste.txt");
        Files.delete(blocked);
        Files.createDirectory(blocked);

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a save that fails part way answers 503", failed.status == 503);
            check("the message says the change was not saved",
                    failed.body.contains("was not saved"));
            check("memory was still put back, so the screen is not wrong", failed.body
                    .contains("rolledBack=true"));
            // The replaced files are restored, but the folder cannot be loaded, so this
            // must be reported as needing recovery - not as a clean rollback.
            check("the dataset is reported as needing recovery",
                    failed.body.contains("datasetConsistent=false")
                            && failed.body.contains("restartLoadsSavedData=false"));
            check("the message says the directory was left untouched",
                    failed.body.contains("a directory is in the way of waste.txt,"
                            + " and it was left untouched"));
            check("writes are blocked after the failed save", wiring.guard.isBlocked());
            check("the guard knows the dataset is not loadable as it stands",
                    !wiring.guard.isDatasetConsistent());
        } finally {
            server.stop();
            base = savedBase;
            // Put the folder back the way a person would: remove the obstacle. A folder
            // in a data file's place is not "cleaned up" by deleting the file name - the
            // directory has to be emptied and removed, or the folder stays one file short.
            deleteTree(blocked);
        }

        check("reversing the save kept the sale in memory only as far as the rollback",
                Math.abs(stockOf(wiring, "I001") - stockBefore) < 0.0001);
        check("sales.txt on disk came back byte for byte",
                salesBefore.equals(Files.readString(dataDir.resolve("sales.txt"))));

        // A failed save does not recreate a file it could not replace, and should not: the
        // file was replaced by a folder from outside, so guessing new contents for it
        // would invent data. What recovery must guarantee is that the other files were
        // put back and that writing works again, which is checked by saving and reloading.
        check("the six other data files were all put back", countDataFiles(dataDir) == 6);
        check("no partial data file was left in waste.txt's place", !Files.exists(blocked));
        check("no sale was saved, so the rollback reached the disk too",
                !Files.readString(dataDir.resolve("sales.txt")).contains("|2|"));
        // One file is missing, so this folder must not be loaded as it is.
        boolean refused = false;
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
        }
        check("a fresh start refuses the folder while it is one file short", refused);

        // Recovery, end to end: one clean save rewrites all seven files, which is what
        // makes a folder that lost a file usable again. The person's first step is the one
        // the hint names - clear the leftovers - because the marker must go before a new
        // start can read the folder.
        Files.deleteIfExists(dataDir.resolve("save-in-progress.tmp"));
        wiring.dataStore.saveAll(wiring.items, wiring.batches, wiring.sales, wiring.waste,
                wiring.seasons, wiring.activity);
        check("a clean save restores all seven files", countDataFiles(dataDir) == 7);
        check("the clean save cleared the marker",
                !Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // Only now can the folder be loaded as a whole: reloading is the strongest proof
        // that what is on disk is a complete, self-consistent dataset.
        Wire reloaded = Wire.build(dataDir);
        check("the reloaded stock matches the stock before the failed sale",
                Math.abs(stockOf(reloaded, "I001") - stockBefore) < 0.0001);
        check("the reloaded item count is unchanged",
                reloaded.items.count() == wiring.items.count());

        // No litter: a leftover staging or backup file would confuse the next start.
        check("no staging files were left behind", countFiles(dataDir, ".tmp") == 0);
        check("no backup files were left behind", countFiles(dataDir, ".bak") == 0);

        checkStagingFailure(parent);
        checkUnrecoverableSave(parent);
    }

    /**
     * Proves the simplest failure of all leaves a folder that starts normally: the save
     * never reached a single data file.
     *
     * <p>The case is easy to overlook because nothing is half-written - which is exactly
     * why it is worth a check. If the marker survived here, a save that ran out of disk
     * before touching anything would still leave a folder the next start refuses, and the
     * 503's advice to simply restart would be wrong for the most ordinary failure there
     * is.</p>
     *
     * <p>The failure happens for real rather than through the injector: waste.txt is
     * replaced by a folder before the save starts, and staging writes to
     * {@code waste.txt.tmp}, which the directory now blocks. Nothing real has changed at
     * that point, so the expectation is a folder that loads with a fresh {@link Wire}.</p>
     */
    private static void checkStagingFailure(Path parent) throws Exception {
        Path scratch = parent.resolve("staging-failure");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        double stockBefore = stockOf(wiring, "I001");
        int saleCountBefore = wiring.sales.count();

        // A directory at the staging name. The data file itself is untouched, so this
        // fails while the first real file is still being prepared - after seasons, items,
        // batches, sales and waste have all been staged.
        Path obstacle = dataDir.resolve("waste.txt.tmp");
        Files.createDirectory(obstacle);
        Path inner = obstacle.resolve("keep.txt");
        Files.writeString(inner, "still here" + System.lineSeparator());

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a save that fails while staging answers 503", failed.status == 503);
            check("it says the save was abandoned before any data file was replaced",
                    failed.body.contains("before any data file was replaced"));
            check("it is reported as a clean reversal",
                    failed.body.contains("datasetConsistent=true")
                            && failed.body.contains("rolledBack=true"));
            check("the wording says a normal start will load the saved data",
                    failed.body.contains("load the saved data as usual"));
        } finally {
            server.stop();
            base = savedBase;
        }

        check("the unexpected directory at the staging name was left alone",
                Files.isDirectory(obstacle) && Files.exists(inner));
        check("memory was rolled back", Math.abs(stockOf(wiring, "I001") - stockBefore) < 0.0001
                && wiring.sales.count() == saleCountBefore);
        // Point 1 of the request, stated directly: a staging failure cleans up its
        // staging files and then removes the marker, so nothing is left to refuse.
        check("the failed save removed its own marker",
                !Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // The marker is the only leftover a start would ever refuse on here: a directory
        // at a staging name is not a data file, so the loader does not look at it. Remove
        // it and the folder loads, which is the proof the marker was really cleared and
        // not merely hidden behind the obstacle.
        deleteTree(obstacle);
        Wire restarted = Wire.build(dataDir);
        check("a fresh start loads the folder after a staging failure",
                restarted.items.count() == wiring.items.count());
        check("the reloaded stock is the stock from before the failed sale",
                Math.abs(stockOf(restarted, "I001") - stockBefore) < 0.0001);
    }

    /**
     * Proves a folder holding nothing but leftovers is never mistaken for a first run.
     *
     * <p>The sample data is written by {@link data.DataBootstrap} without going through
     * {@code loadAll}, so it never meets the interrupted-save refusal. The only thing that
     * can stop it seeding over a half-finished save is the first-run test itself, and an
     * empty folder plus a marker is exactly the shape that would slip past a test written
     * only in terms of data files.</p>
     */
    private static void checkMarkerOnlyFolderIsNotSeeded(Path parent) throws Exception {
        Path scratch = parent.resolve("marker-only");
        deleteTree(scratch);
        Path dataDir = scratch.resolve("data");
        Files.createDirectories(dataDir);

        // The shape left by a first run whose very first save was cut short, then tidied
        // by a person who deleted what they could: no data file, but the marker.
        Files.writeString(dataDir.resolve(data.DataStore.SAVE_IN_PROGRESS), "");
        boolean refused = false;
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
        }
        check("a folder holding only a marker is not seeded as a first run", refused);
        check("seeding did not create the data files", countDataFiles(dataDir) == 0);
        check("the marker is still there for a person to act on",
                Files.exists(dataDir.resolve(data.DataStore.SAVE_IN_PROGRESS)));

        // With the marker gone and no data files, it really is a first run again.
        Files.deleteIfExists(dataDir.resolve(data.DataStore.SAVE_IN_PROGRESS));
        Wire firstRun = Wire.build(dataDir);
        check("clearing the marker makes it a first run again",
                firstRun.seeded && countDataFiles(dataDir) == 7);
    }

    /**
     * Proves the opposite outcome is reported honestly: a save that fails <em>and</em>
     * cannot be reversed leaves the files mixed, and the 503 must say so.
     *
     * <p>No real disk will fail on request, so the failure is injected through
     * {@code DataStore.injectFaults}: waste.txt cannot be replaced, and batches.txt -
     * already replaced by then - cannot be put back. That is exactly the mixed dataset
     * the API must not describe as a clean rollback.</p>
     *
     * <p>An earlier version of this check tried to cause the same thing by replacing a
     * data file with a folder while the save was running. It did not work, and the reason
     * is worth recording: the restore clears whatever is in the way before writing the
     * backup, so it recovered and the honest-report branch was never reached. A folder is
     * a recoverable obstacle, not an unrecoverable one, which is why the injected fault is
     * named for the outcome rather than pretending to be a file-system state.</p>
     */
    private static void checkUnrecoverableSave(Path parent) throws Exception {
        Path scratch = parent.resolve("broken-save");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        String batchesBefore = Files.readString(dataDir.resolve("batches.txt"));
        String activityBefore = Files.readString(dataDir.resolve("activity.txt"));
        double stockBefore = stockOf(wiring, "I001");

        wiring.dataStore.injectFaults(fileName -> {
            if ("waste.txt".equals(fileName)) {
                return data.DataStore.Fault.SAVE_FAILS;
            }
            if ("batches.txt".equals(fileName)) {
                return data.DataStore.Fault.SAVE_AND_RESTORE_FAIL;
            }
            return data.DataStore.Fault.NONE;
        });

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("an unrecoverable save answers 503", failed.status == 503);
            check("the dataset is reported as not consistent",
                    failed.body.contains("datasetConsistent=false"));
            // The two facts are separate. Memory is restored from Java copies and did
            // succeed here, while the files could not be put back - so a single flag would
            // have had to be wrong about one of them.
            check("memory is reported as rolled back even though the files are not",
                    failed.body.contains("rolledBack=true")
                            && failed.body.contains("datasetConsistent=false"));
            check("the guard records that memory was rolled back",
                    wiring.guard.wasMemoryRolledBack());
            check("the message does not claim memory matches the files",
                    !failed.body.contains("match the files"));
            check("the message says the data may be partly written",
                    failed.body.contains("partly written") || failed.body.contains("mixture"));
            check("the message says the data needs recovery",
                    failed.body.contains("needs recovery"));
            check("the message warns against restarting for the saved data",
                    failed.body.contains("Do not restart expecting the saved data"));
            check("the message says startup would refuse to load the folder",
                    failed.body.contains("Startup will refuse to load this folder until the"
                            + " files are recovered"));
            check("the detail says a restart would not load the saved data",
                    failed.body.contains("restartLoadsSavedData=false"));
            check("the message names the backup files to keep",
                    failed.body.contains("batches.txt.bak"));
            check("the message says the screen still matches the last saved state",
                    failed.body.contains("screen still matches"));
            check("the guard records the inconsistency",
                    !wiring.guard.isDatasetConsistent());
            check("the guard keeps the recovery hint",
                    wiring.guard.recoveryHint().contains("needs recovery"));
        } finally {
            server.stop();
            base = savedBase;
            wiring.dataStore.injectFaults(null);
        }

        // Memory was still rolled back, so the running page is not wrong about the past -
        // it is the files that cannot be trusted. Saying which is which is the point.
        check("memory was still rolled back despite the failed restore",
                Math.abs(stockOf(wiring, "I001") - stockBefore) < 0.0001);

        // The disk really is mixed. batches was replaced and not put back, so its file
        // may hold either the new contents or the old ones - the point is that it was
        // not restored, and that the copy kept aside is the one from before the save.
        check("the backup of batches.txt was kept for recovery",
                Files.exists(dataDir.resolve("batches.txt.bak"))
                        && batchesBefore.equals(
                                Files.readString(dataDir.resolve("batches.txt.bak"))));
        check("the files after the failure still hold their old contents",
                Files.readString(dataDir.resolve("activity.txt")).equals(
                        activityBefore));
        // The marker stays on purpose: this folder must not be loaded by a fresh start
        // without someone looking at it first.
        check("the save marker was left behind by the unfinished save",
                Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // And a fresh start really does refuse it. The 503 says a plain restart would load
        // the mixture; this is that claim, tested against the loader rather than trusted.
        boolean refused = false;
        String refusalMessages = "";
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
            refusalMessages = e.getMessage();
        }
        check("a fresh start refuses a folder whose save could not be undone", refused);
        check("the refusal names the marker as what it found",
                refusalMessages.contains("save-in-progress.tmp"));
        check("the refusal says the files may be a mixture",
                refusalMessages.contains("mixture"));

        // A second request after the failure goes through requireWritable, which builds a
        // new exception. If that lost the recovery facts, the retry would be answered as a
        // clean rollback and would promise a restart that the loader has just refused.
        WebServer retry = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedRetryBase = base;
        base = retry.baseUrl();
        try {
            Response again = post("/api/items/I001/sale", "qty=1", "Staff");
            check("a retry after an unrecoverable save is still a 503", again.status == 503);
            check("the retry still reports an inconsistent dataset",
                    again.body.contains("datasetConsistent=false")
                            && again.body.contains("restartLoadsSavedData=false"));
            check("the retry warns against restarting for the saved data",
                    again.body.contains("Do not restart expecting the saved data"));
            check("the retry does not promise that a restart would load the data",
                    !again.body.contains("load the saved data as usual"));
            check("the retry keeps the recovery instructions",
                    again.body.contains("needs recovery"));
        } finally {
            retry.stop();
            base = savedRetryBase;
        }

        // Recovery is possible: one clean save rewrites every file from memory, which
        // still holds the last good state, and the folder loads again.
        wiring.dataStore.saveAll(wiring.items, wiring.batches, wiring.sales, wiring.waste,
                wiring.seasons, wiring.activity);
        check("a clean save puts the folder back together", countDataFiles(dataDir) == 7);
        check("no backup files were left behind after a clean save",
                countFiles(dataDir, ".bak") == 0);
        check("a clean save clears the marker", !Files.exists(
                dataDir.resolve("save-in-progress.tmp")));
        Wire reloaded = Wire.build(dataDir);
        check("the rebuilt folder loads and matches the stock before the failed sale",
                Math.abs(stockOf(reloaded, "I001") - stockBefore) < 0.0001);

        checkBackupCopyFailure(parent);
        checkUnexpectedDirectory(parent);
        checkInterruptedSaveRefused(parent);
        checkMarkerOnlyFolderIsNotSeeded(parent);
        // Cleanup failures after the point of no return. Each one used to be reported as a
        // save that did not happen - or, worse, as no failure at all - while the disk held
        // the new records and a start would have refused the folder.
        checkBackupCleanupFailure(parent);
        checkMarkerCleanupFailure(parent);
        checkStagingCleanupFailure(parent);
        checkSnapshotRollbackFailure(parent);
    }

    /**
     * Proves a failed memory restore is described without softening the disk's facts.
     *
     * <p>Two things can go wrong in one failed save, and they are independent: the files
     * may not go back, and the figures in memory may not go back. The response used to
     * decide everything from consistency alone, so a failed memory restore took the
     * "undone in memory and on disk" branch while its own detail said
     * {@code rolledBack=false} - the message contradicting the facts printed beneath it.</p>
     *
     * <p>The other direction is just as dangerous and is checked first here: when the save
     * itself reported a <em>mixture</em> on disk, recording the memory failure must not
     * rewrite that to "consistent, a restart will load it". The disk facts and the recovery
     * hint belong to the save, which is the only thing that looked at the folder. A memory
     * restore copies Java objects and never touches the disk, so it cannot know better.</p>
     */
    private static void checkSnapshotRollbackFailure(Path parent) throws Exception {
        checkSnapshotRestoreFailedOnMixedDisk(parent);
        checkSnapshotRestoreFailedOnCleanDisk(parent);
    }

    /**
     * The save reported a mixture and a folder a start would refuse, and then the memory
     * restore failed too. Every disk fact must survive, and nothing may promise a restart.
     */
    private static void checkSnapshotRestoreFailedOnMixedDisk(Path parent) throws Exception {
        Path scratch = parent.resolve("snapshot-rollback-mixed");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        double stockBefore = stockOf(wiring, "I001");
        int salesBefore = wiring.sales.count();

        final String hint = "Compare sales.txt and batches.txt with the .bak copies in the"
                + " data folder, then start again.";
        WriteGuard guard = new WriteGuard(() -> {
            throw new DataFileException("batches.txt", 0,
                    "the reversal of an earlier save also failed (simulated)",
                    null, false, false, false, hint);
        });
        server.Routes routes = withFailingRestore(wiring, guard);

        WebServer server = WebServer.start(0, new ApiHandler(routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a memory failure on a mixed disk answers 503", failed.status == 503);
            check("it is still reported as a failed save",
                    failed.body.contains("The change was not saved"));
            check("it is not reported as committed", failed.body.contains("committed=false"));
            check("the mixture the save reported is kept",
                    failed.body.contains("datasetConsistent=false"));
            check("the folder's unloadability is kept",
                    failed.body.contains("restartLoadsSavedData=false"));
            check("memory is reported as NOT rolled back",
                    failed.body.contains("rolledBack=false"));
            check("the save's recovery hint is kept",
                    failed.body.contains("What a person has to do about it: Compare sales.txt"
                            + " and batches.txt with the .bak"));
            check("it does not say a restart would load the saved files",
                    !failed.body.contains("restartLoadsSavedData=true")
                            && !failed.body.contains("load the saved data as usual")
                            && !failed.body.contains("starting the program again would load"));
            check("it does not say restarting would load the mixture",
                    !failed.body.contains("whichever mixture is on disk")
                            && !failed.body.contains("load whichever mixture"));
            check("it says startup will refuse to load the folder",
                    failed.body.contains("Startup will refuse to load this folder until the"
                            + " files are recovered"));
            check("it does not offer a restart as a way back to the saved data",
                    !failed.body.contains("Do not simply start again expecting the saved")
                            && failed.body.contains("Do not restart expecting the saved data"));
            check("it does not say the files still hold the last saved data",
                    !failed.body.contains("still hold the last saved data"));
            check("it does not claim the work was undone in memory",
                    !failed.body.contains("undone in memory and on disk"));
            check("it says the figures in memory are not the saved ones",
                    failed.body.contains("are not the saved ones"));
            check("it names the restore failure once, without repeating itself",
                    failed.body.indexOf("injected failure in restore()")
                            == failed.body.lastIndexOf("injected failure in restore()"));
            check("the guard is blocked", guard.isBlocked());
            check("the guard keeps the mixture flag", !guard.isDatasetConsistent());
            check("the guard keeps the unloadable flag", !guard.isRecoveredOnStartup());
            check("the guard keeps the recovery hint",
                    guard.recoveryHint().contains("Compare sales.txt"));
            check("the guard does not mistake this for the clean-disk case",
                    !guard.isMemoryUnreliable());

            // A second request must be answered with the same facts, not a rebuilt
            // approximation that has forgotten the mixture.
            Response retry = post("/api/items/I001/sale", "qty=1", "Staff");
            check("the retry is refused", retry.status == 503);
            check("the retry still reports the mixture",
                    retry.body.contains("datasetConsistent=false")
                            && retry.body.contains("restartLoadsSavedData=false")
                            && retry.body.contains("rolledBack=false"));
        } finally {
            server.stop();
            base = savedBase;
        }

        check("memory really does hold the unsaved sale",
                wiring.sales.count() == salesBefore + 1);
        check("memory really does hold the moved stock",
                Math.abs(stockOf(wiring, "I001") - (stockBefore - 2)) < 0.0001);
        check("all seven data files are still there", countDataFiles(dataDir) == 7);
    }

    /**
     * The save wrote nothing and the folder is whole; only the memory restore failed.
     * Here the files really are the copy to trust, and a restart really does load them.
     */
    private static void checkSnapshotRestoreFailedOnCleanDisk(Path parent) throws Exception {
        Path scratch = parent.resolve("snapshot-rollback-clean");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        double stockBefore = stockOf(wiring, "I001");
        int salesBefore = wiring.sales.count();
        String salesTextBefore = Files.readString(dataDir.resolve("sales.txt"));

        // A plain failure, so the save itself is sure the files were not changed and a
        // start would load them - which is what the message is allowed to repeat here.
        WriteGuard guard = new WriteGuard(() -> {
            throw new DataFileException("sales.txt", 0, "the disk is full (simulated)");
        });
        server.Routes routes = withFailingRestore(wiring, guard);

        WebServer server = WebServer.start(0, new ApiHandler(routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a failed memory restore answers 503", failed.status == 503);
            check("it is still reported as a failed save",
                    failed.body.contains("The change was not saved"));
            check("the change is not reported as committed",
                    failed.body.contains("committed=false"));
            check("the files are reported as still consistent",
                    failed.body.contains("datasetConsistent=true"));
            check("memory is reported as NOT rolled back",
                    failed.body.contains("rolledBack=false"));
            check("it does not claim the work was undone in memory",
                    !failed.body.contains("undone in memory and on disk"));
            check("the message says the files still hold the last saved data",
                    failed.body.contains("they still hold the last saved data"));
            check("the message says the figures in memory may be unreliable",
                    failed.body.contains("may now be unreliable"));
            check("the message names the restore failure",
                    failed.body.contains("injected failure in restore()"));
            check("the message says a restart would load the files",
                    failed.body.contains("starting the program again would load them")
                            && failed.body.contains("restartLoadsSavedData=true"));
            check("the message tells the user to stop rather than repeat the change",
                    failed.body.contains("Stop the server")
                            && failed.body.contains("Further changes are refused"));
            check("the guard is blocked", guard.isBlocked());
            check("the guard records memory as unreliable", guard.isMemoryUnreliable());
            check("the guard keeps the two disk facts apart",
                    guard.isDatasetConsistent() && guard.isRecoveredOnStartup());
        } finally {
            server.stop();
            base = savedBase;
        }

        // The facts on disk: nothing was written, so the saved files are untouched.
        check("sales.txt was not touched at all",
                salesTextBefore.equals(Files.readString(dataDir.resolve("sales.txt"))));
        check("all seven data files are still there", countDataFiles(dataDir) == 7);
        check("no backup was left behind", countFiles(dataDir, ".bak") == 0);
        check("no staging file was left behind", countFiles(dataDir, ".tmp") == 0);
        check("the marker was cleared, because nothing was committed",
                !Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // Memory does hold the change the guard is warning about - which is the whole
        // reason the message must not call the figures trustworthy.
        check("memory really does hold the unsaved sale",
                wiring.sales.count() == salesBefore + 1);
        check("memory really does hold the moved stock",
                Math.abs(stockOf(wiring, "I001") - (stockBefore - 2)) < 0.0001);

        // What a person is told to do is "stop and start again", so that has to work - and
        // it must load the last saved data, not the change still sitting in memory.
        Wire started = Wire.build(dataDir);
        check("a fresh start loads the last saved data",
                started.sales.count() == salesBefore);
        check("the restarted figures do not include the unsaved sale",
                Math.abs(stockOf(started, "I001") - stockBefore) < 0.0001);
    }

    /**
     * Real wiring with one change: the snapshot's {@code restore()} throws.
     *
     * <p>This is the seam the route already calls through, not a hook added for the test.
     * {@link data.Snapshot#restore()} cannot fail through the program's own stores - it
     * re-adds copies a store already held and counts what went back - so the failure is
     * injected where the route looks for it, and everything else stays real.</p>
     */
    private static server.Routes withFailingRestore(Wire wiring, WriteGuard guard) {
        return new server.Routes(wiring.read, wiring.inventory, wiring.seasons,
                wiring.settings, guard, () -> new Snapshot(wiring.items, wiring.batches,
                        wiring.sales, wiring.waste, wiring.seasons, wiring.activity,
                        wiring.settings) {
                    @Override
                    public void restore() {
                        throw new IllegalStateException("injected failure in restore()");
                    }
                }, wiring.advisor);
    }

    /**
     * Proves a backup that was only half copied is never restored from.
     *
     * <p>The dangerous shape of this bug is subtle. seasons.txt is written first, so a
     * failure later means it has already been replaced. If the copy of its old contents
     * had been cut short, putting that copy "back" would overwrite a good file with a
     * fragment - the save failing would quietly damage the very file the rollback is meant
     * to protect. On top of that the folder is given a <em>stale</em> {@code .bak} from an
     * earlier attempt, so a restore that simply looks for {@code .bak} on disk would find
     * something old and wrong instead of nothing.</p>
     *
     * <p>The check therefore asserts two things the code has to get right: the failure is
     * reported as a clean reversal (nothing needs recovering), and seasons.txt is
     * byte-for-byte what it was, with no {@code .bak} and no {@code .bak.tmp} left over.</p>
     */
    private static void checkBackupCopyFailure(Path parent) throws Exception {
        Path scratch = parent.resolve("backup-failure");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        Path seasonsFile = dataDir.resolve("seasons.txt");
        String seasonsBefore = Files.readString(seasonsFile);
        double stockBefore = stockOf(wiring, "I001");

        // What an earlier, crashed attempt might have left: a backup that is complete but
        // belongs to a save that no longer matters.
        Files.writeString(dataDir.resolve("seasons.txt.bak"), "Dry|1|2" + System.lineSeparator());

        // seasons.txt is replaced first, so by the time items.txt fails its old contents
        // have already gone - which is exactly when a restore would run.
        wiring.dataStore.injectFaults(fileName -> {
            if ("items.txt".equals(fileName)) {
                return data.DataStore.Fault.SAVE_FAILS;
            }
            if ("seasons.txt".equals(fileName)) {
                return data.DataStore.Fault.BACKUP_COPY_FAILS;
            }
            return data.DataStore.Fault.NONE;
        });

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a save that fails at the backup copy answers 503", failed.status == 503);
            check("it is reported as a clean reversal, not as damage",
                    failed.body.contains("datasetConsistent=true")
                            && failed.body.contains("rolledBack=true"));
            check("it is reported as restartable",
                    failed.body.contains("restartLoadsSavedData=true"));
            check("the message says the files were put back",
                    failed.body.contains("undone in memory and on disk"));
            check("the message says a normal start will load the saved data",
                    failed.body.contains("load the saved data as usual"));
            check("the message does not mention recovery",
                    !failed.body.contains("needs recovery"));
        } finally {
            server.stop();
            base = savedBase;
            wiring.dataStore.injectFaults(null);
        }

        check("the file whose backup copy failed is unchanged, byte for byte",
                seasonsBefore.equals(Files.readString(seasonsFile)));
        check("the stale backup did not overwrite it with old contents",
                !Files.readString(seasonsFile).contains("Dry|1|2"));
        check("the stale backup from the earlier attempt was cleared",
                !Files.exists(dataDir.resolve("seasons.txt.bak")));
        check("the half-copied backup was not left under the backup name",
                !Files.exists(dataDir.resolve("seasons.txt.bak.tmp")));
        // The marker used to be left here on purpose. It is now cleared, because nothing
        // on disk differs from before the save - and that also means the restart an older
        // build refused is now the normal way back.
        check("no staging files and no save marker were left behind",
                countFiles(dataDir, ".tmp") == 0);
        check("writes were blocked, so the failed save still stops the server",
                wiring.guard.isBlocked());

        // The claim the 503 makes, tested rather than trusted: a fresh start on this
        // folder loads the saved data.
        Wire restarted = Wire.build(dataDir);
        check("a fresh start loads the folder after a clean rollback",
                restarted.items.count() == wiring.items.count());
        check("the reloaded stock is the stock from before the failed sale",
                Math.abs(stockOf(restarted, "I001") - stockBefore) < 0.0001);
        check("the reloaded sales are the ones from before the failed sale",
                restarted.sales.count() == wiring.sales.count());
    }

    /**
     * Proves a save whose backup cannot be deleted is reported as saved, not as lost.
     *
     * <p>This is the case the old code got wrong in both directions at once. The save had
     * already replaced all seven files, so the new records were on disk; the backup delete
     * then failed quietly, the save returned as a success, and the marker was removed. The
     * user was told everything was fine - and the next start refused the folder because of
     * the leftover {@code .bak}, with no explanation of why. Worse, a caller that rolled
     * memory back (as the old route did whenever the save threw) would have put the old
     * figures on screen while the disk held the new ones.</p>
     *
     * <p>What must be true now: the response says the change was saved, memory keeps the
     * sale, all seven data files hold it, the backup that would not delete is still there,
     * the marker was <em>kept</em> as a second sign, and a fresh {@code Wire} refuses the
     * folder. Recovery is then one deletion away, and that is tested too.</p>
     */
    private static void checkBackupCleanupFailure(Path parent) throws Exception {
        Path scratch = parent.resolve("backup-cleanup-failure");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        String seasonsBefore = Files.readString(dataDir.resolve("seasons.txt"));
        double stockBefore = stockOf(wiring, "I001");
        int salesBefore = wiring.sales.count();

        // The save itself works; only the delete of seasons.txt.bak refuses.
        wiring.dataStore.injectFaults(fileName ->
                "seasons.txt".equals(fileName)
                        ? data.DataStore.Fault.BACKUP_DELETE_FAILS
                        : data.DataStore.Fault.NONE);

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a backup that cannot be deleted answers 503", failed.status == 503);
            check("the response says the change was saved",
                    failed.body.contains("The change was saved"));
            check("it does not say the change was not saved",
                    !failed.body.contains("The change was not saved"));
            check("the four facts are reported separately",
                    failed.body.contains("committed=true")
                            && failed.body.contains("rolledBack=false")
                            && failed.body.contains("datasetConsistent=true")
                            && failed.body.contains("restartLoadsSavedData=false"));
            check("it names the file that could not be deleted",
                    failed.body.contains("seasons.txt.bak"));
            check("it says a normal start would refuse the folder",
                    failed.body.contains("a normal start would refuse"));
            check("memory was kept, so the response does not claim a rollback",
                    !failed.body.contains("The figures in memory were put back"));
            check("it does not promise that a restart would load the data",
                    !failed.body.contains("load the saved data as usual"));

            // The guard's facts, read directly rather than through the message.
            check("the guard knows the data committed",
                    wiring.guard.wasDataCommitted());
            check("the guard knows memory was deliberately not rolled back",
                    !wiring.guard.wasMemoryRolledBack());
            check("the guard still treats the records as consistent",
                    wiring.guard.isDatasetConsistent());
            check("the guard knows a start would not load the folder",
                    !wiring.guard.isRecoveredOnStartup());
        } finally {
            server.stop();
            base = savedBase;
        }

        check("memory keeps the new sale", wiring.sales.count() == salesBefore + 1);
        check("memory keeps the moved stock",
                Math.abs(stockOf(wiring, "I001") - (stockBefore - 2)) < 0.0001);
        check("all seven data files are still there", countDataFiles(dataDir) == 7);
        check("the backup that would not delete is still there",
                Files.exists(dataDir.resolve("seasons.txt.bak")));
        check("the marker is kept as a second sign",
                Files.exists(dataDir.resolve("save-in-progress.tmp")));
        check("the backup holds the contents the save replaced",
                seasonsBefore.equals(Files.readString(dataDir.resolve("seasons.txt.bak"))));

        // A fresh start must refuse, and must not load a mixture - or, worse, the old data
        // while the user believes the sale was saved.
        boolean refused = false;
        String refusalFile = "";
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
            refusalFile = e.getFileName();
        }
        check("a fresh start refuses the folder while the backup is there", refused);
        check("the refusal names the leftover", refusalFile.contains("seasons.txt.bak"));

        // Recovery is the one deletion the hint asks for. Both signs go, because both are
        // what a start refuses on; then the saved records load.
        Files.deleteIfExists(dataDir.resolve("seasons.txt.bak"));
        Files.deleteIfExists(dataDir.resolve("save-in-progress.tmp"));
        Wire recovered = Wire.build(dataDir);
        check("after deleting the leftovers the saved records load",
                recovered.sales.count() == salesBefore + 1);
        check("the recovered stock is the stock the sale left",
                Math.abs(stockOf(recovered, "I001") - (stockBefore - 2)) < 0.0001);
    }

    /**
     * Proves a save that replaced every file and then could not delete its marker is
     * reported as a commit, not as a failed save.
     *
     * <p>All seven files hold the new records and the backups are already gone, so there is
     * nothing left to reverse with. The old code threw here, the route restored the old
     * snapshot, and memory then described a moment that no longer existed on disk. The
     * response also derived "a restart will load the data" from consistency, which was true
     * about the records and false about start-up: the marker makes it refuse.</p>
     *
     * <p>What must be true now: the response says the change was saved and that one file
     * has to be deleted, memory keeps the change, the seven canonical files hold it, only
     * the marker is left, and a fresh {@code Wire} refuses until it is removed.</p>
     */
    private static void checkMarkerCleanupFailure(Path parent) throws Exception {
        Path scratch = parent.resolve("marker-cleanup-failure");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        String batchesBefore = Files.readString(dataDir.resolve("batches.txt"));
        double stockBefore = stockOf(wiring, "I001");
        int salesBefore = wiring.sales.count();

        wiring.dataStore.injectFaults(fileName -> data.DataStore.Fault.MARKER_DELETE_FAILS);

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a marker that cannot be deleted answers 503", failed.status == 503);
            check("the response says the change was saved",
                    failed.body.contains("The change was saved"));
            check("it does not say the change was not saved",
                    !failed.body.contains("The change was not saved"));
            check("it is not reported as a mixture of records",
                    !failed.body.contains("mixture of old and new records"));
            check("the facts say committed but not restartable",
                    failed.body.contains("committed=true")
                            && failed.body.contains("datasetConsistent=true")
                            && failed.body.contains("restartLoadsSavedData=false"));
            check("memory is not reported as rolled back",
                    failed.body.contains("rolledBack=false"));
            check("it names the marker to delete",
                    failed.body.contains("save-in-progress.tmp"));
            check("the guard knows a start would not load the folder",
                    !wiring.guard.isRecoveredOnStartup());
        } finally {
            server.stop();
            base = savedBase;
            wiring.dataStore.injectFaults(null);
        }

        check("memory keeps the new sale", wiring.sales.count() == salesBefore + 1);
        check("memory keeps the moved stock",
                Math.abs(stockOf(wiring, "I001") - (stockBefore - 2)) < 0.0001);
        check("all seven data files are there", countDataFiles(dataDir) == 7);
        check("the data files hold the new records, not the old ones",
                !batchesBefore.equals(Files.readString(dataDir.resolve("batches.txt"))));
        check("no backup was left behind",
                countFiles(dataDir, ".bak") == 0);
        check("no half-copied backup was left behind",
                countFiles(dataDir, ".bak.tmp") == 0);
        check("only the marker is left to delete",
                countFiles(dataDir, ".tmp") == 1
                        && Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // The refusal is a start-up refusing a folder that is otherwise whole.
        boolean refused = false;
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
        }
        check("a fresh start refuses the folder while the marker is there", refused);

        Files.deleteIfExists(dataDir.resolve("save-in-progress.tmp"));
        Wire recovered = Wire.build(dataDir);
        check("after deleting the marker the saved records load",
                recovered.sales.count() == salesBefore + 1);
        check("the recovered stock is the stock the sale left",
                Math.abs(stockOf(recovered, "I001") - (stockBefore - 2)) < 0.0001);
        check("the old figures are not what loads back",
                Math.abs(stockOf(recovered, "I001") - stockBefore) > 0.0001);
    }

    /**
     * Proves a staging failure whose marker cannot be deleted is reported as "no record was
     * written, one file to delete".
     *
     * <p>This is the simplest failure there is - not one data file was opened for writing -
     * and it used to be reported as a clean abandonment. If the marker then could not be
     * removed, the folder was left unloadable while the message said nothing needed doing.
     * The report has to separate the two facts: no record was lost, and a start will still
     * refuse the folder until the marker goes.</p>
     */
    private static void checkStagingCleanupFailure(Path parent) throws Exception {
        Path scratch = parent.resolve("staging-cleanup-failure");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        String salesBefore = Files.readString(dataDir.resolve("sales.txt"));
        double stockBefore = stockOf(wiring, "I001");
        int salesCountBefore = wiring.sales.count();

        wiring.dataStore.injectFaults(fileName ->
                data.DataStore.Fault.MARKER_DELETE_ON_STAGING_FAILS);

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a staging failure with a stuck marker answers 503", failed.status == 503);
            check("the response says the change was not saved",
                    failed.body.contains("The change was not saved"));
            check("it is not reported as committed",
                    failed.body.contains("committed=false"));
            check("the records are not reported as a mixture",
                    failed.body.contains("datasetConsistent=true"));
            check("it does not promise that a restart would load the data",
                    failed.body.contains("restartLoadsSavedData=false")
                            && !failed.body.contains("load the saved data as usual"));
            check("it names the marker as the one file to delete",
                    failed.body.contains("save-in-progress.tmp"));
            check("the guard blocks further writes", wiring.guard.isBlocked());
            check("the guard knows a start would not load the folder",
                    !wiring.guard.isRecoveredOnStartup());
        } finally {
            server.stop();
            base = savedBase;
            wiring.dataStore.injectFaults(null);
        }

        check("memory was rolled back to the saved state",
                wiring.sales.count() == salesCountBefore);
        check("memory holds the stock from before the failed sale",
                Math.abs(stockOf(wiring, "I001") - stockBefore) < 0.0001);
        check("sales.txt was not touched at all",
                salesBefore.equals(Files.readString(dataDir.resolve("sales.txt"))));
        check("no staging file was left behind",
                countFilesExceptMarker(dataDir, ".tmp") == 0);
        check("the only leftover is the marker",
                Files.exists(dataDir.resolve("save-in-progress.tmp")));
        check("no backup was made, because no file was replaced",
                countFiles(dataDir, ".bak") == 0);

        boolean refused = false;
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
        }
        check("a fresh start refuses the folder while the marker is there", refused);

        Files.deleteIfExists(dataDir.resolve("save-in-progress.tmp"));
        Wire recovered = Wire.build(dataDir);
        check("after deleting the marker the untouched records load",
                recovered.sales.count() == salesCountBefore);
        check("the reloaded stock is the stock from before the failed sale",
                Math.abs(stockOf(recovered, "I001") - stockBefore) < 0.0001);
    }

    /**
     * Proves a directory that was put where a data file belongs is left alone.
     *
     * <p>A directory there was not created by this program, and it may hold a person's
     * work. Deleting it to make room would be the program destroying something it never
     * made, so the save reports the obstacle and restores the files it had already
     * replaced. The marker file inside is what makes the assertion meaningful: "the
     * directory still exists" would pass even if its contents had been emptied first.</p>
     *
     * <p>What the caller is left with is <em>not</em> a clean rollback, and this check was
     * wrong twice before it was right. The replaced files are all put back - so an earlier
     * version expected a clean reversal - but the folder is still one file short and no
     * backup exists for that file, because the old contents were a directory, not a file.
     * A load would fail on the missing file. The honest answer is therefore
     * {@code datasetConsistent=false} with the marker kept, which is what this check now
     * asserts, together with the fact that nothing was deleted.</p>
     */
    private static void checkUnexpectedDirectory(Path parent) throws Exception {
        Path scratch = parent.resolve("directory-obstacle");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        String salesBefore = Files.readString(dataDir.resolve("sales.txt"));
        String seasonsBefore = Files.readString(dataDir.resolve("seasons.txt"));
        double stockBefore = stockOf(wiring, "I001");

        // waste.txt is written fifth of seven, so the four before it - seasons, items,
        // batches and sales - are already replaced when the obstacle is reached.
        Path obstacle = dataDir.resolve("waste.txt");
        Files.delete(obstacle);
        Files.createDirectory(obstacle);
        Path marker = obstacle.resolve("notes.txt");
        Files.writeString(marker, "do not delete" + System.lineSeparator());

        WebServer server = WebServer.start(0, new ApiHandler(wiring.routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response failed = post("/api/items/I001/sale", "qty=2", "Staff");
            check("a directory in a data file's place answers 503", failed.status == 503);
            check("the message names the directory that is in the way",
                    failed.body.contains("a directory is in the way of waste.txt"));
            check("the message says the directory was left untouched",
                    failed.body.contains("a directory is in the way of waste.txt,"
                            + " and it was left untouched"));
            check("the replaced files were put back in memory and on disk",
                    failed.body.contains("rolledBack=true"));
            // The folder is one file short with no backup for it, so this must not be
            // reported as a dataset that can simply be loaded again.
            check("it is not reported as a loadable dataset",
                    failed.body.contains("datasetConsistent=false")
                            && failed.body.contains("restartLoadsSavedData=false"));
            check("the message says recovery is needed",
                    failed.body.contains("needs recovery"));
            check("the message does not promise that a restart would load the data",
                    !failed.body.contains("load the saved data as usual"));
        } finally {
            server.stop();
            base = savedBase;
        }

        check("the unexpected directory is still there", Files.isDirectory(obstacle));
        check("the file inside the unexpected directory survived",
                Files.exists(marker)
                        && Files.readString(marker).contains("do not delete"));
        check("the directory was not emptied and recreated",
                Files.list(obstacle).count() == 1);
        check("sales.txt was put back byte for byte",
                salesBefore.equals(Files.readString(dataDir.resolve("sales.txt"))));
        check("seasons.txt was put back byte for byte",
                seasonsBefore.equals(Files.readString(dataDir.resolve("seasons.txt"))));
        check("memory was rolled back as usual",
                Math.abs(stockOf(wiring, "I001") - stockBefore) < 0.0001);
        // The marker stays because this folder genuinely needs a person: one data file is
        // a directory, so the next start must refuse to load it as a whole.
        check("the marker is kept, so the next start refuses this folder",
                Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // And startup really does refuse.
        boolean refused = false;
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refused = true;
        }
        check("a fresh start refuses the folder while the directory is still there", refused);

        // Recovery by hand, then a fresh start: a person removes the obstacle and follows
        // the hint - compare, clear the leftovers, run once more, which rewrites the
        // missing file from the last saved state.
        deleteTree(obstacle);
        Files.deleteIfExists(dataDir.resolve("save-in-progress.tmp"));
        for (int i = 0; i < data.DataFiles.ALL.length; i++) {
            Files.deleteIfExists(dataDir.resolve(data.DataFiles.ALL[i]
                    + data.DataStore.BACKUP_SUFFIX));
        }
        wiring.dataStore.saveAll(wiring.items, wiring.batches, wiring.sales, wiring.waste,
                wiring.seasons, wiring.activity);
        check("a clean save after clearing the obstacle restores all seven files",
                countDataFiles(dataDir) == 7);
        check("the clean save cleared the marker", !Files.exists(
                dataDir.resolve("save-in-progress.tmp")));
        Wire reloaded = Wire.build(dataDir);
        check("the repaired folder loads again",
                Math.abs(stockOf(reloaded, "I001") - stockBefore) < 0.0001);
    }

    /**
     * Proves a fresh start refuses a folder whose last save never finished.
     *
     * <p>The swap is interrupted for real rather than simulated: the injected fault fails
     * one file after several have been replaced <em>and</em> fails to put them back, which
     * is the only way to land in the state a crash leaves - backups on disk, data files
     * mixed, no clean way to tell which is which.</p>
     *
     * <p>The assertion is about a new {@link Wire}, not the running one: a fresh start can
     * only know what the files say, so it is the real judge of whether the folder would be
     * loaded. It must refuse, say why, and leave every file alone.</p>
     *
     * <p>This is also the counterpart of the clean-rollback checks: a save that <em>can</em>
     * be reversed clears the marker and loads again, and a save that cannot be reversed
     * keeps it and is refused. The two together are what make the 503's advice true in both
     * directions.</p>
     */
    private static void checkInterruptedSaveRefused(Path parent) throws Exception {
        Path scratch = parent.resolve("interrupted-save");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path dataDir = scratch.resolve("data");
        String batchesBefore = Files.readString(dataDir.resolve("batches.txt"));

        wiring.dataStore.injectFaults(fileName -> {
            if ("waste.txt".equals(fileName)) {
                return data.DataStore.Fault.SAVE_FAILS;
            }
            if ("batches.txt".equals(fileName)) {
                return data.DataStore.Fault.SAVE_AND_RESTORE_FAIL;
            }
            return data.DataStore.Fault.NONE;
        });
        boolean interrupted = false;
        try {
            wiring.dataStore.saveAll(wiring.items, wiring.batches, wiring.sales, wiring.waste,
                    wiring.seasons, wiring.activity);
        } catch (DataFileException expected) {
            interrupted = true;
        } finally {
            wiring.dataStore.injectFaults(null);
        }
        check("the interrupted save really did fail", interrupted);
        check("the interrupted save left a backup behind",
                Files.exists(dataDir.resolve("batches.txt.bak")));
        check("the interrupted save left its marker behind",
                Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // Capture what the folder looks like now, to prove the refusal changed nothing.
        String batchesNow = Files.readString(dataDir.resolve("batches.txt"));
        String salesNow = Files.readString(dataDir.resolve("sales.txt"));
        String activityNow = Files.readString(dataDir.resolve("activity.txt"));

        section("A folder left by an unfinished save is refused, not loaded");
        DataFileException refusal = null;
        try {
            Wire.build(dataDir);
        } catch (DataFileException e) {
            refusal = e;
        } catch (Exception e) {
            check("a fresh start refuses with a DataFileException, not " + e.getClass()
                    .getSimpleName(), false);
        }
        check("a fresh start refuses to load the folder", refusal != null);
        if (refusal != null) {
            check("the refusal names what the unfinished save left behind",
                    refusal.getFileName().contains("save-in-progress.tmp"));
            check("the refusal says the files may be a mixture",
                    refusal.getMessage().contains("mixture"));
            check("the refusal says the data was not loaded",
                    refusal.getMessage().contains("not loaded"));
            check("the refusal is marked as an inconsistent dataset",
                    !refusal.isDatasetConsistent());
            // With several leftovers the hint names the pattern rather than every file, so
            // a long list cannot push the instruction off a terminal line. What matters is
            // that it says explicitly which files hold the earlier contents.
            check("the refusal explains which files hold the earlier contents",
                    refusal.getRecoveryHint().contains("hold the contents their data files"
                            + " had before that save") && refusal.getRecoveryHint()
                            .contains(".bak"));
            // The old wording promised nothing had been changed. A save that got this far
            // did replace some files, so only startup's own restraint can be claimed.
            check("the refusal says startup did not modify the files",
                    refusal.getRecoveryHint().contains("Startup did not modify the files"));
            check("the refusal no longer claims that nothing was changed",
                    !refusal.getRecoveryHint().contains("Nothing was changed"));
            check("the refusal says the marker must be deleted before a restart",
                    refusal.getRecoveryHint().contains("delete save-in-progress.tmp"));
        }

        check("the refusal did not touch batches.txt", batchesNow.equals(
                Files.readString(dataDir.resolve("batches.txt"))));
        check("the refusal did not touch sales.txt", salesNow.equals(
                Files.readString(dataDir.resolve("sales.txt"))));
        check("the refusal did not touch activity.txt", activityNow.equals(
                Files.readString(dataDir.resolve("activity.txt"))));
        check("the refusal kept the backup file for the person to use",
                Files.exists(dataDir.resolve("batches.txt.bak")));
        check("the refusal kept the marker, so the next start refuses too",
                Files.exists(dataDir.resolve("save-in-progress.tmp")));

        // Following the printed instructions - compare, then clear the leftovers - makes
        // the folder loadable again. This proves the advice is not a dead end.
        Files.deleteIfExists(dataDir.resolve("save-in-progress.tmp"));
        for (int i = 0; i < data.DataFiles.ALL.length; i++) {
            Files.deleteIfExists(dataDir.resolve(data.DataFiles.ALL[i]
                    + data.DataStore.BACKUP_SUFFIX));
        }
        Wire afterCleanup = Wire.build(dataDir);
        check("clearing the leftovers lets the folder load again",
                afterCleanup.items.count() == wiring.items.count());
        check("the reloaded batches are the ones from before the failed save",
                batchesBefore.equals(Files.readString(dataDir.resolve("batches.txt"))));
    }

    /**
     * Proves a failed season save restores the season <em>and</em> the items that point
     * at it.
     *
     * <p>Seasons are shared objects: an item does not own its season, it holds a reference
     * to the one in the store. Rolling back only the store's copy would leave the items
     * reading the changed months, so this check looks at both - the month range, and the
     * seasonal multiplier the dashboard derives from the active season.</p>
     *
     * <p>I005 is seasonal (Dry, x1.35). Dry normally runs May to September; the change
     * moves it to January to June, which no longer covers the demo date of 26 November,
     * so the multiplier the dashboard shows would drop to 1.0 if the change survived.</p>
     */
    private static void checkFailedSeasonSave(Path parent) throws Exception {
        Path scratch = parent.resolve("season-save");
        deleteTree(scratch);

        Wire wiring = Wire.build(scratch.resolve("data"));
        Path seasonsFile = scratch.resolve("data").resolve("seasons.txt");

        double multiplierBefore = seasonalMultiplierOf(wiring);
        check("I005 starts on its Dry multiplier of 1.35",
                Math.abs(multiplierBefore - 1.35) < 0.0001);

        final boolean[] failNextSave = {false};
        final String[] seasonsBefore = {""};
        WriteGuard guard = new WriteGuard(() -> {
            if (failNextSave[0]) {
                throw new DataFileException("seasons.txt", 0, "the disk is full (simulated)");
            }
            return wiring.dataStore.saveAll(wiring.items, wiring.batches, wiring.sales,
                    wiring.waste, wiring.seasons, wiring.activity);
        });
        server.Routes routes = new server.Routes(wiring.read, wiring.inventory, wiring.seasons,
                wiring.settings, guard,
                () -> new Snapshot(wiring.items, wiring.batches, wiring.sales, wiring.waste,
                        wiring.seasons, wiring.activity, wiring.settings), wiring.advisor);

        WebServer server = WebServer.start(0, new ApiHandler(routes));
        String savedBase = base;
        base = server.baseUrl();
        try {
            Response refused = put("/api/seasons/Dry", "startMonth=1&endMonth=6", "Manager");
            check("the season change succeeds while the disk is fine",
                    refused.status == 200);

            // Put it back so the failing attempt below starts from the saved range.
            // Dry is saved as May to September, so the range has to be moved somewhere
            // genuinely different before the failing attempt - otherwise the rollback
            // would be restoring a value that was never changed and nothing would be
            // proven.
            Response moved = put("/api/seasons/Dry", "startMonth=5&endMonth=8", "Manager");
            check("the range can be moved for the test",
                    moved.status == 200 && moved.body.contains("\"monthRange\":\"May - Aug\"")
                            && wiring.seasons.findByName("Dry").getEndMonth() == 8);

            // Captured here, after the successful moves, so the comparison below is
            // against what the failed save must leave alone rather than against the
            // values from before the test began.
            seasonsBefore[0] = Files.readString(seasonsFile);

            failNextSave[0] = true;
            Response failed = put("/api/seasons/Dry", "startMonth=1&endMonth=6", "Manager");
            check("a failed season save answers 503", failed.status == 503);
            check("the season failure is a clean reversal",
                    failed.body.contains("datasetConsistent=true"));
        } finally {
            server.stop();
            base = savedBase;
        }

        // The restored month range must be the saved one, read from the store and from the
        // API, so a rollback that only patched one of them would be caught.
        model.Season restored = wiring.seasons.findByName("Dry");
        // The saved range at this point is the one the successful moves left, May to
        // August: the failed attempt asked for January to June and must have been undone.
        check("the stored season is back to May - August",
                restored.getStartMonth() == 5 && restored.getEndMonth() == 8);
        check("the restored range reads as words",
                "May - Aug".equals(restored.monthRangeText()));
        check("the restored season is not the one that was being changed",
                !"Jan - Jun".equals(restored.monthRangeText()));

        // Items must point at the restored season object, not a stale copy of the changed
        // one. These two assertions are the whole point of the deep copy.
        check("the seasonal item points at the season in the store",
                itemOf(wiring, "I005").getSeason() == restored);
        check("the seasonal multiplier is back to 1.35",
                Math.abs(seasonalMultiplierOf(wiring) - 1.35) < 0.0001);

        check("seasons.txt on disk came back byte for byte",
                seasonsBefore[0].equals(Files.readString(seasonsFile)));
        check("the file still holds May to August",
                Files.readString(seasonsFile).contains("Dry|5|8"));
        Wire reloaded = Wire.build(scratch.resolve("data"));
        check("a reloaded server reads the old Dry range",
                reloaded.seasons.findByName("Dry").getStartMonth() == 5);
    }

    /** Quantity on hand for an item, straight from the store, not via the API. */
    private static double stockOf(Wire wiring, String itemId) {
        double total = 0;
        for (int i = 0; i < wiring.batches.count(); i++) {
            model.Batch batch = wiring.batches.batches()[i];
            if (batch != null && batch.getItemId().equalsIgnoreCase(itemId)) {
                total += batch.getQtyRemaining();
            }
        }
        return total;
    }

    private static int countBatchesOf(Wire wiring, String itemId) {
        int count = 0;
        for (int i = 0; i < wiring.batches.count(); i++) {
            model.Batch batch = wiring.batches.batches()[i];
            if (batch != null && batch.getItemId().equalsIgnoreCase(itemId)) {
                count++;
            }
        }
        return count;
    }

    /** The stored item itself, so a check can compare the object and not just the JSON. */
    private static model.StockItem itemOf(Wire wiring, String itemId) {
        model.StockItem found = wiring.items.findById(itemId);
        if (found == null) {
            throw new IllegalStateException("No item " + itemId + " in the check dataset.");
        }
        return found;
    }

    /**
     * The multiplier the dashboard shows for the seasonal item I005, taken from the JSON
     * the page would receive. That makes the assertion about behaviour rather than about
     * an internal field.
     */
    private static double seasonalMultiplierOf(Wire wiring) throws Exception {
        String text = get("/api/items/I005").body;
        String key = "\"seasonalMultiplier\":";
        int at = text.indexOf(key);
        if (at < 0) {
            throw new IllegalStateException("the item payload has no seasonalMultiplier");
        }
        int start = at + key.length();
        int end = start;
        while (end < text.length()
                && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
            end++;
        }
        return Double.parseDouble(text.substring(start, end));
    }

    /**
     * Counts files in the data folder ending with the given suffix.
     *
     * <p>The save marker is also a {@code .tmp} file. Earlier versions of these checks
     * counted around it because a failed save deliberately left it behind; now the marker
     * is cleared by every save outcome that leaves the folder loadable, so a leftover is
     * just leftover and is counted like any other file.</p>
     */
    private static int countFiles(Path dataDir, String suffix) throws IOException {
        final int[] total = {0};
        Files.list(dataDir).forEach(path -> {
            if (path.getFileName().toString().endsWith(suffix)) {
                total[0]++;
            }
        });
        return total[0];
    }

    private static int countDataFiles(Path dataDir) throws IOException {
        final int[] total = {0};
        Files.list(dataDir).forEach(path -> {
            if (path.getFileName().toString().endsWith(".txt")) {
                total[0]++;
            }
        });
        return total[0];
    }

    /**
     * Counts staging files, ignoring the save marker.
     *
     * <p>Kept for the case where the marker is expected to be there on purpose; see
     * {@link #checkInterruptedSaveRefused}. Anywhere else a leftover marker is a fault and
     * plain {@link #countFiles} is the right check.</p>
     */
    private static int countFilesExceptMarker(Path dataDir, String suffix) throws IOException {
        final int[] total = {0};
        final String marker = data.DataStore.SAVE_IN_PROGRESS;
        Files.list(dataDir).forEach(path -> {
            String name = path.getFileName().toString();
            if (name.endsWith(suffix) && !name.equals(marker)) {
                total[0]++;
            }
        });
        return total[0];
    }

    // -------------------------------------------------------------- advisor

    /**
     * Proves the briefing endpoint's contract at the HTTP boundary.
     *
     * <p>Two halves. The first drives the <em>real</em> server, which has no key in a check
     * environment, and pins the "no key" answer: still 200, still useful, and labelled
     * {@code RULES} so nobody can mistake the local text for AI output.</p>
     *
     * <p>The second half builds real {@code Routes} on a second server with an
     * {@code AdvisorService} wired to a fake {@code LanguageModel}. That seam is why
     * {@code LanguageModel} exists: every failure path - rejected key, unreachable service,
     * unreadable reply, blocked prompt, truncated answer, empty text - can be proven
     * without a network call and without a real key, which is the only way these paths
     * could be checked reproducibly at all.</p>
     */
    private static void checkAdvisorOverHttp() throws Exception {
        // ---- the real server, which has no key ----
        Response noKey = get("/api/advisor/briefing");
        check("GET /api/advisor/briefing answers 200 even with no key", noKey.status == 200);
        check("the briefing envelope uses the agreed ok/data shape",
                noKey.body.startsWith("{\"ok\":true,\"data\":{"));
        check("with no key the answer is labelled as rule-based",
                noKey.body.contains("\"source\":\"RULES\""));
        check("with no key there is no briefing text", noKey.body.contains("\"briefing\":null"));
        check("with no key the reason is NoKey",
                noKey.body.contains("\"reason\":\"NoKey\""));
        check("the no-key answer says no AI was requested",
                noKey.body.contains("no briefing was requested"));
        check("the no-key answer still carries a rule summary",
                noKey.body.contains("\"ruleSummary\":{\"source\":\"RULES\""));
        check("the rule summary has text, not an empty string",
                !noKey.body.contains("\"text\":\"\""));
        // The remediation names the gemini.apiKey SETTING, which is guidance, not a secret.
        // What must never appear is a key VALUE, so the check looks for that specifically.
        check("the no-key answer mentions the setting but carries no key value",
                noKey.body.contains("gemini.apiKey")
                        && !noKey.body.contains("gemini.apiKey=")
                        && !noKey.body.contains("AIza"));
        check("the answer reports how many items were considered",
                noKey.body.contains("\"itemsConsidered\":"));

        // ---- a second server whose model is faked ----
        Wire wiring = Wire.build(Path.of("out", "api-check").resolve("data-advisor"));
        try {
            checkSuccessShape(wiring);
            checkNoKeyServiceShape(wiring);
            checkKeyRejected(wiring);
            checkRateLimited(wiring);
            checkUnreachable(wiring);
            checkMalformedReply(wiring);
            checkBlockedPrompt(wiring);
            checkWrongFinishReason(wiring);
            checkEmptyText(wiring);
            checkReplyIsCapped(wiring);
            checkModelUnavailable(wiring);
            checkKeyPrecedence();
            checkExamplePlaceholder();
        } finally {
            deleteTree(Path.of("out", "api-check").resolve("data-advisor"));
        }
    }

    /**
     * A model that returns a fixed reply, or throws a fixed failure.
     *
     * <p>It records the request body so the checks can prove the fact sheet really is sent
     * and that the key never appears in it.</p>
     */
    private static final class FakeModel implements ai.LanguageModel {
        private final String reply;
        private final IOException failure;
        private java.io.ByteArrayOutputStream lastRequest;

        FakeModel(String reply, IOException failure) {
            this.reply = reply;
            this.failure = failure;
        }

        @Override
        public String generate(String requestJson) throws IOException {
            lastRequest = new java.io.ByteArrayOutputStream();
            lastRequest.writeBytes(requestJson.getBytes(StandardCharsets.UTF_8));
            if (failure != null) {
                throw failure;
            }
            return reply;
        }

        String requestText() {
            return lastRequest == null ? "" : lastRequest.toString(StandardCharsets.UTF_8);
        }
    }

    /** A Gemini success envelope with the given text. */
    private static String geminiReply(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + jsonString(text)
                + "}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":42}}";
    }

    /** Small local escaper so the fixtures below can contain quotes and newlines safely. */
    private static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Runs one briefing request against a throwaway server and always stops it. */
    private static Response briefingFrom(Wire wiring, ai.LanguageModel model) throws Exception {
        String previous = base;
        WebServer server = null;
        try {
            ai.AdvisorConfig config = ai.AdvisorConfig.load(null, "fake-key-for-checks");
            ai.AdvisorService advisor = new ai.AdvisorService(wiring.read, config, model);
            server.Routes routes = new server.Routes(wiring.read, wiring.inventory,
                    wiring.seasons, wiring.settings, wiring.guard,
                    () -> new Snapshot(wiring.items, wiring.batches, wiring.sales,
                            wiring.waste, wiring.seasons, wiring.activity, wiring.settings),
                    advisor);
            server = WebServer.start(0, new ApiHandler(routes));
            base = server.baseUrl();
            return get("/api/advisor/briefing");
        } finally {
            base = previous;
            if (server != null) {
                server.stop();
            }
        }
    }

    /** The happy path: the model's words are returned and labelled AI. */
    private static void checkSuccessShape(Wire wiring) throws Exception {
        String said = "Fresh Milk 1L will run out in 2 days; reorder 12 L today.";
        FakeModel model = new FakeModel(geminiReply(said), null);
        Response response = briefingFrom(wiring, model);

        check("a successful briefing answers 200", response.status == 200);
        check("a successful briefing is labelled AI",
                response.body.contains("\"source\":\"AI\""));
        check("a successful briefing reports the AI as available",
                response.body.contains("\"aiAvailable\":true"));
        check("the model's text is returned verbatim",
                response.body.contains(jsonString(said)));
        check("a successful briefing carries no failure block",
                response.body.contains("\"unavailable\":null"));
        check("the model name is reported",
                response.body.contains("\"model\":\"" + ai.AdvisorConfig.DEFAULT_MODEL + "\""));
        // The default must be a model Google still serves. gemini-2.0-flash was shut
        // down on 1 June 2026, so naming it would make every live call fail.
        check("the default model is the current stable Flash model, not the retired one",
                "gemini-3.8-flash".equals(ai.AdvisorConfig.DEFAULT_MODEL)
                        && !"gemini-2.0-flash".equals(ai.AdvisorConfig.DEFAULT_MODEL));
        check("the rule summary is still offered alongside the AI text",
                response.body.contains("\"ruleSummary\":{\"source\":\"RULES\""));
        check("the fact sheet was actually sent to the model",
                model.requestText().contains("Fresh Milk 1L"));
        check("the request asks for a system instruction",
                model.requestText().contains("systemInstruction"));
        check("the request body never contains the key",
                !model.requestText().contains("fake-key-for-checks"));
    }

    /** A service with a config but no model is not configured, and says so. */
    private static void checkNoKeyServiceShape(Wire wiring) throws Exception {
        ai.AdvisorConfig noKey = ai.AdvisorConfig.load(null, null);
        ai.AdvisorService advisor = new ai.AdvisorService(wiring.read, noKey, null);
        check("a config with no key is not configured", !advisor.isConfigured());
        check("a config with no key still produces a rule summary",
                !advisor.ruleSummary().isBlank());
        boolean refused = false;
        try {
            advisor.briefing();
        } catch (exception.AdvisorUnavailableException e) {
            refused = exception.AdvisorUnavailableException.NO_KEY.equals(e.getReason());
        }
        check("asking an unconfigured advisor for a briefing names NoKey", refused);
        check("a fabricated key path never marks RULES text as AI",
                !advisor.ruleSummary().contains("AI "));
    }

    /**
     * 401/403 or a structured API_KEY_INVALID must surface as KeyRejected.
     *
     * <p>These fixtures use {@link ai.ServiceHttpException} rather than a bare message,
     * because that is what {@code GeminiClient} actually throws. A plain IOException whose
     * text mentions the API key is not a real code path, and deciding from that text was
     * the flaw corrected in this round.</p>
     */
    private static void checkKeyRejected(Wire wiring) throws Exception {
        checkFailureReason(wiring, "a rejected key",
                serviceError(400, "INVALID_ARGUMENT", "API_KEY_INVALID",
                        "API key not valid. Please pass a valid API key."),
                exception.AdvisorUnavailableException.KEY_REJECTED);
        checkFailureReason(wiring, "a forbidden key",
                serviceError(403, "PERMISSION_DENIED", "PERMISSION_DENIED",
                        "Permission denied on the service."),
                exception.AdvisorUnavailableException.KEY_REJECTED);
        checkFailureReason(wiring, "an unauthorised key",
                serviceError(401, "UNAUTHENTICATED", "CREDENTIALS_MISSING",
                        "Request had invalid authentication credentials."),
                exception.AdvisorUnavailableException.KEY_REJECTED);
    }

    /**
     * Synthetic fixtures. The status codes (429, 500) are real HTTP codes, but the wording
     * in the messages is invented rather than quoted from Google. What is being tested is
     * this program's classification of a status code, not any real response body.
     */
    private static void checkRateLimited(Wire wiring) throws Exception {
        checkFailureReason(wiring, "a rate-limited call",
                serviceError(429, "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED",
                        "Resource has been exhausted (e.g. check quota)."),
                exception.AdvisorUnavailableException.UNREACHABLE);
        checkFailureReason(wiring, "a server error",
                serviceError(500, "INTERNAL", null, "Internal error encountered."),
                exception.AdvisorUnavailableException.UNREACHABLE);
    }

    /**
     * Synthetic: a transport failure with no HTTP response at all, which is what a dead
     * network or a wrong host looks like to the HTTP client.
     */
    private static void checkUnreachable(Wire wiring) throws Exception {
        checkFailureReason(wiring, "an unreachable service",
                new IOException("connect timed out"),
                exception.AdvisorUnavailableException.UNREACHABLE);
    }

    /**
     * Proves one failure answer: status, label, reason, and that the local text is not
     * passed off as the model's.
     */
    private static void checkFailureReason(Wire wiring, String what, IOException failure,
                                           String expectedReason) throws Exception {
        Response response = briefingFrom(wiring, new FakeModel(null, failure));
        check(what + " still answers 200, because the request was understood",
                response.status == 200);
        check(what + " is labelled RULES, not AI",
                response.body.contains("\"source\":\"RULES\""));
        check(what + " reports the AI as unavailable",
                response.body.contains("\"aiAvailable\":false"));
        check(what + " sends no briefing text", response.body.contains("\"briefing\":null"));
        check(what + " gives the reason " + expectedReason,
                response.body.contains("\"reason\":\"" + expectedReason + "\""));
        check(what + " still offers the rule summary",
                response.body.contains("\"ruleSummary\":{\"source\":\"RULES\""));
        check(what + " suggests what to do next",
                response.body.contains("\"remediation\":\"") 
                        && !response.body.contains("\"remediation\":\"\""));
    }

    /** A reply that is not JSON at all must be reported as a bad response, not a crash. */
    private static void checkMalformedReply(Wire wiring) throws Exception {
        Response response = briefingFrom(wiring, new FakeModel("<html>gateway</html>", null));
        check("a non-JSON reply answers 200", response.status == 200);
        check("a non-JSON reply is BadResponse",
                response.body.contains("\"reason\":\"BadResponse\""));
        check("a non-JSON reply leaks no stack trace",
                !response.body.contains("at ai.MiniJson") && !response.body.contains("Exception:"));
        check("a non-JSON reply is still labelled RULES",
                response.body.contains("\"source\":\"RULES\""));
    }

    /** A blocked prompt is a refusal, and the wording must not blame the key. */
    private static void checkBlockedPrompt(Wire wiring) throws Exception {
        String body = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"candidates\":[]}";
        Response response = briefingFrom(wiring, new FakeModel(body, null));
        check("a blocked prompt answers 200", response.status == 200);
        check("a blocked prompt is Refused",
                response.body.contains("\"reason\":\"Refused\""));
        check("a blocked prompt says the service declined",
                response.body.contains("declined to answer"));
        check("a blocked prompt names the block reason",
                response.body.contains("SAFETY"));
        check("a blocked prompt does not blame the key",
                !response.body.contains("key was rejected"));
    }

    /** MAX_TOKENS and friends mean the answer is incomplete, so it is not shown. */
    private static void checkWrongFinishReason(Wire wiring) throws Exception {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Fresh\"}]},"
                + "\"finishReason\":\"MAX_TOKENS\"}]}";
        Response response = briefingFrom(wiring, new FakeModel(body, null));
        check("a truncated answer is not passed off as complete",
                response.body.contains("\"briefing\":null"));
        check("a truncated answer is Refused",
                response.body.contains("\"reason\":\"Refused\""));
        check("a truncated answer explains it stopped early",
                response.body.contains("stopped writing before it finished"));
        check("a truncated answer names MAX_TOKENS",
                response.body.contains("MAX_TOKENS"));
    }

    /** STOP with no text is a bad response, not success with a blank briefing. */
    private static void checkEmptyText(Wire wiring) throws Exception {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"   \"}]},"
                + "\"finishReason\":\"STOP\"}]}";
        Response response = briefingFrom(wiring, new FakeModel(body, null));
        check("an empty briefing is not accepted as success",
                response.body.contains("\"aiAvailable\":false"));
        check("an empty briefing is BadResponse",
                response.body.contains("\"reason\":\"BadResponse\""));
        check("an empty briefing says it was empty",
                response.body.contains("empty briefing"));

        Response noCandidates = briefingFrom(wiring, new FakeModel("{\"candidates\":[]}", null));
        check("no candidates at all is Refused",
                noCandidates.body.contains("\"reason\":\"Refused\""));
        check("no candidates at all is not counted as AI output",
                noCandidates.body.contains("\"source\":\"RULES\""));
    }

    /** A very long reply is cut, so one answer cannot flood the page or the log. */
    private static void checkReplyIsCapped(Wire wiring) throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longText.append("Fresh Milk 1L is running low. ");
        }
        Response response = briefingFrom(wiring,
                new FakeModel(geminiReply(longText.toString()), null));
        check("a very long briefing still answers 200", response.status == 200);
        check("a very long briefing is capped, not returned whole",
                response.body.length() < longText.length() + 2000);
        check("a capped briefing is still labelled AI",
                response.body.contains("\"source\":\"AI\""));
    }

    /** The file must win over the environment, and a blank entry must count as absent. */
    private static void checkKeyPrecedence() throws Exception {
        Path file = Path.of("out", "api-check", "ai-precedence.properties");
        String otherModel = "gemini-3.6-flash";
        Files.writeString(file, "# comment\n\ngemini.apiKey=from-file\n"
                + "gemini.model=" + otherModel + "\n");

        ai.AdvisorConfig both = ai.AdvisorConfig.load(file, "from-env");
        check("ai.properties wins over the environment variable",
                both.hasKey() && "ai.properties".equals(both.source()));
        check("the model can be overridden in ai.properties",
                otherModel.equals(both.model()));

        ai.AdvisorConfig envOnly = ai.AdvisorConfig.load(file, null);
        check("a file without a competing env value is still used", envOnly.hasKey());
        check("describe() names the file but never the key",
                both.describe().contains("ai.properties")
                        && !both.describe().contains("from-file"));

        Files.writeString(file, "gemini.apiKey=\n");
        ai.AdvisorConfig blank = ai.AdvisorConfig.load(file, "from-env");
        check("a blank key in the file falls through to the environment",
                blank.hasKey() && blank.source().contains("GEMINI_API_KEY"));

        ai.AdvisorConfig neither = ai.AdvisorConfig.load(file, null);
        check("no key anywhere is not configured", !neither.hasKey());
        check("the default model is used when none is set",
                ai.AdvisorConfig.DEFAULT_MODEL.equals(neither.model()));
        check("describe() says plainly that no key is configured",
                neither.describe().contains("no key configured"));
        check("a client is built only when a key exists",
                neither.newClient() == null && both.newClient() != null);

        ai.AdvisorConfig missing = ai.AdvisorConfig.load(
                Path.of("out", "api-check", "no-such-file.properties"), null);
        check("a missing ai.properties is not an error", !missing.hasKey());

        Files.deleteIfExists(file);
    }

    /**
     * The shipped placeholder must behave like an unset value.
     *
     * <p>This is the case a new user hits: copy the example, do not edit the key line,
     * export {@code GEMINI_API_KEY}. If the placeholder counted as a key it would be sent
     * to Google, come back as "API key not valid", and the program would tell them to
     * check a key they had already set correctly.</p>
     */
    private static void checkExamplePlaceholder() throws Exception {
        Path file = Path.of("out", "api-check", "ai-placeholder.properties");
        Files.writeString(file, "gemini.apiKey=" + ai.AdvisorConfig.EXAMPLE_PLACEHOLDER + "\n");

        ai.AdvisorConfig withEnv = ai.AdvisorConfig.load(file, "real-env-key");
        check("the shipped placeholder is treated as unset, so the environment key wins",
                withEnv.hasKey() && withEnv.source().contains("GEMINI_API_KEY"));

        ai.AdvisorConfig noEnv = ai.AdvisorConfig.load(file, null);
        check("the placeholder alone counts as no key at all", !noEnv.hasKey());
        check("with only a placeholder no client is built, so nothing is sent to Google",
                noEnv.newClient() == null);
        check("the placeholder alone is reported as no key, not as a bad key",
                noEnv.describe().contains("no key configured"));

        Files.writeString(file, "gemini.apiKey=   " + ai.AdvisorConfig.EXAMPLE_PLACEHOLDER
                + "   \n");
        check("a padded placeholder is still treated as unset",
                !ai.AdvisorConfig.load(file, null).hasKey());

        // Probe the public surface rather than widening the API for a test: each value is
        // written to a file and loaded back, which is exactly how the program sees it.
        Path probe = Path.of("out", "api-check", "ai-probe.properties");
        Files.writeString(probe, "gemini.apiKey=AIzaSyExampleNotARealKey\n");
        check("a real-looking key is accepted as a key",
                ai.AdvisorConfig.load(probe, null).hasKey());

        Files.writeString(probe, "gemini.apiKey=   \n");
        check("a blank key is not usable", !ai.AdvisorConfig.load(probe, null).hasKey());

        Files.writeString(probe, "gemini.model=gemini-3.8-flash\n");
        check("a file with no key line at all is not configured",
                !ai.AdvisorConfig.load(probe, null).hasKey());
        Files.deleteIfExists(probe);

        Files.deleteIfExists(file);
    }

    /**
     * A model that is gone must be reported as such, and a bare 400 must not be guessed at.
     *
     * <p><strong>All fixtures below are synthetic.</strong> No valid Gemini key was
     * available while writing them, so none of this wording was observed from the live
     * {@code generateContent} endpoint, and the checks do not reproduce any real response.
     * They exercise this program's own classification rules.</p>
     *
     * <p>The envelope fields used ({@code error.code}, {@code error.message},
     * {@code error.status}, {@code error.details[].reason}) come from the API discovery
     * document, which was fetched and read. The status-string and reason values are this
     * program's chosen inputs - in particular {@code MODEL_NOT_FOUND} is <strong>not</strong>
     * documented for {@code generateContent}: the Google error reference consulted listed a
     * model-not-found code for the Interactions API, a different surface. So the value below
     * is a recognised classification input, not a quoted response for this endpoint.</p>
     */
    private static void checkModelUnavailable(Wire wiring) throws Exception {
        // Synthetic. Envelope shape from the discovery document; the reason value is the
        // program's classification input, not a value observed from this endpoint.
        IOException retired = serviceError(404, "NOT_FOUND", "MODEL_NOT_FOUND",
                "models/gemini-2.0-flash is not found for API version v1beta, or is not"
                        + " supported for generateContent.");
        Response response = briefingFrom(wiring, new FakeModel(null, retired));
        check("a clear model-not-found answers 200", response.status == 200);
        check("a clear model-not-found is reported as ModelUnavailable",
                response.body.contains("\"reason\":\"ModelUnavailable\""));
        check("a clear model-not-found is NOT reported as a rejected key",
                !response.body.contains("\"reason\":\"KeyRejected\""));
        check("a clear model-not-found does not mention a rejected key",
                !response.body.toLowerCase().contains("rejected the configured key"));
        check("a clear model-not-found points at the model setting",
                response.body.contains("gemini.model"));
        // A model error does not prove the key is valid for anything else, so the message
        // must not assert that it was accepted.
        check("a model error does not claim the key was accepted",
                !response.body.toLowerCase().contains("key was accepted"));
        check("a clear model-not-found keeps the RULES label",
                response.body.contains("\"source\":\"RULES\"")
                        && response.body.contains("\"briefing\":null"));
        check("a clear model-not-found offers the rule summary",
                response.body.contains("\"ruleSummary\":{\"source\":\"RULES\""));

        // Synthetic. A 400 whose sentence names the model and says it is gone, with no
        // structured reason at all - the wording is invented, not quoted from Google.
        IOException namedInProse = serviceError(400, "INVALID_ARGUMENT", null,
                "models/gemini-2.0-flash has been shut down; please use another model.");
        Response second = briefingFrom(wiring, new FakeModel(null, namedInProse));
        check("a 400 that names the model and says it is gone is ModelUnavailable",
                second.body.contains("\"reason\":\"ModelUnavailable\""));

        // The case this round exists for. Synthetic: a bare 400 with nothing that
        // identifies the key or the model as the cause.
        IOException bare400 = serviceError(400, "INVALID_ARGUMENT", null,
                "Request contains an invalid argument.");
        Response generic = briefingFrom(wiring, new FakeModel(null, bare400));
        check("a bare 400 is reported as RequestRejected",
                generic.body.contains("\"reason\":\"RequestRejected\""));
        check("a bare 400 is NOT reported as ModelUnavailable",
                !generic.body.contains("\"reason\":\"ModelUnavailable\""));
        check("a bare 400 is NOT reported as KeyRejected",
                !generic.body.contains("\"reason\":\"KeyRejected\""));
        check("a bare 400 does not claim the key was accepted",
                !generic.body.toLowerCase().contains("key was accepted"));
        check("a bare 400 does not tell the user to change the model specifically",
                !generic.body.contains("Set gemini.model"));
        check("a bare 400 admits the response did not say what was wrong",
                generic.body.contains("did not say which part was wrong"));
        check("a bare 400 still keeps the RULES label",
                generic.body.contains("\"source\":\"RULES\"")
                        && generic.body.contains("\"briefing\":null"));
        check("a bare 400 still offers the rule summary",
                generic.body.contains("\"ruleSummary\":{\"source\":\"RULES\""));
        check("a bare 400 never returns fabricated AI text",
                generic.body.contains("\"aiAvailable\":false"));

        // Synthetic. A 404 with no model mentioned must not be read as a model problem:
        // a wrong path produces the same status.
        IOException bare404 = serviceError(404, "NOT_FOUND", null, "Not Found");
        Response notFound = briefingFrom(wiring, new FakeModel(null, bare404));
        check("a bare 404 is not turned into a model diagnosis",
                notFound.body.contains("\"reason\":\"RequestRejected\""));

        // Synthetic. A genuine key problem must still be KeyRejected.
        IOException badKey = serviceError(400, "INVALID_ARGUMENT", "API_KEY_INVALID",
                "API key not valid. Please pass a valid API key.");
        Response third = briefingFrom(wiring, new FakeModel(null, badKey));
        check("a structured bad key is still KeyRejected",
                third.body.contains("\"reason\":\"KeyRejected\""));
        check("a structured bad key tells the user to check the key",
                third.body.contains("Check the key in"));

        // Synthetic. A rejected key whose sentence mentions a model: the structured
        // reason must win over the prose.
        IOException ambiguous = serviceError(400, "INVALID_ARGUMENT", "API_KEY_INVALID",
                "model not found or API key not valid");
        Response fourth = briefingFrom(wiring, new FakeModel(null, ambiguous));
        check("the service's structured reason beats a model-sounding sentence",
                fourth.body.contains("\"reason\":\"KeyRejected\""));
    }

    /** Builds the same IOException structure GeminiClient produces for a non-2xx answer. */
    private static IOException serviceError(int code, String status, String reason,
                                            String message) {
        return new ai.ServiceHttpException(code, status, reason,
                "the AI service answered " + code + ": " + message);
    }

    // ------------------------------------------------------------- requests

    private static Response get(String path) throws Exception {
        return get(path, null);
    }

    private static Response get(String path, String role) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (role != null) {
            builder.header("X-Demo-Role", role);
        }
        return send(builder.build());
    }

    private static Response post(String path, String body, String role) throws Exception {
        return withBody("POST", path, body, role);
    }

    private static Response put(String path, String body, String role) throws Exception {
        return withBody("PUT", path, body, role);
    }

    private static Response withBody(String method, String path, String body, String role)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (role != null && !role.isEmpty()) {
            builder.header("X-Demo-Role", role);
        }
        return send(builder.build());
    }

    private static Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // -------------------------------------------------------------- helpers

    private static void section(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("   PASS " + label);
        } else {
            failed++;
            System.out.println("   FAIL " + label);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort: a leftover file only affects a rerun.
                    }
                });
    }
}
