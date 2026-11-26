package model;

import java.time.LocalDate;

/**
 * Stock that keeps: syrups, dry goods, cups, long-life cartons of juice.
 *
 * <p>It can still run out (that is the stockout rule) but it cannot expire, so it
 * answers every expiry question with "never". This is the second arm of the
 * hierarchy: the same method calls, opposite behaviour.</p>
 */
public class LongLifeItem extends StockItem {

    public LongLifeItem(String itemId, String name, String category, String unit,
                        double sellingPrice, double unitCost, int shelfLifeDays,
                        int packSize, String supplierName, int leadTimeDays) {
        super(itemId, name, category, unit, sellingPrice, unitCost, shelfLifeDays,
                packSize, supplierName, leadTimeDays);
    }

    @Override
    public String itemType() {
        return TYPE_LONGLIFE;
    }

    @Override
    public boolean canExpire() {
        return false;
    }

    /** Never expires, so the earliest expiry is "infinitely far away". Batch dates are ignored. */
    @Override
    public int daysUntilExpiry(long[] batchExpiryDates, LocalDate today) {
        return Integer.MAX_VALUE;
    }

    /** Nothing to clear out, so no discount applies at all (0%, not "not configured"). */
    @Override
    public double discountPercentFor(int daysLeft) {
        return 0.0;
    }

    @Override
    public String describeStock(double totalQty, int batchCount, int daysLeft) {
        if (batchCount == 0) {
            return "out of stock";
        }
        // describeQuantity pluralises the unit: "5 bottles", not "5 bottle".
        return describeQuantity(totalQty) + " in " + batchCount
                + " batch" + (batchCount == 1 ? "" : "es") + ", no expiry";
    }
}
