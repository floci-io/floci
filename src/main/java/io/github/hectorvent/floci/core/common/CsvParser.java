package io.github.hectorvent.floci.core.common;

import java.util.ArrayList;
import java.util.List;

public class CsvParser {

    private CsvParser() {}

    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }

    public static List<List<String>> parseAll(String content) {
        return parseAll(content, ',');
    }

    /**
     * Splits records on line breaks that fall outside a quoted field, so a quoted field may span
     * several lines. A line carrying no fields at all is skipped, as a trailing newline is normal.
     */
    public static List<List<String>> parseAll(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                quoted = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                if (!isBlank(record, quoted)) {
                    rows.add(record);
                }
                record = new ArrayList<>();
                quoted = false;
            } else {
                field.append(c);
            }
        }

        record.add(field.toString());
        if (!isBlank(record, quoted)) {
            rows.add(record);
        }
        return rows;
    }

    private static boolean isBlank(List<String> record, boolean quoted) {
        return !quoted && record.size() == 1 && record.get(0).isBlank();
    }
}
