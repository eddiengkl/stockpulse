package exception;

/**
 * Thrown when a sale or waste record asks for more stock than the batch or the item
 * actually holds.
 *
 * <p>Carries the numbers as well as the message so the UI can show "you have 6 L,
 * you asked for 10 L" without re-parsing the text.</p>
 */
public class InsufficientStockException extends Exception {

    private static final long serialVersionUID = 1L;

    private final double requested;
    private final double available;

    public InsufficientStockException(String itemName, double requested, double available) {
        super("Not enough " + itemName + ": " + trim(requested) + " requested but only "
                + trim(available) + " available.");
        this.requested = requested;
        this.available = available;
    }

    public double getRequested() {
        return requested;
    }

    public double getAvailable() {
        return available;
    }

    private static String trim(double value) {
        long whole = (long) value;
        return (whole == value) ? String.valueOf(whole) : String.valueOf(value);
    }
}
