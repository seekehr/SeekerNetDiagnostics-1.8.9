package com.seekehr.netdiagnostics.core.json;

import java.util.List;

/** RFC 4180 CSV helpers, with protection against spreadsheet formula injection. */
public final class Csv {
    private Csv() {
    }

    public static String escape(String field) {
        if (field == null) {
            return "";
        }
        String f = field;
        // Neutralise values that spreadsheet software would evaluate as formulas.
        if (!f.isEmpty()) {
            char c = f.charAt(0);
            if (c == '=' || c == '+' || c == '@' || c == '\t' || c == '\r'
                    || (c == '-' && f.length() > 1 && !isNumeric(f))) {
                f = "'" + f;
            }
        }
        boolean needsQuote = f.indexOf(',') >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0
                || f.indexOf('\r') >= 0;
        if (!needsQuote) {
            return f;
        }
        return '"' + f.replace("\"", "\"\"") + '"';
    }

    private static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String row(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    public static String row(String... fields) {
        return row(java.util.Arrays.asList(fields));
    }
}
