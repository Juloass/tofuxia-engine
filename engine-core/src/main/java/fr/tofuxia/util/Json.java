package fr.tofuxia.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Minimal dependency-free JSON reader used by material files and the glTF
 * importer. Parses into {@link JsonObject}/{@link JsonArray}/String/Double/
 * Boolean/null. Not a validator: it accepts a superset of JSON (trailing
 * commas) and reports errors with line/column context.
 */
public final class Json {
    private final String text;
    private int pos;
    private int line = 1;
    private int column = 1;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < parser.text.length()) {
            throw parser.error("trailing content after JSON document");
        }
        return value;
    }

    public static JsonObject parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof JsonObject obj)) {
            throw new IllegalArgumentException("Expected a JSON object at document root, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return obj;
    }

    /** JSON object with typed convenience getters. */
    public static final class JsonObject extends LinkedHashMap<String, Object> {
        public String getString(String key, String fallback) {
            Object value = get(key);
            return value instanceof String s ? s : fallback;
        }

        public double getDouble(String key, double fallback) {
            Object value = get(key);
            return value instanceof Double d ? d : fallback;
        }

        public int getInt(String key, int fallback) {
            Object value = get(key);
            return value instanceof Double d ? (int) (double) d : fallback;
        }

        public boolean getBoolean(String key, boolean fallback) {
            Object value = get(key);
            return value instanceof Boolean b ? b : fallback;
        }

        public JsonObject getObject(String key) {
            Object value = get(key);
            return value instanceof JsonObject obj ? obj : null;
        }

        public JsonArray getArray(String key) {
            Object value = get(key);
            return value instanceof JsonArray arr ? arr : null;
        }

        public float[] getFloats(String key, float[] fallback) {
            JsonArray array = getArray(key);
            if (array == null) return fallback;
            return array.toFloats();
        }
    }

    /** JSON array with typed convenience getters. */
    public static final class JsonArray extends ArrayList<Object> {
        public JsonObject getObject(int index) {
            Object value = get(index);
            return value instanceof JsonObject obj ? obj : null;
        }

        public String getString(int index) {
            Object value = get(index);
            return value instanceof String s ? s : null;
        }

        public int getInt(int index) {
            Object value = get(index);
            if (!(value instanceof Double d)) {
                throw new IllegalArgumentException("Expected number at array index " + index);
            }
            return (int) (double) d;
        }

        public double getDouble(int index) {
            Object value = get(index);
            if (!(value instanceof Double d)) {
                throw new IllegalArgumentException("Expected number at array index " + index);
            }
            return d;
        }

        public float[] toFloats() {
            float[] out = new float[size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) getDouble(i);
            }
            return out;
        }

        public int[] toInts() {
            int[] out = new int[size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = getInt(i);
            }
            return out;
        }
    }

    private Object parseValue() {
        char c = peek();
        return switch (c) {
            case '{' -> parseObjectValue();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private JsonObject parseObjectValue() {
        expect('{');
        JsonObject object = new JsonObject();
        skipWhitespace();
        if (peek() == '}') {
            advance();
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() == '}') { // tolerate trailing comma
                advance();
                return object;
            }
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                advance();
            } else if (c == '}') {
                advance();
                return object;
            } else {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private JsonArray parseArray() {
        expect('[');
        JsonArray array = new JsonArray();
        skipWhitespace();
        if (peek() == ']') {
            advance();
            return array;
        }
        while (true) {
            skipWhitespace();
            if (peek() == ']') { // tolerate trailing comma
                advance();
                return array;
            }
            array.add(parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                advance();
            } else if (c == ']') {
                advance();
                return array;
            } else {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error("unterminated string");
            char c = text.charAt(pos);
            advance();
            if (c == '"') return out.toString();
            if (c == '\\') {
                char escape = text.charAt(pos);
                advance();
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        String hex = text.substring(pos, pos + 4);
                        for (int i = 0; i < 4; i++) advance();
                        out.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw error("invalid escape '\\" + escape + "'");
                }
            } else {
                out.append(c);
            }
        }
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                advance();
            } else {
                break;
            }
        }
        if (start == pos) throw error("expected a JSON value");
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("invalid number '" + text.substring(start, pos) + "'");
        }
    }

    private Boolean parseBoolean() {
        if (text.startsWith("true", pos)) {
            for (int i = 0; i < 4; i++) advance();
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            for (int i = 0; i < 5; i++) advance();
            return Boolean.FALSE;
        }
        throw error("expected 'true' or 'false'");
    }

    private Object parseNull() {
        if (text.startsWith("null", pos)) {
            for (int i = 0; i < 4; i++) advance();
            return null;
        }
        throw error("expected 'null'");
    }

    private char peek() {
        if (pos >= text.length()) throw error("unexpected end of input");
        return text.charAt(pos);
    }

    private void expect(char expected) {
        if (peek() != expected) throw error("expected '" + expected + "'");
        advance();
    }

    private void advance() {
        if (text.charAt(pos) == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                advance();
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON error at line " + line + ", column " + column + ": " + message);
    }
}
