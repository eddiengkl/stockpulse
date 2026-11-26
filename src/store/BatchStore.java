package store;

import model.Batch;

/**
 * The batch table: a plain array of {@link Batch} plus a counter.
 *
 * <p>Batches belong to items through {@code itemId}. This store knows how to answer
 * "which batches does this item have" and "in which order should they be sold", which
 * is always soonest-expiry first.</p>
 */
public class BatchStore {

    public static final int DEFAULT_CAPACITY = 800;

    private final Batch[] batches;
    private int count;

    public BatchStore() {
        this(DEFAULT_CAPACITY);
    }

    public BatchStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Batch store capacity must be at least 1.");
        }
        this.batches = new Batch[capacity];
        this.count = 0;
    }

    // ------------------------------------------------------------------ array

    public Batch[] batches() {
        return batches;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return batches.length;
    }

    public boolean isFull() {
        return count >= batches.length;
    }

    /** @return false when the array is full */
    public boolean add(Batch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("Cannot add a null batch.");
        }
        if (isFull()) {
            return false;
        }
        batches[count] = batch;
        count++;
        return true;
    }

    // -------------------------------------------------------------- reading

    public Batch findById(String batchId) {
        if (batchId == null) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getBatchId().equalsIgnoreCase(batchId.trim())) {
                return batch;
            }
        }
        return null;
    }

    public int indexOf(String batchId) {
        if (batchId == null) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getBatchId().equalsIgnoreCase(batchId.trim())) {
                return i;
            }
        }
        return -1;
    }

    /** Indexes of every batch belonging to an item, in array order. */
    public int[] indexesForItem(String itemId) {
        int[] found = new int[count];
        int foundCount = 0;
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getItemId().equalsIgnoreCase(itemId)) {
                found[foundCount++] = i;
            }
        }
        int[] result = new int[foundCount];
        System.arraycopy(found, 0, result, 0, foundCount);
        return result;
    }

    /** Total quantity of an item still on the shelf across all its batches. */
    public double stockOf(String itemId) {
        double total = 0;
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getItemId().equalsIgnoreCase(itemId)) {
                total += batch.getQtyRemaining();
            }
        }
        return total;
    }

    /** How many batches of this item still hold stock. */
    public int liveBatchCount(String itemId) {
        int live = 0;
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getItemId().equalsIgnoreCase(itemId) && !batch.isDepleted()) {
                live++;
            }
        }
        return live;
    }

    /**
     * Expiry dates of an item's non-depleted batches, as epoch days, duplicating a date
     * once per batch. Passed to {@code StockItem.daysUntilExpiry} so the polymorphic
     * call has real data without the model needing to know about stores.
     */
    public long[] expiryEpochDaysFor(String itemId) {
        long[] dates = new long[count];
        int dateCount = 0;
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getItemId().equalsIgnoreCase(itemId)
                    && !batch.isDepleted() && batch.getExpiryDate() != null) {
                dates[dateCount++] = batch.getExpiryDate().toEpochDay();
            }
        }
        long[] result = new long[dateCount];
        System.arraycopy(dates, 0, result, 0, dateCount);
        return result;
    }

    /** Next free batch id, e.g. "B0042", based on the highest id currently stored. */
    public String nextBatchId() {
        int highest = 0;
        for (int i = 0; i < count; i++) {
            Batch batch = batches[i];
            if (batch != null && batch.getBatchId().length() > 1) {
                int number = parseTrailingNumber(batch.getBatchId());
                if (number > highest) {
                    highest = number;
                }
            }
        }
        return "B" + pad(highest + 1, 4);
    }

    // --------------------------------------------------------------- removing

    /**
     * Removes the batch at {@code index} and re-packs the array.
     *
     * @throws IllegalArgumentException when the index is out of range
     */
    public void remove(int index) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("Cannot remove batch at index " + index
                    + "; the store holds " + count + " records.");
        }
        batches[index] = null;
        compact();
    }

    /** Non-null cells in order, new counter value at the end. */
    public void compact() {
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (batches[read] != null) {
                batches[write] = batches[read];
                if (write != read) {
                    batches[read] = null;
                }
                write++;
            }
        }
        for (int i = write; i < count; i++) {
            batches[i] = null;
        }
        count = write;
    }

    public void clear() {
        for (int i = 0; i < batches.length; i++) {
            batches[i] = null;
        }
        count = 0;
    }

    // ---------------------------------------------------------------- helpers

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
