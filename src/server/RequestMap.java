package server;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import exception.InvalidInputException;
import model.DateText;
import model.NumberText;

/**
 * The fields of one request: the query string for GET, or the form-urlencoded body for
 * POST and PUT.
 *
 * <p>The agreed contract uses {@code key=value&key=value} rather than JSON bodies, so
 * that no JSON parser has to be written by hand just to read five numbers. Values are
 * still sent by the browser as a form post, which is the same encoding.</p>
 *
 * <p>Every accessor throws {@link InvalidInputException} naming the offending field, so
 * the API can answer 400 with a message that says which field was wrong instead of
 * "bad request".</p>
 */
public final class RequestMap {

    private final String[] keys;
    private final String[] values;
    private final int count;

    private RequestMap(String[] keys, String[] values, int count) {
        this.keys = keys;
        this.values = values;
        this.count = count;
    }

    /** An empty map, for a request with no body. */
    public static RequestMap empty() {
        return new RequestMap(new String[0], new String[0], 0);
    }

    /**
     * Parses {@code a=1&b=two}.
     *
     * <p>Repeated keys are rejected rather than silently letting the last one win: a
     * doubled field is a mistake on the caller's side and quietly choosing one value is
     * how surprising bugs get in.</p>
     */
    public static RequestMap parse(String text) throws InvalidInputException {
        if (text == null || text.isBlank()) {
            return empty();
        }
        String[] parts = text.split("&", -1);
        String[] keys = new String[parts.length];
        String[] values = new String[parts.length];
        int count = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            int equals = part.indexOf('=');
            String key = decode(equals < 0 ? part : part.substring(0, equals)).trim();
            String value = (equals < 0) ? "" : decode(part.substring(equals + 1));
            if (key.isEmpty()) {
                throw new InvalidInputException("request", "A field name is missing.");
            }
            for (int j = 0; j < count; j++) {
                if (keys[j].equalsIgnoreCase(key)) {
                    throw new InvalidInputException(key,
                            "The field '" + key + "' was sent more than once.");
                }
            }
            keys[count] = key;
            values[count] = value;
            count++;
        }
        return new RequestMap(keys, values, count);
    }

    /** The '+' convention of form posts turned back into spaces, then percent-decoded. */
    private static String decode(String text) throws InvalidInputException {
        try {
            return URLDecoder.decode(text.replace("+", " "), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            throw new InvalidInputException("request", "The request contained invalid text.", e);
        }
    }

    public int size() {
        return count;
    }

    public boolean has(String key) {
        return indexOf(key) >= 0;
    }

    /** The raw text, or null when absent. A present-but-empty field returns "". */
    public String get(String key) {
        int index = indexOf(key);
        return (index < 0) ? null : values[index];
    }

    /**
     * Text that must be present and non-blank.
     *
     * @throws InvalidInputException when the field is missing or blank
     */
    public String require(String key) throws InvalidInputException {
        String raw = get(key);
        if (raw == null) {
            throw new InvalidInputException(key, "The field '" + key + "' is required.");
        }
        if (raw.isBlank()) {
            throw new InvalidInputException(key, "The field '" + key + "' cannot be empty.");
        }
        return raw.trim();
    }

    /**
     * A required number, parsed without regard to the machine's locale.
     *
     * <p>The value model's parsers report a bad number with the unchecked
     * {@link IllegalArgumentException}, which is right for a model that should only ever
     * receive clean data. Here, at the input boundary, it is translated into the checked
     * {@link InvalidInputException} the API answers 400 with.</p>
     *
     * @throws InvalidInputException when missing, not a number, or infinite/NaN
     */
    public double requireDouble(String key) throws InvalidInputException {
        String raw = require(key);
        double value;
        try {
            value = NumberText.parseDouble(raw, key);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(key, e.getMessage(), e);
        }
        if (!Double.isFinite(value)) {
            throw new InvalidInputException(key, "The field '" + key + "' must be a normal number.");
        }
        return value;
    }

    /** A required whole number, translated from the model's unchecked exception. */
    public int requireInt(String key) throws InvalidInputException {
        String raw = require(key);
        try {
            return NumberText.parseInt(raw, key);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(key, e.getMessage(), e);
        }
    }

    /**
     * A required date written dd/MM/yyyy.
     *
     * @throws InvalidInputException when missing or in any other format
     */
    public java.time.LocalDate requireDate(String key) throws InvalidInputException {
        String raw = require(key);
        try {
            return DateText.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(key,
                    "The field '" + key + "' should be a date written dd/MM/yyyy but was '"
                            + raw + "'.", e);
        }
    }

    /** A date that may be absent: returns null when the field is missing or blank. */
    public java.time.LocalDate optionalDate(String key) throws InvalidInputException {
        String raw = get(key);
        if (raw == null || raw.isBlank() || raw.trim().equals("-")) {
            return null;
        }
        return requireDate(key);
    }

    private int indexOf(String key) {
        for (int i = 0; i < count; i++) {
            if (keys[i].equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }
}
