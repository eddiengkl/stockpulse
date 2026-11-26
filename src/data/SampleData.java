package data;

import java.time.LocalDate;

import exception.DataFileException;
import exception.InvalidSeasonException;
import exception.StorageFullException;
import model.Batch;
import model.LongLifeItem;
import model.PerishableItem;
import model.SaleRecord;
import model.Season;
import model.Settings;
import model.StockItem;
import model.NumberText;
import service.ActivityLogStore;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.SeasonStore;
import store.WasteStore;

/**
 * Builds the sample café dataset used the first time StockPulse runs.
 *
 * <p>Written as real model objects and pushed through {@link DataStore#saveAll}, not as
 * hand-authored text. That way the seed data exercises the same writer the program uses
 * later, and a change to a data format cannot leave the sample data unreadable.</p>
 *
 * <p>This runs only when no data files exist. It never overwrites an existing file:
 * {@link DataStore#isFirstRun()} gates the call.</p>
 */
public final class SampleData {

    /** Numbers chosen so the demo shows a mix of safe, tight and already-expired stock. */
    private SampleData() {
    }

    /**
     * Fills the stores with the sample dataset and saves it.
     *
     * @return the date the sample sales history ends on, so the caller can explain it
     */
    public static LocalDate seed(DataStore store, ItemStore items, BatchStore batches,
                                 SaleStore sales, WasteStore waste, SeasonStore seasons,
                                 ActivityLogStore activity, Settings settings)
            throws DataFileException, StorageFullException, InvalidSeasonException {
        LocalDate today = settings.effectiveDate();

        // ---- seasons
        Season dry = new Season("Dry", 5, 9);
        Season rainy = new Season("Rainy", 10, 2);   // wraps across the new year
        seasons.add(dry);
        seasons.add(rainy);

        // ---- items: perishable and long-life, so polymorphism has both cases
        StockItem milk = new PerishableItem("I001", "Fresh Milk 1L", "Dairy", "L",
                4.50, 2.10, 7, 12, "Creamery Sdn Bhd", 2);
        StockItem cream = new PerishableItem("I002", "Whipping Cream 1L", "Dairy", "L",
                7.20, 3.60, 5, 6, "Creamery Sdn Bhd", 2);
        StockItem croissant = new PerishableItem("I003", "Butter Croissant", "Bakery", "pcs",
                3.80, 1.40, 2, 20, "Golden Crust Bakery", 1);
        StockItem beans = new PerishableItem("I004", "Coffee Beans 1kg", "Coffee", "bag",
                58.00, 32.00, 30, 4, "Highland Roasters", 4);
        StockItem syrup = new LongLifeItem("I005", "Iced Syrup - Lychee", "Syrup", "bottle",
                18.00, 9.00, 365, 6, "Sweetline Supplies", 5);
        StockItem cups = new LongLifeItem("I006", "Paper Cup 12oz", "Packaging", "pack",
                12.00, 6.50, 730, 10, "PackRight", 3);

        // Syrup sells much faster in the dry season; the multiplier lives on the item.
        syrup.setSeason(dry);
        syrup.setSeasonalMultiplier(1.35);

        items.add(milk);
        items.add(cream);
        items.add(croissant);
        items.add(beans);
        items.add(syrup);
        items.add(cups);

        // ---- batches. Deliberately varied:
        //   I001 has two batches, one expiring soon -> expiry risk after projecting demand
        //   I002 has an expired batch -> appears as waste-worthy, cannot be sold
        //   I003 is nearly out -> stockout risk
        batches.add(new Batch("B0001", "I001", 12, 12, today.minusDays(2),
                today.plusDays(5), 2.10));
        batches.add(new Batch("B0002", "I001", 12, 12, today.minusDays(1),
                today.plusDays(11), 2.10));
        batches.add(new Batch("B0003", "I002", 6, 6, today.minusDays(12),
                today.minusDays(2), 3.60));   // already expired: waste, never sold
        batches.add(new Batch("B0004", "I003", 20, 4, today.minusDays(1),
                today.plusDays(1), 1.40));
        batches.add(new Batch("B0005", "I004", 4, 4, today.minusDays(6),
                today.plusDays(24), 32.00));
        batches.add(new Batch("B0006", "I005", 6, 5, today.minusDays(20),
                null, 9.00));                  // long life: no expiry date
        batches.add(new Batch("B0007", "I006", 10, 8, today.minusDays(15),
                null, 6.50));

        // ---- 14 days of sales history, so the velocity window has real data.
        // Each row is one item's units per day, oldest first.
        double[] milkDaily = {5, 5, 0, 6, 5, 4, 5, 5, 6, 5, 0, 5, 5, 5};
        double[] croissantDaily = {18, 20, 22, 20, 19, 20, 21, 20, 20, 22, 19, 20, 20, 20};
        double[] beansDaily = {0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0};
        double[] syrupDaily = {1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1};

        int saleNumber = 0;
        for (int d = 13; d >= 0; d--) {
            int index = 13 - d;
            LocalDate date = today.minusDays(d);
            // The draws text records the batch each historical sale came from and the
            // quantity it took, so a reloaded sale reads the same as a live one.
            saleNumber = addSale(sales, saleNumber, "I001", date, milkDaily[index], 4.50,
                    drawText("B0001", milkDaily[index]));
            saleNumber = addSale(sales, saleNumber, "I003", date, croissantDaily[index], 3.80,
                    drawText("B0004", croissantDaily[index]));
            saleNumber = addSale(sales, saleNumber, "I004", date, beansDaily[index], 58.00,
                    drawText("B0005", beansDaily[index]));
            saleNumber = addSale(sales, saleNumber, "I005", date, syrupDaily[index], 18.00,
                    drawText("B0006", syrupDaily[index]));
        }

        // ---- a little settings customisation, so settings.txt is not all defaults
        settings.setActiveSeasonName("Dry");
        settings.setSalesWindowDays(Settings.DEFAULT_WINDOW_DAYS);
        settings.setSafetyMarginDays(Settings.DEFAULT_SAFETY_MARGIN_DAYS);
        settings.setReorderHorizonDays(Settings.DEFAULT_REORDER_HORIZON_DAYS);

        activity.add("System", "SEED",
                "Created the sample café dataset (" + items.count() + " items, "
                        + batches.count() + " batches, " + sales.count() + " sales)");

        // Seeding writes the same seven files as any other save, so it can end the same
        // way. A first run that committed and left its marker behind must not look like a
        // first run that finished: the caller would then load a folder that start-up would
        // refuse on the next attempt. The return value is reported, not ignored.
        DataStore.CleanupIncomplete unfinished =
                store.saveAll(items, batches, sales, waste, seasons, activity);
        if (unfinished != null) {
            throw unfinished.reason();
        }
        return today;
    }

    /**
     * Adds one sale line, skipping zero-quantity days because the model requires a
     * positive quantity. The zero days still count in the velocity divisor.
     */
    private static int addSale(SaleStore sales, int saleNumber, String itemId, LocalDate date,
                               double quantity, double unitPrice, String draws) {
        if (quantity <= 0) {
            return saleNumber;
        }
        saleNumber++;
        sales.add(new SaleRecord("S" + pad(saleNumber, 4), itemId, date, quantity, unitPrice,
                draws));
        return saleNumber;
    }

    /** "B0001:5" - the batch id and the quantity this sale took from it. */
    private static String drawText(String batchId, double quantity) {
        return batchId + ":" + NumberText.quantity(quantity);
    }

    private static String pad(int value, int width) {
        String text = String.valueOf(value);
        while (text.length() < width) {
            text = "0" + text;
        }
        return text;
    }
}
