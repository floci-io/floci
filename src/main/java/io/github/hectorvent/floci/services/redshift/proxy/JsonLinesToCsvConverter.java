package io.github.hectorvent.floci.services.redshift.proxy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
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
        convert(in, targetColumns, out, false);
    }

    static void convert(InputStream in, List<String> targetColumns, OutputStream out, boolean ignoreCase) throws IOException {
        if (targetColumns == null || targetColumns.isEmpty()) {
            // Catalog-based column discovery for FORMAT AS JSON 'auto' only runs over the Simple
            // Query protocol; over Extended Query the column list is fixed at Parse time, before
            // any backend round trip is possible. This is the only way targetColumns ever arrives
            // empty here, so the message names Extended Query directly instead of guessing.
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                    "COPY ... FORMAT AS JSON 'auto' requires an explicit column list over the "
                            + "Extended Query protocol; specify columns, or connect with "
                            + "preferQueryMode=simple to use catalog-based column discovery", null);
        }

        List<String> lookupColumns = ignoreCase
                ? targetColumns.stream().map(String::toLowerCase).toList()
                : targetColumns;

        try (JsonParser parser = MAPPER.getFactory().createParser(in)) {
            while (parser.nextToken() != null) {
                JsonNode rootNode;
                try {
                    rootNode = MAPPER.readTree(parser);
                } catch (IOException e) {
                    throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                            "JSON parse error in S3 data: " + e.getMessage(), e);
                }
                if (rootNode == null) {
                    continue;
                }
                if (!rootNode.isObject()) {
                    String preview = rootNode.toString();
                    if (preview.length() > 60) {
                        preview = preview.substring(0, 60) + "...";
                    }
                    throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                            "JSON value is not an object: " + preview, null);
                }

                Map<String, JsonNode> fieldMap = null;
                if (ignoreCase) {
                    fieldMap = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        fieldMap.put(entry.getKey().toLowerCase(), entry.getValue());
                    }
                }

                StringBuilder csvLine = new StringBuilder();
                for (int i = 0; i < lookupColumns.size(); i++) {
                    if (i > 0) {
                        csvLine.append(',');
                    }
                    String col = lookupColumns.get(i);
                    JsonNode val = ignoreCase ? fieldMap.get(col) : rootNode.get(col);
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
        } catch (IOException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new S3CopySimulator.S3TransferException(SQLSTATE_INTERNAL,
                    "JSON parse error in S3 data: " + cause.getMessage(), cause);
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
