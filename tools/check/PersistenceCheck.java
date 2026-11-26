// Manual check program - NOT part of the StockPulse website.
//
// Phase 3D: proves that saved data reloads exactly, and that a damaged file is reported
// with its file name and line number and is NOT overwritten.
//
// Runs in a scratch folder under out/, so it never touches the real data/ directory.
//
//   javac -d out -sourcepath src tools/check/PersistenceCheck.java
//   java -cp out PersistenceCheck

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;

import data.DataBootstrap;
import data.DataFiles;
import data.DataStore;
import exception.DataFileException;
import model.Batch;
import model.SaleRecord;
import model.Season;
import model.Settings;
import model.StockItem;
import service.ActivityLogStore;
import service.InventoryService;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.SeasonStore;
import store.WasteStore;

public final class PersistenceCheck {

    private static int passed = 0;
    private static int failed = 0;

    private PersistenceCheck() {
    }

    /** Everything a "session" needs, so a second session can be built the same way. */
    private static final class Session {
        final ItemStore items = new ItemStore();
        final BatchStore batches = new BatchStore();
        final SaleStore sales = new SaleStore();
        final WasteStore waste = new WasteStore();
        final SeasonStore seasons = new SeasonStore();
        final ActivityLogStore activity = new ActivityLogStore();
        final Settings settings = new Settings();
        final DataStore store;

        Session(Path dir) {
            this.store = new DataStore(dir, settings);
        }

        InventoryService service() {
            InventoryService service = new InventoryService(items, batches, sales, waste,
                    settings, activity);
            service.useSeasons(seasons.seasons(), seasons.count());
            return service;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("out", "persistence-check");
        deleteTree(root);

        System.out.println("=== StockPulse persistence check ===");
        System.out.println("Scratch folder: " + root.toAbsolutePath());

        // ------------------------------------------------ 1. first run seeds sample data
        Session first = new Session(root);
        boolean seeded = DataBootstrap.loadOrSeed(first.store, first.items, first.batches,
                first.sales, first.waste, first.seasons, first.activity, first.settings);
        check("first run created the sample data", seeded);
        check("data folder now exists", Files.isDirectory(root));
        for (int i = 0; i < DataFiles.ALL.length; i++) {
            check("wrote " + DataFiles.ALL[i],
                    Files.isRegularFile(root.resolve(DataFiles.ALL[i])));
        }
        int sampleItems = first.items.count();
        int sampleBatches = first.batches.count();
        int sampleSales = first.sales.count();
        System.out.println("   sample data: items=" + sampleItems + " batches=" + sampleBatches
                + " sales=" + sampleSales + " seasons=" + first.seasons.count()
                + " activity=" + first.activity.count());

        // ------------------------------------------- 2. mutate, then save through the service
        InventoryService service = first.service();
        LocalDate today = first.settings.effectiveDate();

        // A sale draws from batches first-expired-first-out and changes two quantities.
        model.SaleRecord sold = service.recordSale("I001", 5, Double.NaN,
                InventoryService.ROLE_STAFF);
        System.out.println("   sold 5 x I001 from " + sold.getBatchDraws());
        // A delivery adds a whole new batch with its own expiry.
        Batch delivered = service.receiveDelivery("I002", 4, today.plusDays(6), 3.60,
                InventoryService.ROLE_STAFF);
        System.out.println("   received batch " + delivered.getBatchId() + " of I002");
        // A waste record moves stock out through the waste ledger, not a sale.
        service.recordWaste("I002", "B0003", 6, "expired on shelf",
                InventoryService.ROLE_STAFF);
        // A settings change must survive the restart too.
        first.settings.setSalesWindowDays(21);
        first.settings.setSafetyMarginDays(2);
        first.settings.setReorderHorizonDays(10);

        first.store.saveAll(first.items, first.batches, first.sales, first.waste,
                first.seasons, first.activity);

        // Snapshot the expected values before the second session exists.
        double expectedMilkStock = first.batches.stockOf("I001");
        int expectedBatchCount = first.batches.count();
        int expectedSaleCount = first.sales.count();
        int expectedWasteCount = first.waste.count();
        int expectedItemCount = first.items.count();
        int expectedActivityCount = first.activity.count();
        String expectedItemLine = first.items.findById("I005").toDataLine();

        // --------------------------------------- 3. a fresh session reloads from disk only
        System.out.println();
        System.out.println("--- Reload in a brand new session (simulating a restart) ---");
        Session second = new Session(root);
        boolean seededAgain = DataBootstrap.loadOrSeed(second.store, second.items,
                second.batches, second.sales, second.waste, second.seasons,
                second.activity, second.settings);
        check("second run did NOT seed again", !seededAgain);
        check("items reloaded", second.items.count() == expectedItemCount);
        check("batches reloaded", second.batches.count() == expectedBatchCount);
        check("sales reloaded", second.sales.count() == expectedSaleCount);
        check("waste reloaded", second.waste.count() == expectedWasteCount);
        check("seasons reloaded", second.seasons.count() == first.seasons.count());
        check("activity reloaded", second.activity.count() == expectedActivityCount);
        check("stock level identical (batch draw-down survived)",
                close(second.batches.stockOf("I001"), expectedMilkStock));
        check("settings survived (window=21, margin=2, horizon=10)",
                second.settings.getSalesWindowDays() == 21
                        && second.settings.getSafetyMarginDays() == 2
                        && second.settings.getReorderHorizonDays() == 10);
        check("demo date was NOT persisted", !second.settings.isDemoDate());

        // ---------------------------------------------- 4. field-by-field on every object
        System.out.println();
        System.out.println("--- Field-by-field comparison ---");
        String itemMismatch = compareItems(first.items, second.items);
        check("every item field matches" + suffix(itemMismatch), itemMismatch == null);

        String batchMismatch = compareBatches(first.batches, second.batches);
        check("every batch field matches" + suffix(batchMismatch), batchMismatch == null);

        String saleMismatch = compareSales(first.sales, second.sales);
        check("every sale field matches" + suffix(saleMismatch), saleMismatch == null);

        String wasteMismatch = compareWaste(first.waste, second.waste);
        check("every waste field matches" + suffix(wasteMismatch), wasteMismatch == null);

        // The polymorphic type tag must survive: a long-life item must not reload as a
        // perishable one, which would give it an expiry date it never had.
        StockItem reloadedSyrup = second.items.findById("I005");
        check("long-life item kept its subclass",
                reloadedSyrup != null && !reloadedSyrup.canExpire()
                        && reloadedSyrup.itemType().equals(model.StockItem.TYPE_LONGLIFE));
        check("perishable item kept its subclass",
                second.items.findById("I001") != null
                        && second.items.findById("I001").canExpire());
        check("season link restored on the seasonal item",
                reloadedSyrup != null && reloadedSyrup.getSeason() != null
                        && reloadedSyrup.getSeason().getName().equals("Dry")
                        && close(reloadedSyrup.getSeasonalMultiplier(), 1.35));
        check("wrapping season reloaded (Oct - Feb)",
                second.seasons.findByName("Rainy") != null
                        && second.seasons.findByName("Rainy").monthCount() == 5);
        check("long-life batch kept a null expiry date",
                second.batches.findById("B0006") != null
                        && second.batches.findById("B0006").getExpiryDate() == null);
        check("item data line is byte-identical after reload",
                expectedItemLine.equals(second.items.findById("I005").toDataLine()));

        // ------------------------------------- 5. a saved sale is still saleable data
        LocalDate reloadedToday = second.settings.effectiveDate();
        double reloadedVelocity = second.service().risk().observedVelocity("I001", reloadedToday);
        check("risk engine can compute velocity from reloaded sales (" 
                + String.format("%.3f", reloadedVelocity) + "/day)", reloadedVelocity > 0);

        // ------------------------------------------- 6. corrupt files are reported, not saved
        System.out.println();
        System.out.println("--- Corrupt file handling ---");

        expectDamage(root, DataFiles.ITEMS, "I999|Broken Item|Dairy|PERISHABLE|L",
                "too few fields");
        expectDamage(root, DataFiles.ITEMS,
                "I999|Broken|Dairy|NOT_A_TYPE|L|4.50|2.10|7|12|Supplier|2|-|1.00",
                "unknown item type");
        expectDamage(root, DataFiles.BATCHES,
                "B9999|I001|10|4|31/02/2026|-|2.10",
                "impossible date");
        expectDamage(root, DataFiles.BATCHES,
                "B9999|I001|ten|10|13/11/2026|-|2.10",
                "non-numeric quantity");
        expectDamage(root, DataFiles.BATCHES,
                "B9999|I404|10|10|13/11/2026|-|2.10",
                "batch points at a missing item");
        expectDamage(root, DataFiles.SEASONS,
                "Monsoon|13|2",
                "month out of range");
        expectDamage(root, DataFiles.SETTINGS,
                "SALES_WINDOW_DAYZ=14",
                "unknown setting key");

        // Prove that a failed load leaves the previous GOOD data untouched in memory.
        System.out.println();
        System.out.println("--- After a failed load, the stores keep their good data ---");
        Session third = new Session(root);
        DataBootstrap.loadOrSeed(third.store, third.items, third.batches, third.sales,
                third.waste, third.seasons, third.activity, third.settings);
        int goodSaleCount = third.sales.count();

        appendLine(root.resolve(DataFiles.SALES), "S9999|I001|13/11/2026|notanumber|4.50|");
        String corruptBytes = Files.readString(root.resolve(DataFiles.SALES),
                StandardCharsets.UTF_8);

        try {
            third.store.loadAll(third.items, third.batches, third.sales, third.waste,
                    third.seasons, third.activity);
            fail("expected DataFileException for a non-numeric sale quantity");
        } catch (DataFileException e) {
            check("reported file name", DataFiles.SALES.equals(e.getFileName()));
            check("reported the line number (" + e.getLineNumber() + ")", e.getLineNumber() > 0);
            System.out.println("   message: " + e.getMessage());
        }
        check("good in-memory data survived the failed load",
                third.sales.count() == goodSaleCount);
        check("corrupt file was NOT overwritten",
                corruptBytes.equals(Files.readString(root.resolve(DataFiles.SALES),
                        StandardCharsets.UTF_8)));

        // Restore sales.txt to its good content by rewriting it from the reloaded stores,
        // which also re-proves that a save produces a loadable file.
        third.store.saveAll(third.items, third.batches, third.sales, third.waste,
                third.seasons, third.activity);

        // ------------------------- 7. a bad settings line leaves the live settings alone
        System.out.println();
        System.out.println("--- Settings load is transactional ---");
        Session fourth = new Session(root);
        fourth.settings.setSalesWindowDays(30);
        fourth.settings.setSafetyMarginDays(5);
        fourth.settings.setActiveSeasonName("Rainy");
        String liveBefore = fourth.settings.savedStateText();
        System.out.println("   live settings before   : " + liveBefore);

        // Two valid lines that would change the thresholds, then one bad line. If the load
        // were not transactional the first two would already have been applied.
        String goodSettings = Files.readString(root.resolve(DataFiles.SETTINGS),
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve(DataFiles.SETTINGS),
                "SALES_WINDOW_DAYS=99" + System.lineSeparator()
                        + "SAFETY_MARGIN_DAYS=42" + System.lineSeparator()
                        + "SEASON_FROM_DATE=maybe" + System.lineSeparator(),
                StandardCharsets.UTF_8);
        try {
            fourth.store.loadAll(fourth.items, fourth.batches, fourth.sales, fourth.waste,
                    fourth.seasons, fourth.activity);
            fail("expected DataFileException for SEASON_FROM_DATE=maybe");
        } catch (DataFileException e) {
            check("bad settings value reported with file + line",
                    DataFiles.SETTINGS.equals(e.getFileName()) && e.getLineNumber() == 3);
            System.out.println("      " + e.getMessage());
        }
        check("live settings unchanged after a failed settings load ("
                        + liveBefore + " -> " + fourth.settings.savedStateText() + ")",
                liveBefore.equals(fourth.settings.savedStateText()));
        check("the two valid lines before the bad one were NOT applied",
                fourth.settings.getSalesWindowDays() != 99
                        && fourth.settings.getSafetyMarginDays() != 42);
        Files.writeString(root.resolve(DataFiles.SETTINGS), goodSettings, StandardCharsets.UTF_8);

        // ------------------------------- 8. a partially missing data folder is an error
        System.out.println();
        System.out.println("--- A partially missing data folder ---");
        Session partial = new Session(root);
        partial.store.loadAll(partial.items, partial.batches, partial.sales, partial.waste,
                partial.seasons, partial.activity);
        int salesBefore = partial.sales.count();
        check("full folder loads, isFirstRun=" + partial.store.isFirstRun(),
                !partial.store.isFirstRun() && partial.store.existingFileCount() == 7);

        Path moved = root.resolve("sales.txt.moved");
        Files.move(root.resolve(DataFiles.SALES), moved);
        String itemsBytes = Files.readString(root.resolve(DataFiles.ITEMS), StandardCharsets.UTF_8);
        try {
            partial.store.loadAll(partial.items, partial.batches, partial.sales, partial.waste,
                    partial.seasons, partial.activity);
            fail("expected DataFileException for a missing sales.txt");
        } catch (DataFileException e) {
            check("missing file named in the error (" + e.getFileName() + ")",
                    DataFiles.SALES.equals(e.getFileName()));
            check("missing file reports line 0 (not a line problem)", e.getLineNumber() == 0);
            System.out.println("      " + e.getMessage());
        }
        check("memory unchanged after the missing-file failure",
                partial.sales.count() == salesBefore);
        check("no file was written while a file was missing",
                itemsBytes.equals(Files.readString(root.resolve(DataFiles.ITEMS),
                        StandardCharsets.UTF_8))
                        && !Files.exists(root.resolve(DataFiles.SALES)));
        Files.move(moved, root.resolve(DataFiles.SALES));

        // ----------------------------------------- 9. record references are validated
        System.out.println();
        System.out.println("--- Referential checks ---");
        // Waste pointing at a batch that belongs to a different item.
        expectReferenceError(root, DataFiles.WASTE,
                "W9999|B0001|I002|26/11/2026|1|3.60|wrong item",
                "waste batch belongs to a different item", "belongs to");
        // Waste pointing at a batch that does not exist.
        expectReferenceError(root, DataFiles.WASTE,
                "W9998|B7777|I001|26/11/2026|1|2.10|no such batch",
                "waste batch does not exist", "not in");
        // Sale drawing from another item's batch.
        expectReferenceError(root, DataFiles.SALES,
                "S9997|I001|26/11/2026|5|4.50|B0004:5",
                "sale draws from another item's batch", "belongs to");
        // Sale drawing from a batch that does not exist.
        expectReferenceError(root, DataFiles.SALES,
                "S9996|I001|26/11/2026|5|4.50|B7777:5",
                "sale draws from a missing batch", "not in");
        // Sale whose draws do not add up to the sold quantity.
        expectReferenceError(root, DataFiles.SALES,
                "S9995|I001|26/11/2026|10|4.50|B0001:4,B0002:3",
                "sale draws total 7 but quantity is 10", "totalling");
        // Sale whose draw records no quantity.
        expectReferenceError(root, DataFiles.SALES,
                "S9994|I001|26/11/2026|5|4.50|B0001:0",
                "sale draw has a zero quantity", "without a positive quantity");
        // A sale with NO draws at all is still accepted (imported history).
        Session noDraws = new Session(root);
        appendLine(root.resolve(DataFiles.SALES), "S9993|I001|26/11/2026|3|4.50|");
        try {
            noDraws.store.loadAll(noDraws.items, noDraws.batches, noDraws.sales, noDraws.waste,
                    noDraws.seasons, noDraws.activity);
            check("a sale with no draws at all is accepted", true);
        } catch (DataFileException e) {
            fail("a sale with no draws should be accepted but got: " + e.getMessage());
        }

        // --------------------------------------------- 10. the files are human-readable
        System.out.println();
        System.out.println("--- First two lines of each file ---");
        for (int i = 0; i < DataFiles.ALL.length; i++) {
            // Arrays, not Files.readAllLines(): the whole project avoids collection classes.
            String[] lines = Files.readString(root.resolve(DataFiles.ALL[i]),
                    StandardCharsets.UTF_8).split("\\R", -1);
            int realLines = 0;
            for (int j = 0; j < lines.length; j++) {
                if (!lines[j].isEmpty()) {
                    realLines++;
                }
            }
            System.out.println("   " + DataFiles.ALL[i] + "  (" + realLines + " lines)");
            int shown = 0;
            for (int j = 0; j < lines.length && shown < 2; j++) {
                if (!lines[j].isEmpty()) {
                    System.out.println("      " + lines[j]);
                    shown++;
                }
            }
        }

        System.out.println();
        System.out.printf("=== %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --------------------------------------------------------------- corrupt cases

    /**
     * Writes a deliberately broken file, loads, and confirms the error names the file and
     * the correct line, and that the broken file is still on disk unchanged afterwards.
     */
    private static void expectDamage(Path root, String fileName, String badLine, String what)
            throws IOException {
        Path path = root.resolve(fileName);
        // Keep a copy so this test can be repeated for several files.
        String original = Files.exists(path)
                ? Files.readString(path, StandardCharsets.UTF_8) : "";
        appendLine(path, badLine);
        // Snapshot exactly what is on disk now, with the bad line in it. This is the
        // content a failed load must leave byte-identical.
        String damaged = Files.readString(path, StandardCharsets.UTF_8);
        int badLineNumber = Files.readAllLines(path, StandardCharsets.UTF_8).size();

        Session s = new Session(root);
        try {
            s.store.loadAll(s.items, s.batches, s.sales, s.waste, s.seasons, s.activity);
            fail("expected DataFileException for " + what);
        } catch (DataFileException e) {
            boolean rightFile = fileName.equals(e.getFileName());
            boolean rightLine = e.getLineNumber() == badLineNumber;
            check(what + " -> " + fileName + " line " + e.getLineNumber()
                            + (rightFile && rightLine ? "" : "  MISMATCH"),
                    rightFile && rightLine);
            System.out.println("      " + e.getMessage());
        } catch (Exception e) {
            fail(what + " threw " + e.getClass().getSimpleName() + " instead of DataFileException");
        }

        // Nothing was written during the failed load, so the file must be byte-identical
        // to the damaged version we just put on disk.
        String after = Files.readString(path, StandardCharsets.UTF_8);
        check(what + " -> file left untouched", after.equals(damaged));

        // Restore so the next case starts from good data.
        Files.writeString(path, original, StandardCharsets.UTF_8);
    }

    /**
     * Appends a record whose references are wrong, loads, and confirms the error names the
     * file, the line, and the problem. Restores the file afterwards.
     */
    private static void expectReferenceError(Path root, String fileName, String badLine,
                                             String what, String mustMention)
            throws IOException {
        Path path = root.resolve(fileName);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        appendLine(path, badLine);
        int badLineNumber = countLines(path);
        String damaged = Files.readString(path, StandardCharsets.UTF_8);

        Session s = new Session(root);
        try {
            s.store.loadAll(s.items, s.batches, s.sales, s.waste, s.seasons, s.activity);
            fail("expected DataFileException for " + what);
        } catch (DataFileException e) {
            boolean right = fileName.equals(e.getFileName())
                    && e.getLineNumber() == badLineNumber
                    && e.getMessage().contains(mustMention);
            check(what + " -> " + fileName + " line " + e.getLineNumber()
                    + (right ? "" : "  MISMATCH"), right);
            System.out.println("      " + e.getMessage());
        } catch (Exception e) {
            fail(what + " threw " + e.getClass().getSimpleName() + " instead of DataFileException");
        }
        check(what + " -> file left untouched",
                damaged.equals(Files.readString(path, StandardCharsets.UTF_8)));
        Files.writeString(path, original, StandardCharsets.UTF_8);
    }

    private static int countLines(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return 0;
        }
        String[] lines = text.split("\\R", -1);
        int count = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static void appendLine(Path path, String line) throws IOException {
        String existing = Files.exists(path)
                ? Files.readString(path, StandardCharsets.UTF_8) : "";
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            existing = existing + System.lineSeparator();
        }
        Files.writeString(path, existing + line + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ field compares

    private static String compareItems(ItemStore a, ItemStore b) {
        for (int i = 0; i < a.count(); i++) {
            StockItem x = a.items()[i];
            if (x == null) {
                continue;
            }
            StockItem y = b.findById(x.getItemId());
            if (y == null) {
                return " (" + x.getItemId() + " missing after reload)";
            }
            if (!x.getItemId().equals(y.getItemId())
                    || !x.getName().equals(y.getName())
                    || !x.getCategory().equals(y.getCategory())
                    || !x.itemType().equals(y.itemType())
                    || !x.getUnit().equals(y.getUnit())
                    || !close(x.getSellingPrice(), y.getSellingPrice())
                    || !close(x.getUnitCost(), y.getUnitCost())
                    || x.getShelfLifeDays() != y.getShelfLifeDays()
                    || x.getPackSize() != y.getPackSize()
                    || !x.getSupplierName().equals(y.getSupplierName())
                    || x.getLeadTimeDays() != y.getLeadTimeDays()
                    || !close(x.getSeasonalMultiplier(), y.getSeasonalMultiplier())) {
                return " (" + x.getItemId() + " differs)";
            }
            if (x.getSeason() == null && y.getSeason() != null
                    || x.getSeason() != null && y.getSeason() == null
                    || x.getSeason() != null
                    && !x.getSeason().getName().equals(y.getSeason().getName())) {
                return " (" + x.getItemId() + " season differs)";
            }
        }
        return null;
    }

    private static String compareBatches(BatchStore a, BatchStore b) {
        for (int i = 0; i < a.count(); i++) {
            Batch x = a.batches()[i];
            if (x == null) {
                continue;
            }
            Batch y = b.findById(x.getBatchId());
            if (y == null) {
                return " (" + x.getBatchId() + " missing after reload)";
            }
            if (!x.getItemId().equals(y.getItemId())
                    || !close(x.getQtyReceived(), y.getQtyReceived())
                    || !close(x.getQtyRemaining(), y.getQtyRemaining())
                    || !x.getReceivedDate().equals(y.getReceivedDate())
                    || !close(x.getUnitCost(), y.getUnitCost())) {
                return " (" + x.getBatchId() + " differs)";
            }
            boolean xHasExpiry = x.getExpiryDate() != null;
            boolean yHasExpiry = y.getExpiryDate() != null;
            if (xHasExpiry != yHasExpiry
                    || xHasExpiry && !x.getExpiryDate().equals(y.getExpiryDate())) {
                return " (" + x.getBatchId() + " expiry differs)";
            }
        }
        return null;
    }

    private static String compareSales(SaleStore a, SaleStore b) {
        for (int i = 0; i < a.count(); i++) {
            SaleRecord x = a.sales()[i];
            if (x == null) {
                continue;
            }
            SaleRecord y = null;
            for (int j = 0; j < b.count(); j++) {
                if (b.sales()[j] != null && b.sales()[j].getSaleId().equals(x.getSaleId())) {
                    y = b.sales()[j];
                    break;
                }
            }
            if (y == null) {
                return " (" + x.getSaleId() + " missing after reload)";
            }
            if (!x.getItemId().equals(y.getItemId())
                    || !x.getDate().equals(y.getDate())
                    || !close(x.getQuantity(), y.getQuantity())
                    || !close(x.getUnitPrice(), y.getUnitPrice())
                    || !x.getBatchDraws().equals(y.getBatchDraws())) {
                return " (" + x.getSaleId() + " differs)";
            }
        }
        return null;
    }

    private static String compareWaste(WasteStore a, WasteStore b) {
        for (int i = 0; i < a.count(); i++) {
            model.WasteRecord x = a.waste()[i];
            if (x == null) {
                continue;
            }
            model.WasteRecord y = null;
            for (int j = 0; j < b.count(); j++) {
                if (b.waste()[j] != null && b.waste()[j].getWasteId().equals(x.getWasteId())) {
                    y = b.waste()[j];
                    break;
                }
            }
            if (y == null) {
                return " (" + x.getWasteId() + " missing after reload)";
            }
            if (!x.getBatchId().equals(y.getBatchId())
                    || !x.getItemId().equals(y.getItemId())
                    || !x.getDate().equals(y.getDate())
                    || !close(x.getQuantity(), y.getQuantity())
                    || !close(x.getUnitCost(), y.getUnitCost())
                    || !x.getReason().equals(y.getReason())) {
                return " (" + x.getWasteId() + " differs)";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.0000001;
    }

    private static String suffix(String mismatch) {
        return (mismatch == null) ? "" : "  <-" + mismatch;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("   PASS " + what);
        } else {
            failed++;
            System.out.println("   FAIL " + what);
        }
    }

    private static void fail(String why) {
        failed++;
        System.out.println("   FAIL " + why);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort: the scratch folder is disposable
                    }
                });
    }
}
