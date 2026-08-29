package io.github.hectorvent.floci.services.apigateway;

import java.util.ArrayList;
import java.util.List;

public final class ApiKeyCsvParser {
    private ApiKeyCsvParser() {}

    public static List<List<String>> parse(String csv) {
        if (csv == null || csv.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuote = false;
        int len = csv.length();
        for (int i = 0; i < len; i++) {
            char c = csv.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < len && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                } else if (c == '\r' && i + 1 < len && csv.charAt(i + 1) == '\n') {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    i++;
                } else if (c == '\n') {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
        }
        if (inQuote) {
            throw new IllegalArgumentException("Unclosed quote");
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
