package exception;

/**
 * Thrown when someone tries to sell or discount stock whose expiry date has passed.
 *
 * <p>Expired stock leaves the shelf through a waste record, never through a sale. This
 * is a real business rule, so it is its own custom exception rather than a generic
 * invalid-input error: the handler can point the user at the waste action.</p>
 */
public class ExpiredStockException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String batchId;
    private final int daysExpired;

    public ExpiredStockException(String batchId, String itemName, int daysExpired) {
        super("Batch " + batchId + " of " + itemName + " expired "
                + (daysExpired <= 0 ? "today" : daysExpired + " day" + (daysExpired == 1 ? "" : "s") + " ago")
                + ". Record it as waste instead of selling it.");
        this.batchId = batchId;
        this.daysExpired = daysExpired;
    }

    public String getBatchId() {
        return batchId;
    }

    public int getDaysExpired() {
        return daysExpired;
    }
}
