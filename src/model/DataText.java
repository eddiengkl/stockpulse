package model;

/**
 * Shared validation for text and number fields that are written into pipe-delimited
 * data files.
 *
 * <p>Every text field that reaches {@code toDataLine()} goes through
 * {@link #safeField}: a name containing a pipe or a line break would shift columns in
 * items.txt and make the file unreadable on the next start, so such text is rejected
 * at the point it is set rather than being written out and breaking the file.</p>
 */
public final class DataText {

    private DataText() {
    }

    /**
     * Trims a text field that will be saved to a data file.
     *
     * @param text      value supplied by the caller
     * @param fieldName human-readable name used in the error message
     * @return trimmed text
     * @throws IllegalArgumentException when the text is null, blank, or contains
     *                                  {@code '|'}, {@code '\n'} or {@code '\r'}
     */
    public static String safeField(String text, String fieldName) {
        if (text == null) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " is required but was empty.");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " is required but was empty.");
        }
        if (trimmed.indexOf('|') >= 0) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " cannot contain the '|' character: " + describe(trimmed));
        }
        if (trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " cannot contain a line break: " + describe(trimmed));
        }
        return trimmed;
    }

    /** @throws IllegalArgumentException when the value is negative or not finite */
    public static double nonNegative(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " must be a normal non-negative number but was "
                            + (Double.isFinite(value) ? NumberText.quantity(value) : String.valueOf(value))
                            + ".");
        }
        return value;
    }

    /** @throws IllegalArgumentException when the value is negative or NaN/Infinity */
    public static double quantity(double value, String fieldName) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " must be a real number but was " + value + ".");
        }
        return value;
    }

    /** @throws IllegalArgumentException when the value is negative */
    public static int nonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " cannot be negative but was " + value + ".");
        }
        return value;
    }

    /** @throws IllegalArgumentException when the value is below 1 */
    public static int atLeastOne(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    cap(fieldName) + " must be at least 1 but was " + value + ".");
        }
        return value;
    }

    private static String cap(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Shortens long values so an error message stays readable. */
    private static String describe(String text) {
        String single = text.replace('\n', ' ').replace('\r', ' ');
        return "'" + (single.length() > 40 ? single.substring(0, 40) + "..." : single) + "'";
    }
}
