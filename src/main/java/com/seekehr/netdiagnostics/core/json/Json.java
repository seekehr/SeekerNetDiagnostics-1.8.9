package com.seekehr.netdiagnostics.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, dependency-free JSON support.
 *
 * <p>The parser is used for untrusted data (Minecraft status responses), so it enforces a maximum
 * input length and nesting depth and never instantiates arbitrary classes. Values map to:
 * object → {@link LinkedHashMap}, array → {@link ArrayList}, string → {@link String},
 * number → {@link Double}, true/false → {@link Boolean}, null → {@code null}.</p>
 */
public final class Json {
    public static final int DEFAULT_MAX_DEPTH = 64;

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value, 0, true);
        return sb.toString();
    }

    public static String writeCompact(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value, 0, false);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int indent, boolean pretty) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", ""));
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent + 1, pretty);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), indent + 1, pretty);
            }
            newline(sb, indent, pretty);
            sb.append('}');
        } else if (v instanceof Iterable) {
            Iterable<Object> it = (Iterable<Object>) v;
            boolean first = true;
            sb.append('[');
            boolean any = false;
            for (Object o : it) {
                any = true;
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent + 1, pretty);
                write(sb, o, indent + 1, pretty);
            }
            if (any) {
                newline(sb, indent, pretty);
            }
            sb.append(']');
        } else if (v instanceof double[]) {
            double[] arr = (double[]) v;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(pretty ? ", " : ",");
                }
                write(sb, arr[i], indent, pretty);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent, boolean pretty) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        return parse(text, Integer.MAX_VALUE, DEFAULT_MAX_DEPTH);
    }

    public static Object parse(String text, int maxLength, int maxDepth) {
        if (text == null) {
            throw new JsonException("null input");
        }
        if (text.length() > maxLength) {
            throw new JsonException("JSON exceeds maximum length of " + maxLength + " characters");
        }
        Parser p = new Parser(text, maxDepth);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("trailing data at offset " + p.pos);
        }
        return v;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) {
            super(msg);
        }
    }

    private static final class Parser {
        final String s;
        final int maxDepth;
        int pos;

        Parser(String s, int maxDepth) {
            this.s = s;
            this.maxDepth = maxDepth;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonException err(String msg) {
            return new JsonException(msg + " at offset " + pos);
        }

        Object value(int depth) {
            if (depth > maxDepth) {
                throw err("maximum nesting depth exceeded");
            }
            if (pos >= s.length()) {
                throw err("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw err("unexpected character '" + c + "'");
            }
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw err("invalid literal");
            }
            pos += lit.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw err("expected object key");
                }
                String key = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw err("expected ':'");
                }
                pos++;
                skipWs();
                m.put(key, value(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw err("unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw err("expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<Object>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw err("unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw err("expected ',' or ']'");
                }
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw err("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw err("truncated unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("invalid unicode escape");
                            }
                            pos += 4;
                            break;
                        default:
                            throw err("invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw err("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Double number() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos - start > 64) {
                throw err("number too long");
            }
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException ex) {
                throw err("invalid number");
            }
        }
    }

    // ------------------------------------------------------------------ access helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key, String def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }

    public static double num(Map<String, Object> m, String key, double def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
