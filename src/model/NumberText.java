package model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Number text helpers. Money is written with two decimals, quantities without a
 * trailing ".0", and every conversion uses {@link java.util.Locale#ROOT} so a machine
 * set to a locale that writes "2,10" still saves "2.10" and reloads correctly.
 */
public final class NumberText {

    private NumberText() {
    }

    /** Two-decimal text, e.g. 2.10. */
    public static String money(double value) {
        return new BigDecimal(Double.toString(value)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Quantity text: whole numbers without decimals, otherwise up to two decimals. */
    public static String quantity(double value) {
        BigDecimal rounded = new BigDecimal(Double.toString(value)).setScale(2, RoundingMode.HALF_UP);
        if (rounded.stripTrailingZeros().scale() <= 0) {
            return String.valueOf(rounded.intValue());
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    /**
     * Parses a decimal written with a dot regardless of the OS locale.
     *
     * @throws IllegalArgumentException when the text is not a number
     */
    public static double parseDouble(String text, String fieldName) {
        String trimmed = (text == null) ? "" : text.trim();
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' should be a number but was '" + trimmed + "'.", e);
        }
    }

    /**
     * Parses a whole number written with a dot-free format.
     *
     * @throws IllegalArgumentException when the text is not an integer
     */
    public static int parseInt(String text, String fieldName) {
        String trimmed = (text == null) ? "" : text.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' should be a whole number but was '" + trimmed + "'.", e);
        }
    }
}
