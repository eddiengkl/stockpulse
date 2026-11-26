package store;

import model.StockItem;

/**
 * The item table: a plain array of {@link StockItem} plus an integer counter.
 *
 * <p>No Java Collections Framework class is used to hold project objects - the array
 * and {@link #count} are the whole data structure. Because a sale, a delivery and a
 * risk report all need to look items up by id, that lookup is a linear scan, which is
 * fast enough for a few dozen café items and keeps the storage rule obvious.</p>
 *
 * <p>A "hole" is a null array cell left by {@link #remove(int)}. Methods that need
 * contiguous data call {@link #compact()}; the store stays usable without it.</p>
 */
public class ItemStore {

    public static final int DEFAULT_CAPACITY = 200;

    private final StockItem[] items;
    private int count;

    public ItemStore() {
        this(DEFAULT_CAPACITY);
    }

    public ItemStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Item store capacity must be at least 1.");
        }
        this.items = new StockItem[capacity];
        this.count = 0;
    }

    // ------------------------------------------------------------------ array

    /** The backing array. Cells at or beyond {@link #count()} may be null. */
    public StockItem[] items() {
        return items;
    }

    /** How many records were added (including any later removed, before compacting). */
    public int count() {
        return count;
    }

    public int capacity() {
        return items.length;
    }

    public boolean isFull() {
        return count >= items.length;
    }

    // ---------------------------------------------------------------- adding
    // Adding throws StorageFullException from the service layer, so this class only
    // supplies the mechanics.

    /**
     * Appends an item at the counter position.
     *
     * @return false when the array is full
     */
    public boolean add(StockItem item) {
        if (item == null) {
            throw new IllegalArgumentException("Cannot add a null item.");
        }
        if (isFull()) {
            return false;
        }
        items[count] = item;
        count++;
        return true;
    }

    // -------------------------------------------------------------- reading

    /**
     * Finds an item by id.
     *
     * @return the item, or null when no item has that id
     */
    public StockItem findById(String itemId) {
        if (itemId == null) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            StockItem item = items[i];
            if (item != null && item.getItemId().equalsIgnoreCase(itemId.trim())) {
                return item;
            }
        }
        return null;
    }

    /** @return the array index of the item, or -1 */
    public int indexOf(String itemId) {
        if (itemId == null) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            StockItem item = items[i];
            if (item != null && item.getItemId().equalsIgnoreCase(itemId.trim())) {
                return i;
            }
        }
        return -1;
    }

    public boolean containsId(String itemId) {
        return indexOf(itemId) >= 0;
    }

    /** Count of non-null cells, i.e. how many items really exist right now. */
    public int liveCount() {
        int live = 0;
        for (int i = 0; i < count; i++) {
            if (items[i] != null) {
                live++;
            }
        }
        return live;
    }

    // --------------------------------------------------------------- removing

    /**
     * Removes the item at {@code index} by leaving a hole and re-packing the array, so
     * the counter always matches the number of real items afterwards.
     *
     * @throws IllegalArgumentException when the index is out of range
     */
    public void remove(int index) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("Cannot remove item at index " + index
                    + "; the store holds " + count + " records.");
        }
        items[index] = null;
        compact();
    }

    /**
     * Shifts every non-null cell to the left and clears the tail, then shrinks the
     * counter to the number of real items. This is the array equivalent of
     * {@code List.remove} — done by hand because collections are not allowed here.
     */
    public void compact() {
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (items[read] != null) {
                items[write] = items[read];
                if (write != read) {
                    items[read] = null;
                }
                write++;
            }
        }
        for (int i = write; i < count; i++) {
            items[i] = null;
        }
        count = write;
    }

    /** Empties the store. Used when reloading from disk. */
    public void clear() {
        for (int i = 0; i < items.length; i++) {
            items[i] = null;
        }
        count = 0;
    }
}
