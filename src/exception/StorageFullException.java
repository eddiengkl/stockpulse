package exception;

/**
 * Thrown when an array store has reached its fixed capacity.
 *
 * <p>The stores use plain arrays, so capacity is decided when the program starts. This
 * exception is the honest signal that the cap was reached, rather than silently
 * dropping the newest record.</p>
 */
public class StorageFullException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String storeName;
    private final int capacity;

    public StorageFullException(String storeName, int capacity) {
        super("The " + storeName + " store is full (" + capacity
                + " records). Archive old records in the data folder and restart.");
        this.storeName = storeName;
        this.capacity = capacity;
    }

    public String getStoreName() {
        return storeName;
    }

    public int getCapacity() {
        return capacity;
    }
}
