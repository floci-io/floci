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
        List<List<String>> rows = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                rows.add(parseLine(line));
            }
        }
        return rows;
    }
}
