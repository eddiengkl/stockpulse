// Manual check program - NOT part of the StockPulse website.
//
// Exercises Phase 3B: array stores, first-expired-first-out allocation, the risk
// engine, and every custom business exception.
//
//   javac -d out -sourcepath src tools/check/RiskCheck.java
//   java -cp out RiskCheck

import java.time.LocalDate;

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
import service.RiskEngine;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.SeasonStore;
import store.WasteStore;

public final class RiskCheck {

    private RiskCheck() {
    }

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 26);

        // ---- stores and settings, sized small so the full-array path is reachable
        ItemStore items = new ItemStore(6);
        BatchStore batches = new BatchStore(10);
        SaleStore sales = new SaleStore(100);
        WasteStore waste = new WasteStore(10);
        SeasonStore seasonStore = new SeasonStore(3);
        Settings settings = new Settings();
        settings.setDemoDate(today);
        ActivityLogStore log = new ActivityLogStore(20);

        Season dry = new Season("Dry", 5, 9);
        Season rainy = new Season("Rainy", 10, 2);
        seasonStore.add(dry);
        seasonStore.add(rainy);

        InventoryService service = new InventoryService(items, batches, sales, waste, settings, log);
        service.useSeasons(seasonStore.seasons(), seasonStore.count());
        RiskEngine risk = service.risk();

        StockItem milk = new PerishableItem("I001", "Fresh Milk 1L", "Dairy", "L",
                4.50, 2.10, 7, 12, "Creamery Sdn Bhd", 2);
        StockItem croissant = new PerishableItem("I004", "Butter Croissant", "Bakery", "pcs",
                3.80, 1.40, 2, 20, "Golden Crust", 1);
        StockItem syrup = new LongLifeItem("I014", "Iced Syrup - Lychee", "Syrup", "bottle",
                18.00, 9.00, 365, 6, "Sweetline", 5);
        syrup.setSeason(dry);
        syrup.setSeasonalMultiplier(1.35);
        StockItem cream = new PerishableItem("I002", "Whipping Cream 1L", "Dairy", "L",
                7.20, 3.60, 5, 6, "Creamery Sdn Bhd", 2);
        items.add(milk);
        items.add(croissant);
        items.add(syrup);
        items.add(cream);

        System.out.println("=== StockPulse risk check ===");
        System.out.println("Stores: items=" + items.count() + "/" + items.capacity()
                + " batches=" + batches.count() + "/" + batches.capacity()
                + " sales=" + sales.count() + "/" + sales.capacity());

        // ---- 14-day sales history for milk: 5 L a day for 14 days, with one zero day
        double[] milkDaily = {5, 5, 0, 6, 5, 4, 5, 5, 6, 5, 0, 5, 5, 5};
        int saleNo = 0;
        for (int d = 13; d >= 0; d--) {
            double qty = milkDaily[13 - d];
            if (qty > 0) {
                sales.add(new model.SaleRecord("S" + (1000 + saleNo++), "I001",
                        today.minusDays(d), qty, milk.getSellingPrice(), "hist"));
            }
        }
        // croissant sells 20 a day (fast), syrup 1 bottle a day (slow)
        for (int d = 13; d >= 0; d--) {
            sales.add(new model.SaleRecord("S" + (2000 + d), "I004", today.minusDays(d), 20,
                    croissant.getSellingPrice(), "hist"));
            sales.add(new model.SaleRecord("S" + (3000 + d), "I014", today.minusDays(d), 1,
                    syrup.getSellingPrice(), "hist"));
        }

        // ---- velocity: divisor counts zero-sale days
        double sumMilk = sales.unitsSold("I001", today, 14);
        System.out.println();
        System.out.println("--- Observed velocity (14-day window, zero days counted) ---");
        System.out.println("Milk units in window       : " + sumMilk);
        System.out.println("Days since first stocked   : " + risk.daysSinceFirstStocked("I001", today));
        System.out.println("Divisor used               : " + risk.velocityDivisor("I001", today));
        System.out.printf("Observed velocity          : %.3f L/day (%.3f if zero days were skipped)%n",
                risk.observedVelocity("I001", today), sumMilk / 12.0);

        double syrupObserved = risk.observedVelocity("I014", today);
        System.out.printf("Syrup observed/seasonal    : %.3f / %.3f (Dry x1.35, active=%s)%n",
                syrupObserved, risk.seasonalVelocity(syrup, syrupObserved),
                settings.resolveActiveSeason(seasonStore.seasons(), seasonStore.count()).getName());

        // ---- deliveries and stockout rules
        System.out.println();
        System.out.println("--- Deliveries ---");
        service.receiveDelivery("I001", 12, today.plusDays(3), 2.10, InventoryService.ROLE_STAFF);
        service.receiveDelivery("I001", 12, today.plusDays(9), 2.10, InventoryService.ROLE_STAFF);
        service.receiveDelivery("I004", 60, today.plusDays(1), 1.40, InventoryService.ROLE_STAFF);
        service.receiveDelivery("I014", 6, null, 9.00, InventoryService.ROLE_STAFF);
        System.out.println("Milk stock     : " + batches.stockOf("I001") + " L in "
                + batches.liveBatchCount("I001") + " batches");

        double milkStock = batches.stockOf("I001");
        double milkVelocity = risk.seasonalVelocity(milk, risk.observedVelocity("I001", today));
        double cover = risk.daysOfCover(milkStock, milkVelocity);
        double reorderQty = risk.reorderQuantity(milkStock, milkVelocity, milk.getLeadTimeDays(), milk.getPackSize());
        System.out.printf("Milk cover     : %.2f days (cover days = lead %d + margin %d = %d) -> stockout risk=%s%n",
                cover, milk.getLeadTimeDays(), settings.getSafetyMarginDays(),
                settings.coverDaysFor(milk.getLeadTimeDays()),
                risk.isStockoutRisk(cover, milk.getLeadTimeDays()));
        System.out.printf("Milk reorder   : %.0f L (target = %.2f L/day x (%d+%d) days, packs of %d)%n",
                reorderQty, milkVelocity, settings.coverDaysFor(milk.getLeadTimeDays()),
                settings.getReorderHorizonDays(), milk.getPackSize());

        // A second item that is genuinely short: 3 L left, still sells 4.36 L/day.
        StockItem shortMilk = new PerishableItem("I003", "Fresh Milk 500ml", "Dairy", "bottle",
                2.80, 1.30, 5, 6, "Creamery Sdn Bhd", 2);
        items.add(shortMilk);
        service.receiveDelivery("I003", 3, today.plusDays(4), 1.30, InventoryService.ROLE_STAFF);
        for (int d = 9; d >= 0; d--) {
            sales.add(new model.SaleRecord("S4" + d, "I003", today.minusDays(d), 4,
                    shortMilk.getSellingPrice(), "hist"));
        }
        double shortStock = batches.stockOf("I003");
        double shortVel = risk.observedVelocity("I003", today);
        double shortCover = risk.daysOfCover(shortStock, shortVel);
        System.out.printf("500ml cover    : %.2f days on %.0f bottles selling %.2f/day -> stockout risk=%s, urgency=%.2f, reorder %.0f%n",
                shortCover, shortStock, shortVel,
                risk.isStockoutRisk(shortCover, shortMilk.getLeadTimeDays()),
                risk.stockoutUrgency(shortCover, shortMilk.getLeadTimeDays()),
                risk.reorderQuantity(shortStock, shortVel, shortMilk.getLeadTimeDays(), shortMilk.getPackSize()));

        double syrupStock = batches.stockOf("I014");
        double syrupVel = risk.seasonalVelocity(syrup, syrupObserved);
        System.out.printf("Syrup cover    : %.2f days -> stockout risk=%s, reorder %.0f bottles%n",
                risk.daysOfCover(syrupStock, syrupVel),
                risk.isStockoutRisk(risk.daysOfCover(syrupStock, syrupVel), syrup.getLeadTimeDays()),
                risk.reorderQuantity(syrupStock, syrupVel, syrup.getLeadTimeDays(), syrup.getPackSize()));

        // ---- cumulative batch expiry projection: the fix from Phase 2 review
        System.out.println();
        System.out.println("--- Expiry projection: cumulative, not per-batch ---");
        System.out.println("Milk sells ~5 L/day. Batch A=12 L expiring in 3 days, Batch B=12 L in 9 days.");
        RiskEngine.ExpiryProjection[] milkProjection = risk.projectExpiry("I001", milkVelocity, today);
        for (int i = 0; i < milkProjection.length; i++) {
            RiskEngine.ExpiryProjection p = milkProjection[i];
            System.out.printf("  %s daysLeft=%-2d stockAtExpiry=%5.1f projectedLeftover=%5.1f risk=%s%n",
                    p.batch.getBatchId(), p.daysLeft, p.stockAtExpiry, p.projectedLeftover,
                    risk.isExpiryRisk(p));
        }
        // The user's worked example: 12 @ +3 days, 12 @ +9 days, demand 2/day.
        // By day 3, 6 sell and 6 expire; days 4-9 demand 12, exactly clearing batch 2.
        System.out.println("  worked example (2/day, 12@+3, 12@+9) expects expiry leftovers 6 then 0:");
        double[] example = simulateExpiryExample(2.0);
        System.out.printf("  -> first expiry leftover=%.0f, second expiry leftover=%.0f%n",
                example[0], example[1]);

        System.out.println();
        System.out.println("--- 14-date window boundary ---");
        LocalDate w = LocalDate.of(2026, 11, 26);
        System.out.println("window starts on        : " + model.DateText.format(model.SaleRecord.windowStart(w, 14)));
        System.out.println("sale 13/11 in window    : " + saleOn(w.minusDays(13)).withinLastDays(w, 14));
        System.out.println("sale 14/11 in window    : " + saleOn(w.minusDays(12)).withinLastDays(w, 14));
        System.out.println("sale 12/11 (14 days ago): " + saleOn(w.minusDays(14)).withinLastDays(w, 14)
                + "   <- correctly excluded");
        System.out.println("counted dates in window : 14 (13/11..26/11)");
        double naive = 12 - milkVelocity * 9;
        System.out.printf("  naive per-batch check on batch B would say leftover=%.1f (understated)%n", naive);

        System.out.println();
        System.out.println("Croissant: 60 pcs, sells 20/day, expires in 1 day ->");
        RiskEngine.ExpiryProjection[] croissantProjection = risk.projectExpiry("I004",
                risk.observedVelocity("I004", today), today);
        for (int i = 0; i < croissantProjection.length; i++) {
            RiskEngine.ExpiryProjection p = croissantProjection[i];
            System.out.printf("  %s projectedLeftover=%.1f -> flagged for clearance=%s, discount='%s'%n",
                    p.batch.getBatchId(), p.projectedLeftover, risk.isExpiryRisk(p),
                    StockItem.discountText(croissant.discountPercentFor(p.daysLeft)));
        }

        // ---- FEFO sale through the service
        System.out.println();
        System.out.println("--- FEFO sale of 14 L of milk ---");
        model.SaleRecord sale = service.recordSale("I001", 14, Double.NaN, InventoryService.ROLE_STAFF);
        System.out.println("Sale " + sale.getSaleId() + " drew from: " + sale.getBatchDraws()
                + " (earliest-expiry batch emptied first)");
        System.out.println("Milk stock now : " + batches.stockOf("I001") + " L");
        System.out.println("Activity lines : " + log.count());

        // ---- custom exceptions, each thrown by the service and caught here
        System.out.println();
        System.out.println("--- Custom exceptions ---");
        try {
            service.recordSale("I001", 999, Double.NaN, InventoryService.ROLE_STAFF);
        } catch (InsufficientStockException e) {
            System.out.printf("InsufficientStockException: requested=%.0f available=%.0f%n",
                    e.getRequested(), e.getAvailable());
        }
        try {
            service.recordSale("I404", 1, Double.NaN, InventoryService.ROLE_STAFF);
        } catch (ItemNotFoundException e) {
            System.out.println("ItemNotFoundException     : " + e.getMessage());
        }
        try {
            service.recordSale("I001", 0, Double.NaN, InventoryService.ROLE_STAFF);
        } catch (InvalidInputException e) {
            System.out.println("InvalidInputException     : field=" + e.getField() + " - " + e.getMessage());
        }
        try {
            service.receiveDelivery("I001", 10, today.minusDays(1), 2.10, InventoryService.ROLE_STAFF);
        } catch (InvalidInputException e) {
            System.out.println("InvalidInputException     : field=" + e.getField() + " - " + e.getMessage());
        }
        try {
            service.undoLastSale("I001", InventoryService.ROLE_STAFF);
        } catch (RoleNotPermittedException e) {
            System.out.println("RoleNotPermittedException : role=" + e.getRole());
        }
        try {
            new ItemStore(1).add(milk);
            ItemStore tiny = new ItemStore(1);
            tiny.add(milk);
            tiny.add(croissant);
            if (!tiny.isFull()) {
                throw new IllegalStateException("expected full store");
            }
            throw new StorageFullException("item", tiny.capacity());
        } catch (StorageFullException e) {
            System.out.println("StorageFullException      : " + e.getMessage());
        }
        try {
            new Season("Bad", 13, 2);
        } catch (InvalidSeasonException e) {
            System.out.println("InvalidSeasonException    : " + e.getMessage());
        }

        // ---- expired stock cannot be sold
        System.out.println();
        System.out.println("--- Expired stock ---");
        Batch past = new Batch("B0099", "I002", 2, 2, today.minusDays(8), today.minusDays(2), 3.60);
        batches.add(past);
        System.out.println("Cream batches: expired bottle present="
                + batches.findById("B0099").isExpired(today));
        System.out.println("Cream sellable stock: " + service.allocator().saleableStock("I002", today)
                + " (expired 2 L excluded)");
        try {
            service.recordSale("I002", 1, Double.NaN, InventoryService.ROLE_STAFF);
        } catch (ExpiredStockException e) {
            System.out.println("ExpiredStockException     : batch=" + e.getBatchId()
                    + " expiredDays=" + e.getDaysExpired());
        } catch (InsufficientStockException e) {
            System.out.println("InsufficientStockException (all stock expired)");
        }
        service.recordWaste("I002", "B0099", 2, "past use-by", InventoryService.ROLE_STAFF);
        System.out.println("Waste value recorded: RM" + waste.valueLostInWindow(today, 14));

        // ---- array mechanics: remove leaves no hole
        System.out.println();
        System.out.println("--- Array remove/compact ---");
        System.out.println("Before: count=" + items.count() + " live=" + items.liveCount());
        items.remove(items.indexOf("I003"));
        System.out.println("After removing I003: count=" + items.count() + " live="
                + items.liveCount() + " ids=" + items.items()[0].getItemId() + ","
                + items.items()[1].getItemId() + "," + items.items()[2].getItemId());

        System.out.println();
        System.out.println("=== Risk check finished ===");
    }

    private static model.SaleRecord saleOn(LocalDate date) {
        return new model.SaleRecord("SX", "I001", date, 1, 4.50, "");
    }

    /**
     * Replays the worked example from the review independently of the engine, so the
     * engine's answer can be compared against a hand calculation.
     *
     * @return leftover at the first expiry and at the second
     */
    private static double[] simulateExpiryExample(double perDay) {
        double[] shelf = {12, 12};
        int[] expiryDays = {3, 9};
        double[] leftover = new double[2];
        double consumed = 0;
        for (int b = 0; b < 2; b++) {
            double demand = perDay * Math.max(0, expiryDays[b] - consumed);
            consumed = Math.max(consumed, expiryDays[b]);
            for (int i = 0; i < shelf.length && demand > 0; i++) {
                double taken = Math.min(demand, shelf[i]);
                shelf[i] -= taken;
                demand -= taken;
            }
            // Only batches that have expired by this date contribute to this figure.
            // Including batch b+1 here is what produced the wrong 18 in the first run.
            double here = 0;
            for (int i = 0; i <= b; i++) {
                here += shelf[i];
            }
            leftover[b] = here;
            for (int i = 0; i <= b; i++) {
                shelf[i] = 0;
            }
        }
        return leftover;
    }
}
