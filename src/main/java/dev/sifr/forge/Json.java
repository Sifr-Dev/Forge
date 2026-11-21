package dev.sifr.forge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict RFC 8259 subset parser: objects, arrays, strings, numbers, booleans and null. */
public final class Json {
    private Json() {
    }

    public static Object parse(String text) throws ForgeException {
        if (text == null || text.indexOf('\0') >= 0) {
            throw new ForgeException("JSON: input must not contain NUL");
        }
        var parser = new Parser(text);
        parser.space();
        Object value = parser.value(0);
        parser.space();
        if (!parser.end()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    /** Deterministic JSON output used by CLI reports and cache metadata. */
    public static String stringify(Object value) {
        var out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String string -> quote(string, out);
            case Boolean bool -> out.append(bool);
            case BigDecimal number -> out.append(number.toString());
            case Number number -> out.append(number);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote((String) entry.getKey(), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
        }
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int at;

        private Parser(String text) {
            this.text = text;
        }

        private boolean end() {
            return at == text.length();
        }

        private void space() {
            while (!end()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    at++;
                } else {
                    break;
                }
            }
        }

        private Object value(int depth) throws ForgeException {
            if (depth > 100) {
                throw error("nesting exceeds 100 levels");
            }
            if (end()) {
                throw error("expected a JSON value");
            }
            return switch (text.charAt(at)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    char c = text.charAt(at);
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("expected a JSON value");
                }
            };
        }

        private Map<String, Object> object(int depth) throws ForgeException {
            at++;
            space();
            var result = new LinkedHashMap<String, Object>();
            if (take('}')) {
                return result;
            }
            while (true) {
                if (end() || text.charAt(at) != '"') {
                    throw error("object key must be a string");
                }
                String key = string();
                if (result.containsKey(key)) {
                    throw error("duplicate object key: " + key);
                }
                space();
                expect(':');
                space();
                result.put(key, value(depth));
                space();
                if (take('}')) {
                    return result;
                }
                expect(',');
                space();
            }
        }

        private List<Object> array(int depth) throws ForgeException {
            at++;
            space();
            var result = new ArrayList<Object>();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value(depth));
                space();
                if (take(']')) {
                    return result;
                }
                expect(',');
                space();
            }
        }

        private String string() throws ForgeException {
            expect('"');
            var out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character in string");
                }
                if (c == '\\') {
                    if (end()) {
                        throw error("unterminated escape sequence");
                    }
                    char escaped = text.charAt(at++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> unicode(out);
                        default -> throw error("invalid string escape: \\" + escaped);
                    }
                } else if (Character.isHighSurrogate(c)) {
                    if (end() || !Character.isLowSurrogate(text.charAt(at))) {
                        throw error("unpaired high surrogate in string");
                    }
                    out.append(c).append(text.charAt(at++));
                } else if (Character.isLowSurrogate(c)) {
                    throw error("unpaired low surrogate in string");
                } else {
                    out.append(c);
                }
            }
            throw error("unterminated string");
        }

        private void unicode(StringBuilder out) throws ForgeException {
            char c = unicodeCodeUnit();
            if (Character.isHighSurrogate(c)) {
                if (at + 6 > text.length() || text.charAt(at) != '\\' || text.charAt(at + 1) != 'u') {
                    throw error("high surrogate must be followed by a low surrogate escape");
                }
                at += 2;
                char low = unicodeCodeUnit();
                if (!Character.isLowSurrogate(low)) {
                    throw error("high surrogate must be followed by a low surrogate");
                }
                out.append(c).append(low);
            } else if (Character.isLowSurrogate(c)) {
                throw error("unpaired low surrogate escape");
            } else {
                out.append(c);
            }
        }

        private char unicodeCodeUnit() throws ForgeException {
            if (at + 4 > text.length()) {
                throw error("incomplete Unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(at++), 16);
                if (digit < 0) {
                    throw error("invalid Unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private BigDecimal number() throws ForgeException {
            int start = at;
            take('-');
            if (take('0')) {
                if (!end() && Character.isDigit(text.charAt(at))) {
                    throw error("number has a leading zero");
                }
            } else {
                digits(true);
            }
            if (take('.')) {
                digits(true);
            }
            if (!end() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (!end() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                    at++;
                }
                digits(true);
            }
            try {
                return new BigDecimal(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw error("invalid number");
            }
        }

        private void digits(boolean requireOne) throws ForgeException {
            int start = at;
            while (!end() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                at++;
            }
            if (requireOne && start == at) {
                throw error("expected a digit");
            }
        }

        private Object literal(String token, Object value) throws ForgeException {
            if (!text.startsWith(token, at)) {
                throw error("invalid literal");
            }
            at += token.length();
            return value;
        }

        private boolean take(char c) {
            if (!end() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) throws ForgeException {
            if (!take(c)) {
                throw error("expected '" + c + "'");
            }
        }

        private ForgeException error(String message) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < at && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new ForgeException("JSON at line " + line + ", column " + column + ": " + message);
        }
    }
}
