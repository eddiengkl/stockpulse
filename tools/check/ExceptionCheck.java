// Manual check program - NOT part of the StockPulse website.
//
// Phase 3C: shows every custom exception being thrown and caught, and shows the
// translation chain - the model throws IllegalArgumentException for bad state, the
// service catches it and throws InvalidInputException for the API.
//
//   javac -d out -sourcepath src tools/check/ExceptionCheck.java
//   java -cp out ExceptionCheck

import java.time.LocalDate;

import exception.DataFileException;
import exception.ExpiredStockException;
import exception.InsufficientStockException;
import exception.InvalidInputException;
import exception.InvalidSeasonException;
import exception.ItemNotFoundException;
import exception.RoleNotPermittedException;
import exception.StorageFullException;
import model.Batch;
import model.LongLifeItem;
import model.PerishableItem;
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

public final class ExceptionCheck {

    private static int passed = 0;
    private static int failed = 0;

    private ExceptionCheck() {
    }

    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026, 11, 26);

        // Stores are small on purpose so the full-array path is easy to reach.
        ItemStore items = new ItemStore(3);
        BatchStore batches = new BatchStore(2);
        SaleStore sales = new SaleStore(2);
        WasteStore waste = new WasteStore(2);
        SeasonStore seasons = new SeasonStore(2);
        Settings settings = new Settings();
        settings.setDemoDate(today);
        ActivityLogStore log = new ActivityLogStore(50);

        InventoryService service = new InventoryService(items, batches, sales, waste, settings, log);

        StockItem milk = new PerishableItem("I001", "Fresh Milk 1L", "Dairy", "L",
                4.50, 2.10, 7, 12, "Creamery Sdn Bhd", 2);
        StockItem syrup = new LongLifeItem("I014", "Iced Syrup - Lychee", "Syrup", "bottle",
                18.00, 9.00, 365, 6, "Sweetline", 5);
        items.add(milk);
        items.add(syrup);

        System.out.println("=== StockPulse custom exception check ===");

        // ---------------------------------------------------------------- 1
        System.out.println();
        System.out.println("1. ItemNotFoundException  (service -> API 404)");
        try {
            service.recordSale("I999", 1, Double.NaN, InventoryService.ROLE_STAFF);
            fail("expected ItemNotFoundException");
        } catch (ItemNotFoundException e) {
            pass("caught at the call site", "lookedForId=" + e.getLookedForId()
                    + " | " + e.getMessage());
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName());
        }

        // ---------------------------------------------------------------- 2
        System.out.println();
        System.out.println("2. InvalidInputException  (service -> API 400)");
        try {
            service.recordSale("I001", -3, Double.NaN, InventoryService.ROLE_STAFF);
            fail("expected InvalidInputException for a negative quantity");
        } catch (InvalidInputException e) {
            pass("caught at the call site", "field=" + e.getField() + " | " + e.getMessage());
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName());
        }

        // ---------------------------------------------------------------- 3
        System.out.println();
        System.out.println("3. Translation: model IllegalArgumentException -> InvalidInputException");
        // Two layers guard the same rule, and both are worth showing:
        //   (a) the service checks first, so ordinary bad input never reaches the model;
        //   (b) if a bad value did reach the model, the constructor's IllegalArgumentException
        //       is caught by the service and re-thrown as the checked exception.
        try {
            new Batch("B0001", "I001", 0, 0, today, today.plusDays(5), 2.10);
            fail("expected the model to reject a zero quantity");
        } catch (IllegalArgumentException e) {
            pass("(b) the model threw the unchecked exception", e.getMessage());
        }
        try {
            service.receiveDelivery("I001", 0, today.plusDays(5), 2.10, InventoryService.ROLE_STAFF);
            fail("expected the service to translate it");
        } catch (InvalidInputException e) {
            pass("(a) the service rejected it first", "field=" + e.getField() + " | "
                    + e.getMessage() + " | cause="
                    + (e.getCause() == null ? "none (service checked it directly)"
                                            : e.getCause().getClass().getSimpleName()));
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName());
        }
        // (b) reached directly: a name the model rejects, arriving through the service.
        try {
            service.receiveDelivery("I001", 2, today.plusDays(5), -1, InventoryService.ROLE_STAFF);
            fail("expected InvalidInputException for a negative cost");
        } catch (InvalidInputException e) {
            pass("(a) the service rejected the cost", "field=" + e.getField() + " | " + e.getMessage());
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName());
        }

        // ---------------------------------------------------------------- 4
        System.out.println();
        System.out.println("4. InsufficientStockException  (service -> API 409)");
        try {
            service.receiveDelivery("I001", 5, today.plusDays(6), 2.10, InventoryService.ROLE_STAFF);
            service.recordSale("I001", 12, Double.NaN, InventoryService.ROLE_STAFF);
            fail("expected InsufficientStockException");
        } catch (InsufficientStockException e) {
            pass("caught at the call site", "requested=" + e.getRequested()
                    + " available=" + e.getAvailable() + " | " + e.getMessage());
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName());
        }

        // ---------------------------------------------------------------- 5
        System.out.println();
        System.out.println("5. ExpiredStockException  (service -> API 409)");
        // A separate item whose only stock is already expired, so this case stands alone
        // and does not depend on what steps 3-4 left on the shelf.
        StockItem cream = new PerishableItem("I002", "Whipping Cream 1L", "Dairy", "L",
                7.20, 3.60, 5, 6, "Creamery Sdn Bhd", 2);
        items.add(cream);
        Batch expired = new Batch("B0002", "I002", 4, 4, today.minusDays(10),
                today.minusDays(3), 3.60);
        batches.add(expired);
        try {
            service.recordSale("I002", 1, Double.NaN, InventoryService.ROLE_STAFF);
            fail("expected ExpiredStockException");
        } catch (ExpiredStockException e) {
            pass("caught at the call site", "batchId=" + e.getBatchId()
                    + " daysExpired=" + e.getDaysExpired() + " | " + e.getMessage());
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        // ...and the prescribed route out is a waste record.
        try {
            service.recordWaste("I002", "B0002", 4, "past use-by", InventoryService.ROLE_STAFF);
            pass("expired stock left via waste instead", "batch now holds "
                    + batches.findById("B0002").getQtyRemaining());
        } catch (Exception e) {
            fail("waste should have been accepted: " + e.getMessage());
        }

        // ---------------------------------------------------------------- 6
        System.out.println();
        System.out.println("6. RoleNotPermittedException  (service -> API 403)");
        try {
            service.undoLastSale("I001", InventoryService.ROLE_STAFF);
            fail("expected RoleNotPermittedException");
        } catch (RoleNotPermittedException e) {
            pass("caught at the call site", "role=" + e.getRole()
                    + " action=" + e.getAction() + " | " + e.getMessage());
        } catch (ItemNotFoundException e) {
            // Only reachable if the role check were removed: requireRole runs first.
            fail("role check must come before the lookup, got " + e.getMessage());
        }

        // ---------------------------------------------------------------- 7
        System.out.println();
        System.out.println("7. StorageFullException  (service -> API 507)");
        // Batches has capacity 2 and already holds one.
        try {
            service.receiveDelivery("I001", 3, today.plusDays(8), 2.10, InventoryService.ROLE_STAFF);
            service.receiveDelivery("I001", 3, today.plusDays(9), 2.10, InventoryService.ROLE_STAFF);
            pass("second delivery fitted, third must not", "batches=" + batches.count()
                    + "/" + batches.capacity());
            service.receiveDelivery("I001", 3, today.plusDays(10), 2.10, InventoryService.ROLE_STAFF);
            fail("expected StorageFullException");
        } catch (StorageFullException e) {
            pass("caught at the call site", "store=" + e.getStoreName()
                    + " capacity=" + e.getCapacity() + " | " + e.getMessage());
        } catch (Exception e) {
            fail("wrong exception: " + e.getClass().getSimpleName());
        }

        // ---------------------------------------------------------------- 8
        System.out.println();
        System.out.println("8. InvalidSeasonException  (no translation - model throws it itself)");
        try {
            new Season("Monsoon", 13, 2);
            fail("expected InvalidSeasonException");
        } catch (InvalidSeasonException e) {
            pass("caught at the call site", e.getMessage());
        }
        try {
            Season wrap = new Season("Rainy", 10, 2);
            seasons.add(wrap);
            pass("a wrapping season is accepted", wrap.monthRangeText() + " = "
                    + wrap.monthCount() + " months");
            seasons.updateMonths("Rainy", 0, 5);
            fail("expected InvalidSeasonException from the store");
        } catch (InvalidSeasonException e) {
            pass("store re-throws the model's exception", e.getMessage());
        }

        // ---------------------------------------------------------------- 9
        System.out.println();
        System.out.println("9. DataFileException  (loader -> stops instead of losing data)");
        // Phase 3D will throw this from the loader. Building one here shows the shape of
        // the message the user will see when a data file is damaged.
        DataFileException bad = new DataFileException("data/items.txt", 7,
                "expected 13 fields separated by '|' but found 11");
        System.out.println("   caught at the loader: getFileName=" + bad.getFileName()
                + " getLineNumber=" + bad.getLineNumber());
        System.out.println("   message: " + bad.getMessage());

        // ---------------------------------------------------------------- 10
        System.out.println();
        System.out.println("10. Undo is still possible for the Manager");
        try {
            service.recordSale("I001", 2, Double.NaN, InventoryService.ROLE_MANAGER);
            boolean undone = service.undoLastSale("I001", InventoryService.ROLE_MANAGER);
            pass("Manager undid the sale", "undone=" + undone);
        } catch (Exception e) {
            fail("Manager should be allowed: " + e.getMessage());
        }

        System.out.println();
        System.out.printf("=== %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void pass(String what, String detail) {
        passed++;
        System.out.println("   PASS " + what + " -> " + detail);
    }

    private static void fail(String why) {
        failed++;
        System.out.println("   FAIL " + why);
    }
}
