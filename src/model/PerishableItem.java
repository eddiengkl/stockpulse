package model;

import java.time.LocalDate;

/**
 * Stock that goes off: milk, cream, pastries, fruit.
 *
 * <p>Every question about expiry is answered from the item's batches, because two
 * deliveries of milk on different days do not share an expiry date.</p>
 */
public class PerishableItem extends StockItem {

    public PerishableItem(String itemId, String name, String category, String unit,
                          double sellingPrice, double unitCost, int shelfLifeDays,
                          int packSize, String supplierName, int leadTimeDays) {
        super(itemId, name, category, unit, sellingPrice, unitCost, shelfLifeDays,
                packSize, supplierName, leadTimeDays);
    }

    @Override
    public String itemType() {
        return TYPE_PERISHABLE;
    }

    @Override
    public boolean canExpire() {
        return true;
    }

    /**
     * Earliest expiry among the batches. Returns {@link Integer#MAX_VALUE} when no
     * batch carries an expiry date, which keeps comparisons in the risk engine simple.
     */
    @Override
    public int daysUntilExpiry(long[] batchExpiryDates, LocalDate today) {
        // Each long is a LocalDate.toEpochDay() value for one batch's expiry date.
        long earliest = Long.MAX_VALUE;
        for (long epochDay : batchExpiryDates) {
            if (epochDay < earliest) {
                earliest = epochDay;
            }
        }
        if (earliest == Long.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        long days = earliest - today.toEpochDay();
        return (days > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) days;
    }

    /**
     * No discount rule is configured for the MVP.
     *
     * <p>The Phase 1 discussion mentioned fixed percentages, but that rule was not
     * approved, so no ladder is hardcoded. This returns
     * {@link #DISCOUNT_NOT_CONFIGURED} rather than 0.0 so the UI shows "not
     * configured" instead of "0%", which would look like a deliberate recommendation.
     * The risk engine still reports how much stock is projected to be left over, and
     * that leftover is what gets flagged for clearance. Clearance ideas come later from
     * the AI advisor.</p>
     *
     * <p>Expired batches never reach this method: they are waste, not clearance.</p>
     */
    @Override
    public double discountPercentFor(int daysLeft) {
        return DISCOUNT_NOT_CONFIGURED;
    }

    @Override
    public String describeStock(double totalQty, int batchCount, int daysLeft) {
        if (batchCount == 0) {
            return "out of stock";
        }
        // describeQuantity pluralises the unit: "24 L" stays "24 L", "5 pc" does not
        // become "5 pcs" by accident - it already is "pcs".
        String qty = describeQuantity(totalQty);
        String batchWord = batchCount + " batch" + (batchCount == 1 ? "" : "es");
        if (daysLeft == Integer.MAX_VALUE) {
            return qty + " in " + batchWord + ", no expiry recorded";
        }
        if (daysLeft < 0) {
            return qty + " in " + batchWord + ", expired "
                    + Math.abs(daysLeft) + " day" + (daysLeft == -1 ? "" : "s") + " ago";
        }
        return qty + " in " + batchWord + ", oldest "
                + daysLeft + " day" + (daysLeft == 1 ? "" : "s") + " left";
    }
}
