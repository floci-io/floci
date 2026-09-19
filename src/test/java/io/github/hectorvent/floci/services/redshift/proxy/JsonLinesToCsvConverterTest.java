package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLinesToCsvConverterTest {

    @Test
    void convert_caseInsensitiveMappingAndEscaping() throws IOException {
        String ndjson = "{\"Id\": 1, \"Name\": \"Alice\", \"note\": \"hello, world\"}\n"
                + "{\"id\": 2, \"NAME\": \"Bob, \\\"The Builder\\\"\", \"note\": \"multi\\nline\"}\n"
                + "{\"ID\": 3, \"name\": null}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("id", "name", "note");
        JsonLinesToCsvConverter.convert(in, columns, out, true);

        String result = out.toString(StandardCharsets.UTF_8);
        String[] lines = result.split("\n");
        assertEquals("1,Alice,\"hello, world\"", lines[0].trim());
        assertEquals("2,\"Bob, \"\"The Builder\"\"\",\"multi\nline\"", lines[1].trim() + "\n" + lines[2].trim());
        assertEquals("3,,", lines[3].trim());
    }

    @Test
    void convert_caseSensitiveDefault_matchesExactCase() throws IOException {
        String ndjson = "{\"Id\": 1, \"id\": 10, \"NAME\": \"Alice\", \"name\": \"Bob\"}\n"
                + "{\"Id\": 2, \"name\": \"Charlie\"}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("id", "name");
        // default ignoreCase = false
        JsonLinesToCsvConverter.convert(in, columns, out);

        String result = out.toString(StandardCharsets.UTF_8);
        String[] lines = result.split("\n");
        assertEquals("10,Bob", lines[0].trim());
        // Second line has "Id" but not "id", so "id" column is null (empty)
        assertEquals(",Charlie", lines[1].trim());
    }

    @Test
    void convert_prettyPrintedMultilineJson() throws IOException {
        String prettyJson = "{\n  \"id\": 1,\n  \"name\": \"Alice\"\n}\n"
                + "{\n  \"id\": 2,\n  \"name\": \"Bob\"\n}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(prettyJson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("id", "name");
        JsonLinesToCsvConverter.convert(in, columns, out);

        String result = out.toString(StandardCharsets.UTF_8);
        String[] lines = result.split("\n");
        assertEquals("1,Alice", lines[0].trim());
        assertEquals("2,Bob", lines[1].trim());
    }

    @Test
    void convert_nonObjectRoot_throwsException() {
        String jsonArray = "[{\"id\": 1, \"name\": \"Alice\"}]\n";
        ByteArrayInputStream in = new ByteArrayInputStream(jsonArray.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("id", "name");
        S3CopySimulator.S3TransferException ex = assertThrows(
                S3CopySimulator.S3TransferException.class,
                () -> JsonLinesToCsvConverter.convert(in, columns, out));

        assertTrue(ex.getMessage().contains("JSON value is not an object"));
    }

    @Test
    void convert_nestedJsonSerializedToString() throws IOException {
        String ndjson = "{\"id\": 10, \"payload\": {\"subKey\": \"val\", \"count\": 5}}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("id", "payload");
        JsonLinesToCsvConverter.convert(in, columns, out);

        String result = out.toString(StandardCharsets.UTF_8);
        assertEquals("10,\"{\"\"subKey\"\":\"\"val\"\",\"\"count\"\":5}\"", result.trim());
    }

    @Test
    void convert_booleanAndNumericFormats() throws IOException {
        String ndjson = "{\"flag\": true, \"val\": 12.345, \"zero\": 0}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("flag", "val", "zero");
        JsonLinesToCsvConverter.convert(in, columns, out);

        String result = out.toString(StandardCharsets.UTF_8);
        assertEquals("true,12.345,0", result.trim());
    }

    @Test
    void convert_malformedJson_includesLinePreviewInException() {
        String badNdjson = "{\"id\": 1, \"name\": invalid_json}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(badNdjson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        List<String> columns = List.of("id", "name");
        S3CopySimulator.S3TransferException ex = assertThrows(
                S3CopySimulator.S3TransferException.class,
                () -> JsonLinesToCsvConverter.convert(in, columns, out));

        assertTrue(ex.getMessage().contains("invalid_json"));
    }

    @Test
    void convert_emptyOrNullColumns_throwsException() {
        String ndjson = "{\"id\": 1}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(
                S3CopySimulator.S3TransferException.class,
                () -> JsonLinesToCsvConverter.convert(in, List.of(), out));
        assertThrows(
                S3CopySimulator.S3TransferException.class,
                () -> JsonLinesToCsvConverter.convert(in, null, out));
    }
}
