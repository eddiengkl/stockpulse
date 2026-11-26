package exception;

/**
 * Thrown when a season's name or month range is not usable, for example month 13, or
 * a start month equal to the end month.
 */
public class InvalidSeasonException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidSeasonException(String message) {
        super(message);
    }

    public InvalidSeasonException(String message, Throwable cause) {
        super(message, cause);
    }
}
