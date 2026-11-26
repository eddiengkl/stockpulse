package model;

import java.time.LocalDate;

/**
 * One recorded sale of one item on one date.
 *
 * <p>Sales are kept in a single flat array with an {@code itemId} field rather than as
 * an array per item: one counter, one scan for the 14-day window, and no nested
 * collections anywhere.</p>
 */
public class SaleRecord {

    private String saleId;      // e.g. "S0912"
    private String itemId;
    private LocalDate date;
    private double quantity;    // > 0
    private double unitPrice;   // price actually charged, which may be discounted, >= 0
    private String batchDraws;  // "B0039:6,B0041:4" - which batches the sale came from

    /**
     * @param saleId     unique sale id
     * @param itemId     item sold
     * @param date       business date of the sale
     * @param quantity   units sold, must be &gt; 0
     * @param unitPrice  unit price charged, must be &gt;= 0
     * @param batchDraws text description of the first-expired-first-out allocation
     * @throws IllegalArgumentException when a value does not meet the rules above
     */
    public SaleRecord(String saleId, String itemId, LocalDate date, double quantity,
                      double unitPrice, String batchDraws) {
        this.saleId = DataText.safeField(saleId, "sale id");
        this.itemId = DataText.safeField(itemId, "item id");
        if (date == null) {
            throw new IllegalArgumentException("Sale date is required.");
        }
        this.date = date;
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            throw new IllegalArgumentException(
                    "Sale quantity must be a real number above 0 but was "
                            + (Double.isFinite(quantity) ? NumberText.quantity(quantity) : String.valueOf(quantity))
                            + ".");
        }
        this.quantity = quantity;
        this.unitPrice = DataText.nonNegative(unitPrice, "sale unit price");
        this.batchDraws = (batchDraws == null) ? "" : batchDraws.trim();
    }

    /** Total money taken for this sale, in RM. */
    public double revenue() {
        return quantity * unitPrice;
    }

    /**
     * Splits {@code batchDraws} into the batch ids it names.
     *
     * <p>The text form is {@code "B0039:6,B0041:4"}. An empty array is returned when no
     * draws were recorded, so callers can tell "nothing recorded" from "one draw".</p>
     *
     * @return the entries, in the order written; empty array when nothing was recorded
     */
    public String[] drawEntries() {
        if (batchDraws.isEmpty()) {
            return new String[0];
        }
        return batchDraws.split(",", -1);
    }

    /**
     * The batch id part of one draw entry, e.g. {@code "B0039"} for {@code "B0039:6"}.
     *
     * @return the id, or the whole entry when it holds no colon
     */
    public static String drawBatchId(String entry) {
        int colon = entry.indexOf(':');
        return (colon < 0) ? entry.trim() : entry.substring(0, colon).trim();
    }

    /**
     * The quantity part of one draw entry, e.g. 6 for {@code "B0039:6"}.
     *
     * @return the quantity, or 0 when the entry holds no usable number
     */
    public static double drawQuantity(String entry) {
        int colon = entry.indexOf(':');
        if (colon < 0) {
            return 0;
        }
        try {
            return Double.parseDouble(entry.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * True when the sale date falls inside a window of exactly {@code days} calendar
     * dates ending on {@code today}.
     *
     * <p>For example {@code days = 14} on 26/11/2026 covers 13/11 to 26/11 inclusive -
     * fourteen dates, not fifteen. The offset is therefore {@code days - 1}, and a sale
     * dated 12/11 (14 days before today) is excluded. The divisor used by the velocity
     * rule must describe the same set of dates, so both use
     * {@link #windowStart(LocalDate, int)}.</p>
     */
    public boolean withinLastDays(LocalDate today, int days) {
        LocalDate from = windowStart(today, days);
        return !date.isBefore(from) && !date.isAfter(today);
    }

    /**
     * First date of the window of {@code days} calendar dates ending on {@code today}.
     *
     * @return {@code today - (days - 1)}, i.e. {@code today} itself when {@code days} is 1
     */
    public static LocalDate windowStart(LocalDate today, int days) {
        int span = Math.max(1, days);
        return today.minusDays(span - 1L);
    }

    public String toDataLine() {
        return saleId + "|" + itemId + "|" + DateText.format(date) + "|"
                + NumberText.quantity(quantity) + "|" + NumberText.money(unitPrice) + "|"
                + batchDraws;
    }

    public String getSaleId() { return saleId; }
    public String getItemId() { return itemId; }
    public LocalDate getDate() { return date; }
    public double getQuantity() { return quantity; }
    public double getUnitPrice() { return unitPrice; }
    public String getBatchDraws() { return batchDraws; }
}
