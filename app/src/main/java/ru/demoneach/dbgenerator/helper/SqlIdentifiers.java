package ru.demoneach.dbgenerator.helper;

import ru.demoneach.dbgenerator.exception.ParametFormatException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SqlIdentifiers {
    private static final Pattern SIMPLE = Pattern.compile("[\\p{L}\\p{N}_$-]+");
    private SqlIdentifiers() {}

    public static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static String qualified(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    // Keep existing simple configuration keys stable, quote ambiguous components.
    public static String configPart(String name) {
        return SIMPLE.matcher(name).matches() ? name : quote(name);
    }

    public static String normalize(String name, int count) {
        return parse(name, count).stream().map(SqlIdentifiers::configPart).collect(Collectors.joining("."));
    }

    public static List<String> parse(String name, int count) {
        if (name == null || name.indexOf('\0') >= 0) throw invalid(name, count);
        List<String> parts = new ArrayList<>();
        int pos = 0;
        while (pos < name.length()) {
            StringBuilder part = new StringBuilder();
            if (name.charAt(pos) == '"') {
                pos++;
                boolean closed = false;
                while (pos < name.length()) {
                    char ch = name.charAt(pos++);
                    if (ch != '"') part.append(ch);
                    else if (pos < name.length() && name.charAt(pos) == '"') { part.append('"'); pos++; }
                    else { closed = true; break; }
                }
                if (!closed) throw invalid(name, count);
            } else {
                int start = pos;
                while (pos < name.length() && name.charAt(pos) != '.') pos++;
                String raw = name.substring(start, pos);
                if (!SIMPLE.matcher(raw).matches()) throw invalid(name, count);
                part.append(raw);
            }
            if (part.isEmpty()) throw invalid(name, count);
            parts.add(part.toString());
            if (pos == name.length()) break;
            if (name.charAt(pos++) != '.' || pos == name.length()) throw invalid(name, count);
        }
        if (parts.size() != count) throw invalid(name, count);
        return parts;
    }

    private static ParametFormatException invalid(String name, int count) {
        return new ParametFormatException("Invalid identifier: " + name + "; expected " + count
                + " dot-separated components; use balanced double quotes and escape quotes as \"\"");
    }
}
