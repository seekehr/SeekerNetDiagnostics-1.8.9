package com.seekehr.netdiagnostics.helper;

import java.util.ArrayList;
import java.util.List;

/** Line format between the mod and the helper: tab-separated fields, with \\ \t \n \r escaped. */
public final class Wire {
    private Wire() {
    }

    public static String line(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('\t');
            }
            String f = fields[i] == null ? "" : fields[i];
            for (int j = 0; j < f.length(); j++) {
                char c = f.charAt(j);
                switch (c) {
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    default:
                        sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String[] split(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                cur.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
