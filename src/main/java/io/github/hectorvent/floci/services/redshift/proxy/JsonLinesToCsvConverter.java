package io.github.hectorvent.floci.services.redshift.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class JsonLinesToCsvConverter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SQLSTATE_INTERNAL = "XX000";

    private JsonLinesToCsvConverter() {
    }

    static void convert(InputStream in, List<String> targetColumns, OutputStream out) throws IOException {
        if (targetColumns == null || targetColumns.isEmpty()) {
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                    "Cannot convert JSON to CSV without target columns", null);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> lowerColumns = targetColumns.stream().map(String::toLowerCase).toList();
        String line;

        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonNode rootNode;
            try {
                rootNode = MAPPER.readTree(trimmed);
            } catch (IOException e) {
                String preview = trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
                throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                        "JSON parse error in S3 data around '" + preview + "': " + e.getMessage(), e);
            }
            if (!rootNode.isObject()) {
                continue;
            }
            Map<String, JsonNode> fieldMap = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                fieldMap.put(entry.getKey().toLowerCase(), entry.getValue());
            }

            StringBuilder csvLine = new StringBuilder();
            for (int i = 0; i < lowerColumns.size(); i++) {
                if (i > 0) {
                    csvLine.append(',');
                }
                String col = lowerColumns.get(i);
                JsonNode val = fieldMap.get(col);
                if (val == null || val.isNull()) {
                    // NULL in Postgres CSV is unquoted empty string
                    continue;
                }
                if (val.isNumber() || val.isBoolean()) {
                    csvLine.append(val.asText());
                } else if (val.isTextual()) {
                    String text = val.asText();
                    if (text.isEmpty()) {
                        csvLine.append("\"\"");
                    } else {
                        csvLine.append(escapeCsv(text));
                    }
                } else {
                    // Nested Object or Array: serialize as JSON text
                    csvLine.append(escapeCsv(MAPPER.writeValueAsString(val)));
                }
            }
            csvLine.append('\n');
            byte[] bytes = csvLine.toString().getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
        }
        out.flush();
    }

    static String escapeCsv(String text) {
        if (text == null) {
            return "";
        }
        boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        String escaped = text.replace("\"", "\"\"");
        if (needsQuotes) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
