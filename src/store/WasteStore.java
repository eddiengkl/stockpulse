package store;

import java.time.LocalDate;

import model.WasteRecord;

/**
 * The waste ledger: a plain array of {@link WasteRecord} plus a counter. Waste is kept
 * because it is the only honest record of money lost, and the advisor needs it to
 * explain recurring expiry problems.
 */
public class WasteStore {

    public static final int DEFAULT_CAPACITY = 2000;

    private final WasteRecord[] waste;
    private int count;

    public WasteStore() {
        this(DEFAULT_CAPACITY);
    }

    public WasteStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Waste store capacity must be at least 1.");
        }
        this.waste = new WasteRecord[capacity];
        this.count = 0;
    }

    public WasteRecord[] waste() {
        return waste;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return waste.length;
    }

    public boolean isFull() {
        return count >= waste.length;
    }

    /** @return false when the array is full */
    public boolean add(WasteRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("Cannot add a null waste record.");
        }
        if (isFull()) {
            return false;
        }
        waste[count] = record;
        count++;
        return true;
    }

    /** Total value thrown away in the window, in RM. */
    public double valueLostInWindow(LocalDate today, int days) {
        double total = 0;
        LocalDate from = today.minusDays(days);
        for (int i = 0; i < count; i++) {
            WasteRecord record = waste[i];
            if (record != null && !record.getDate().isBefore(from) && !record.getDate().isAfter(today)) {
                total += record.valueLost();
            }
        }
        return total;
    }

    /** Next free waste id, e.g. "W0007". */
    public String nextWasteId() {
        int highest = 0;
        for (int i = 0; i < count; i++) {
            WasteRecord record = waste[i];
            if (record != null) {
                int number = parseTrailingNumber(record.getWasteId());
                if (number > highest) {
                    highest = number;
                }
            }
        }
        return "W" + pad(highest + 1, 4);
    }

    public void clear() {
        for (int i = 0; i < waste.length; i++) {
            waste[i] = null;
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
