package ai;

/**
 * A deliberately small JSON reader, just enough to read one reply from the AI service.
 *
 * <p>The project has no libraries, so a parser has to be written by hand. That is a
 * liability: a general parser is a large, subtle piece of code, and a bug in one would
 * quietly change a value the user is shown. This one is therefore kept as small as the
 * job allows, and - more importantly - <strong>fails closed</strong>. On anything it does
 * not understand it throws {@link Malformed} instead of guessing, so a reply that is not
 * the documented shape is reported as unusable rather than half-read.</p>
 *
 * <p>What it supports is the whole of JSON's grammar: objects, arrays, strings, numbers,
 * {@code true}, {@code false} and {@code null}. What it does not support is formatting
 * hints and duplicate keys - a repeated key is an error here, because two values for one
 * key means the reply is not something we can describe honestly.</p>
 *
 * <p>Traversal is through the {@link Value} accessors, which all throw {@link Malformed}
 * rather than returning null. That is what lets the advisor read
 * {@code candidates[0].content.parts[0].text} in one line and still report a clean error
 * when a model returns a safety block instead.</p>
 */
public final class MiniJson {

    /** Raised when the text is not valid JSON, or is not the shape the caller asked for. */
    public static class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        public Malformed(String message) {
            super(message);
        }
    }

    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    /** Parses a whole document. Trailing non-whitespace text is an error. */
    public static Value parse(String text) throws Malformed {
        if (text == null) {
            throw new Malformed("the reply was empty");
        }
        MiniJson reader = new MiniJson(text);
        Value value = reader.readValue();
        reader.skipWhitespace();
        if (reader.at != text.length()) {
            throw new Malformed("the reply had unexpected text after the end of the JSON");
        }
        return value;
    }

    // --------------------------------------------------------------- parsing

    private Value readValue() throws Malformed {
        skipWhitespace();
        if (at >= text.length()) {
            throw new Malformed("the reply ended before the JSON was complete");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return new Value(Value.STRING, readString(), null, null);
            case 't':
                expectWord("true");
                return new Value(Value.TRUE, null, null, null);
            case 'f':
                expectWord("false");
                return new Value(Value.FALSE, null, null, null);
            case 'n':
                expectWord("null");
                return new Value(Value.NULL, null, null, null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return new Value(Value.NUMBER, readNumber(), null, null);
                }
                throw new Malformed("the reply contained an unexpected character '"
                        + (Character.isISOControl(c) ? "?" : String.valueOf(c)) + "'");
        }
    }

    private Value readObject() throws Malformed {
        at++;   // the '{'
        String[] keys = new String[8];
        Value[] values = new Value[8];
        int count = 0;
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return new Value(Value.OBJECT, null, keys, values, count);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new Malformed("a JSON object key must be a string");
            }
            String key = readString();
            for (int i = 0; i < count; i++) {
                if (keys[i].equals(key)) {
                    throw new Malformed("the reply repeated the key '" + key + "'");
                }
            }
            skipWhitespace();
            if (peek() != ':') {
                throw new Malformed("a JSON object key must be followed by ':'");
            }
            at++;
            Value value = readValue();
            if (count == keys.length) {
                keys = grow(keys);
                values = grow(values);
            }
            keys[count] = key;
            values[count] = value;
            count++;
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                at++;
                continue;
            }
            if (next == '}') {
                at++;
                return new Value(Value.OBJECT, null, keys, values, count);
            }
            throw new Malformed("a JSON object must continue with ',' or '}'");
        }
    }

    private Value readArray() throws Malformed {
        at++;   // the '['
        Value[] items = new Value[8];
        int count = 0;
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return new Value(Value.ARRAY, null, null, items, count);
        }
        while (true) {
            Value value = readValue();
            if (count == items.length) {
                items = grow(items);
            }
            items[count] = value;
            count++;
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                at++;
                continue;
            }
            if (next == ']') {
                at++;
                return new Value(Value.ARRAY, null, null, items, count);
            }
            throw new Malformed("a JSON array must continue with ',' or ']'");
        }
    }

    /** Reads a quoted string, handling the escapes JSON defines and no others. */
    private String readString() throws Malformed {
        at++;   // the opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw new Malformed("a string in the reply was not closed");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw new Malformed("a string in the reply held a raw control character");
                }
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw new Malformed("a string in the reply ended inside an escape");
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readHexChar());
                default -> throw new Malformed("the reply used an invalid escape '\\"
                        + escaped + "'");
            }
        }
    }

    private char readHexChar() throws Malformed {
        if (at + 4 > text.length()) {
            throw new Malformed("the reply ended inside a \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(at++), 16);
            if (digit < 0) {
                throw new Malformed("the reply used a \\u escape that is not hexadecimal");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    /** Reads a number and keeps its original text, so nothing is lost to rounding. */
    private String readNumber() throws Malformed {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        if (!isDigit(peek())) {
            throw new Malformed("a number in the reply had no digits");
        }
        while (isDigit(peek())) {
            at++;
        }
        if (peek() == '.') {
            at++;
            if (!isDigit(peek())) {
                throw new Malformed("a number in the reply ended after its decimal point");
            }
            while (isDigit(peek())) {
                at++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            at++;
            if (peek() == '+' || peek() == '-') {
                at++;
            }
            if (!isDigit(peek())) {
                throw new Malformed("a number in the reply had an empty exponent");
            }
            while (isDigit(peek())) {
                at++;
            }
        }
        return text.substring(start, at);
    }

    private void expectWord(String word) throws Malformed {
        if (!text.startsWith(word, at)) {
            throw new Malformed("the reply contained an unexpected word where '" + word
                    + "' was expected");
        }
        at += word.length();
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipWhitespace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            at++;
        }
    }

    private static String[] grow(String[] array) {
        String[] bigger = new String[array.length * 2];
        for (int i = 0; i < array.length; i++) {
            bigger[i] = array[i];
        }
        return bigger;
    }

    private static Value[] grow(Value[] array) {
        Value[] bigger = new Value[array.length * 2];
        for (int i = 0; i < array.length; i++) {
            bigger[i] = array[i];
        }
        return bigger;
    }

    // ----------------------------------------------------------------- values

    /**
     * One parsed value.
     *
     * <p>The accessors are strict on purpose. {@link #field(String)} on something that is
     * not an object, or {@link #index(int)} past the end of an array, throws
     * {@link Malformed} - so a reply that is missing the field the program needs is
     * reported as unusable at the point of use, not carried onward as a null that fails
     * somewhere unrelated.</p>
     */
    public static final class Value {

        static final int OBJECT = 0;
        static final int ARRAY = 1;
        static final int STRING = 2;
        static final int NUMBER = 3;
        static final int TRUE = 4;
        static final int FALSE = 5;
        static final int NULL = 6;

        private final int kind;
        private final String text;
        private final String[] keys;
        private final Value[] children;
        private final int count;

        Value(int kind, String text, String[] keys, Value[] children) {
            this(kind, text, keys, children, children == null ? 0 : children.length);
        }

        Value(int kind, String text, String[] keys, Value[] children, int count) {
            this.kind = kind;
            this.text = text;
            this.keys = keys;
            this.children = children;
            this.count = count;
        }

        /** The kind, for diagnostics: OBJECT, ARRAY, STRING, NUMBER, TRUE, FALSE or NULL. */
        public String kindName() {
            switch (kind) {
                case OBJECT: return "OBJECT";
                case ARRAY: return "ARRAY";
                case STRING: return "STRING";
                case NUMBER: return "NUMBER";
                case TRUE: return "TRUE";
                case FALSE: return "FALSE";
                default: return "NULL";
            }
        }

        /** The named field of an object. */
        public Value field(String name) throws Malformed {
            if (kind != OBJECT) {
                throw new Malformed("expected an object to read '" + name + "' from but found "
                        + kindName());
            }
            for (int i = 0; i < count; i++) {
                if (keys[i].equals(name)) {
                    return children[i];
                }
            }
            throw new Malformed("the reply had no '" + name + "' field");
        }

        /**
         * The named field, or null when it is absent.
         *
         * <p>Only for fields the program can carry on without; anything required uses
         * {@link #field(String)} so a missing value cannot pass silently.</p>
         */
        public Value optionalField(String name) throws Malformed {
            if (kind != OBJECT) {
                return null;
            }
            for (int i = 0; i < count; i++) {
                if (keys[i].equals(name)) {
                    return children[i];
                }
            }
            return null;
        }

        /** True when the object has this field at all. */
        public boolean has(String name) {
            if (kind != OBJECT) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (keys[i].equals(name)) {
                    return true;
                }
            }
            return false;
        }

        /** The item at an index of an array. */
        public Value index(int position) throws Malformed {
            if (kind != ARRAY) {
                throw new Malformed("expected an array but found " + kindName());
            }
            if (position < 0 || position >= count) {
                throw new Malformed("the reply's array had " + count + " item(s), so item "
                        + position + " does not exist");
            }
            return children[position];
        }

        /** How many items an array holds. */
        public int size() throws Malformed {
            if (kind != ARRAY) {
                throw new Malformed("expected an array but found " + kindName());
            }
            return count;
        }

        /** The text of a string value. */
        public String asString() throws Malformed {
            if (kind != STRING) {
                throw new Malformed("expected a string but found " + kindName());
            }
            return text;
        }

        /** True for the literal {@code true} or {@code false}. */
        public boolean isTruthy() {
            return kind == TRUE;
        }

        /** True when the value is {@code null}. */
        public boolean isNull() {
            return kind == NULL;
        }

        /** The text of a number, exactly as it appeared in the reply. */
        public String numberText() throws Malformed {
            if (kind != NUMBER) {
                throw new Malformed("expected a number but found " + kindName());
            }
            return text;
        }
    }
}
