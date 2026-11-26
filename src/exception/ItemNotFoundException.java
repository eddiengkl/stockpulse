package exception;

/**
 * Thrown when a request names an item or batch id that does not exist.
 *
 * <p>A user-facing problem, so it is a checked custom exception rather than a
 * programming error: the API layer catches it and answers 404 with the message.</p>
 */
public class ItemNotFoundException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String lookedForId;

    public ItemNotFoundException(String lookedForId, String what) {
        super(what + " '" + lookedForId + "' was not found.");
        this.lookedForId = lookedForId;
    }

    /** Same, with the underlying cause kept for the log. */
    public ItemNotFoundException(String lookedForId, String what, Throwable cause) {
        super(what + " '" + lookedForId + "' was not found.", cause);
        this.lookedForId = lookedForId;
    }

    /** The id that could not be resolved, so the API can echo it back. */
    public String getLookedForId() {
        return lookedForId;
    }
}
