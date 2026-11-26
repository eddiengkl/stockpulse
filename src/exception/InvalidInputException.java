package exception;

/**
 * Thrown when input from the user is not usable: a quantity of zero, a date in the
 * wrong format, a negative price, a name containing a pipe.
 *
 * <p>The model itself throws {@link IllegalArgumentException} for the same field rules;
 * this exception is created at the service boundary so the API can answer 400 instead
 * of reporting a programming error.</p>
 */
public class InvalidInputException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String field;

    /**
     * @param field   which input was wrong, e.g. "qty" - lets the UI highlight it
     * @param message what is wrong and what is accepted
     */
    public InvalidInputException(String field, String message) {
        super(message);
        this.field = field;
    }

    public InvalidInputException(String field, String message, Throwable cause) {
        super(message, cause);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
