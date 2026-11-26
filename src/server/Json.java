package server;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds JSON text by hand.
 *
 * <p>The project may not use external libraries, so this is a small writer rather than a
 * general-purpose serialiser. It has one job: turn Java values into valid JSON. All
 * output is escaped, so a supplier name containing a quote or a backslash cannot break
 * the response.</p>
 *
 * <p>Numbers are written through {@link BigDecimal#toPlainString()}, so a decimal point
 * is always a dot whatever the operating system's locale is. This kind of locale bug is
 * invisible on an English machine and appears as "4,50" on a machine set to Malay, which
 * is exactly the bug this project is meant to demonstrate awareness of.</p>
 */
public final class Json {

    private final StringBuilder out = new StringBuilder(256);

    public Json() {
    }

    /** Starts an object, inserting a separator when it follows another array element. */
    public Json beginObject() {
        separatorIfArrayElement();
        out.append('{');
        return this;
    }

    /** Ends an object, dropping a trailing comma if one was left behind. */
    public Json endObject() {
        trimTrailingComma();
        out.append('}');
        return this;
    }

    /** Starts an array, inserting a separator when it follows another array element. */
    public Json beginArray() {
        separatorIfArrayElement();
        out.append('[');
        return this;
    }

    /** Ends an array, dropping a trailing comma if one was left behind. */
    public Json endArray() {
        trimTrailingComma();
        out.append(']');
        return this;
    }

    /**
     * Optional separator, kept so call sites read like JSON.
     *
     * <p>Not required: {@link #name(String)} and the {@code begin*} methods insert a
     * separator whenever one is actually needed. Relying on a caller to remember every
     * comma is how a malformed response gets shipped - this class used to produce
     * {@code }{ between array elements because of exactly that.</p>
     */
    public Json comma() {
        return this;
    }

    /**
     * Writes {@code "key":}, inserting a comma first when the previous character is a
     * completed value. Calling it after a fresh {@code {} or {@code [} adds no comma.
     */
    public Json name(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("JSON key must not be blank.");
        }
        trimTrailingComma();
        char last = lastMeaningful();
        if (last != '{' && last != '[' && last != '\0') {
            out.append(',');
        }
        appendString(key);
        out.append(':');
        return this;
    }

    /** Writes a quoted, escaped string. Null becomes JSON null. */
    public Json value(String text) {
        separatorIfArrayElement();
        if (text == null) {
            out.append("null");
        } else {
            appendString(text);
        }
        return this;
    }

    public Json value(long number) {
        separatorIfArrayElement();
        out.append(number);
        return this;
    }

    public Json value(boolean flag) {
        separatorIfArrayElement();
        out.append(flag);
        return this;
    }

    /**
     * Writes a number with at most two decimal places and no trailing zeros.
     *
     * <p>Infinities and NaN are not valid JSON, so they are written as {@code null}. A
     * caller that needs to explain an infinite value sends a companion text field
     * (days of cover is reported as "never runs out" rather than an enormous number).</p>
     */
    public Json value(double number) {
        separatorIfArrayElement();
        out.append(numbers(number));
        return this;
    }

    /** Convenience: "key": null. */
    public Json putNull(String key) {
        return name(key).value(null);
    }

    /** Convenience: "key": "text". */
    public Json put(String key, String text) {
        return name(key).value(text);
    }

    /** Convenience: "key": number. */
    public Json put(String key, long number) {
        return name(key).value(number);
    }

    /** Convenience: "key": number. */
    public Json put(String key, double number) {
        return name(key).value(number);
    }

    /** Convenience: "key": true/false. */
    public Json put(String key, boolean flag) {
        return name(key).value(flag);
    }

    /**
     * Formats a number the same way everywhere: dot separator, at most two decimals,
     * no exponent, no trailing zeros, and {@code null} for non-finite values.
     */
    public static String numbers(double value) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        BigDecimal decimal = BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        // stripTrailingZeros turns 100.00 into 1E+2; toPlainString writes it back as 100.
        return decimal.toPlainString();
    }

    /** Escapes a string into the buffer, quotes included. */
    private void appendString(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Inserts a comma when the value about to be written is a second or later element of
     * the array we are currently inside.
     *
     * <p>The test is positional, not bookkeeping: the last meaningful character is the
     * closing bracket, quote or digit of the previous element. A {@code [} (empty array)
     * or {@code ,} needs nothing, and inside an object the last character is {@code :}
     * after a key, which also needs nothing.</p>
     */
    private void separatorIfArrayElement() {
        trimTrailingComma();
        char last = lastMeaningful();
        if (last == '\0' || last == '[' || last == '{' || last == ':' || last == ',') {
            return;
        }
        out.append(',');
    }

    /** Drops a comma that was appended but followed by nothing. */
    private void trimTrailingComma() {
        int i = out.length() - 1;
        while (i >= 0 && Character.isWhitespace(out.charAt(i))) {
            i--;
        }
        if (i >= 0 && out.charAt(i) == ',') {
            out.deleteCharAt(i);
        }
    }

    private char lastMeaningful() {
        for (int i = out.length() - 1; i >= 0; i--) {
            char c = out.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
