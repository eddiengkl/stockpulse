package store;

import java.time.LocalDate;

import model.SaleRecord;

/**
 * The sales ledger: one flat array of {@link SaleRecord} plus a counter.
 *
 * <p>Sales are not nested per item. One array means one scan can answer "how much did
 * this item sell in the last N days", which is the only question the risk engine asks
 * of it.</p>
 */
public class SaleStore {

    public static final int DEFAULT_CAPACITY = 5000;

    private final SaleRecord[] sales;
    private int count;

    public SaleStore() {
        this(DEFAULT_CAPACITY);
    }

    public SaleStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Sale store capacity must be at least 1.");
        }
        this.sales = new SaleRecord[capacity];
        this.count = 0;
    }

    public SaleRecord[] sales() {
        return sales;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return sales.length;
    }

    public boolean isFull() {
        return count >= sales.length;
    }

    /** @return false when the array is full */
    public boolean add(SaleRecord sale) {
        if (sale == null) {
            throw new IllegalArgumentException("Cannot add a null sale record.");
        }
        if (isFull()) {
            return false;
        }
        sales[count] = sale;
        count++;
        return true;
    }

    /**
     * Units of one item sold in the window {@code [today - days, today]}, counting only
     * days that are inside the window. Zero-sale days are days with no record, so they
     * contribute nothing here; the divisor in the velocity rule accounts for them.
     */
    public double unitsSold(String itemId, LocalDate today, int days) {
        double total = 0;
        for (int i = 0; i < count; i++) {
            SaleRecord sale = sales[i];
            if (sale != null && sale.getItemId().equalsIgnoreCase(itemId)
                    && sale.withinLastDays(today, days)) {
                total += sale.getQuantity();
            }
        }
        return total;
    }

    /**
     * The date of the earliest sale on record for an item, or null when it has never
     * sold. Used to work out how many calendar days the item has been stocked and
     * selling, which is the velocity divisor.
     */
    public LocalDate firstSaleDate(String itemId) {
        LocalDate earliest = null;
        for (int i = 0; i < count; i++) {
            SaleRecord sale = sales[i];
            if (sale != null && sale.getItemId().equalsIgnoreCase(itemId)) {
                if (earliest == null || sale.getDate().isBefore(earliest)) {
                    earliest = sale.getDate();
                }
            }
        }
        return earliest;
    }

    /** Most recent {@code limit} sales for an item, newest first, for the item screen. */
    public SaleRecord[] recentForItem(String itemId, int limit) {
        SaleRecord[] buffer = new SaleRecord[Math.min(limit, count)];
        int found = 0;
        for (int i = count - 1; i >= 0 && found < buffer.length; i--) {
            SaleRecord sale = sales[i];
            if (sale != null && sale.getItemId().equalsIgnoreCase(itemId)) {
                buffer[found++] = sale;
            }
        }
        SaleRecord[] result = new SaleRecord[found];
        System.arraycopy(buffer, 0, result, 0, found);
        return result;
    }

    /** Revenue recorded in the window, in RM. */
    public double revenueInWindow(LocalDate today, int days) {
        double total = 0;
        for (int i = 0; i < count; i++) {
            SaleRecord sale = sales[i];
            if (sale != null && sale.withinLastDays(today, days)) {
                total += sale.revenue();
            }
        }
        return total;
    }

    /** Next free sale id, e.g. "S0913". */
    public String nextSaleId() {
        int highest = 0;
        for (int i = 0; i < count; i++) {
            SaleRecord sale = sales[i];
            if (sale != null) {
                int number = parseTrailingNumber(sale.getSaleId());
                if (number > highest) {
                    highest = number;
                }
            }
        }
        return "S" + pad(highest + 1, 4);
    }

    /** Removes the sale at {@code index} (used by "undo last sale") and re-packs. */
    public void remove(int index) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("Cannot remove sale at index " + index
                    + "; the store holds " + count + " records.");
        }
        sales[index] = null;
        compact();
    }

    /**
     * Removes the most recent sale for an item that was recorded on or after
     * {@code date}, so "undo" cannot reach back into history by accident.
     *
     * @return the removed record, or null when there is nothing to undo
     */
    public SaleRecord removeMostRecentForItem(String itemId, LocalDate date) {
        for (int i = count - 1; i >= 0; i--) {
            SaleRecord sale = sales[i];
            if (sale != null && sale.getItemId().equalsIgnoreCase(itemId)
                    && !sale.getDate().isBefore(date)) {
                sales[i] = null;
                compact();
                return sale;
            }
        }
        return null;
    }

    public void compact() {
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (sales[read] != null) {
                sales[write] = sales[read];
                if (write != read) {
                    sales[read] = null;
                }
                write++;
            }
        }
        for (int i = write; i < count; i++) {
            sales[i] = null;
        }
        count = write;
    }

    public void clear() {
        for (int i = 0; i < sales.length; i++) {
            sales[i] = null;
        }
        count = 0;
    }

    private static int parseTrailingNumber(String id) {
        int start = 0;
        while (start < id.length() && !Character.isDigit(id.charAt(start))) {
            start++;
        }
        if (start >= id.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(start));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String pad(int value, int width) {
        String text = String.valueOf(value);
        while (text.length() < width) {
            text = "0" + text;
        }
        return text;
    }
}
