package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvParserTest {

    /**
     * The file from the Step Functions "how Step Functions parses input CSV files" reference,
     * whose third field carries a doubled quote and a line break and whose fourth field is an
     * unquoted object relying on doubled quotes to survive States.StringToJson.
     */
    @Test
    void parsesTheAwsReferenceRecord() {
        String csv = "abc,123,\"This string contains commas, a double quotation marks (\"\"), "
                + "and a newline (\n)\",{\"\"MyKey\"\":\"\"MyValue\"\"},\"[1,2,3]\"";

        List<List<String>> rows = CsvParser.parseAll(csv, ',');

        assertEquals(1, rows.size(), "the quoted newline must not split the record");
        assertEquals(List.of(
                "abc",
                "123",
                "This string contains commas, a double quotation marks (\"), and a newline (\n)",
                "{\"MyKey\":\"MyValue\"}",
                "[1,2,3]"), rows.get(0));
    }

    @Test
    void doubledQuotesSurviveInAnUnquotedField() {
        assertEquals(List.of("{\"MyKey\":\"MyValue\"}"),
                CsvParser.parseAll("{\"\"MyKey\"\":\"\"MyValue\"\"}", ',').get(0));
    }

    @Test
    void doubledQuotesSurviveInAQuotedField() {
        assertEquals(List.of("a\"b"), CsvParser.parseAll("\"a\"\"b\"", ',').get(0));
    }

    /** A field that opens with a quote still follows CSV rules, so "" stays an empty field. */
    @Test
    void aQuotedEmptyFieldStaysEmpty() {
        assertEquals(List.of("", "x"), CsvParser.parseAll("\"\",x", ',').get(0));
    }

    @Test
    void backslashEscapesABackslash() {
        String csv = "path,size\nC:\\\\Program Files\\\\MyApp.exe,6534512\n";

        List<List<String>> rows = CsvParser.parseAll(csv, ',');

        assertEquals(List.of("C:\\Program Files\\MyApp.exe", "6534512"), rows.get(1));
    }

    @Test
    void backslashEscapesAQuoteAndTheDelimiter() {
        assertEquals(List.of("a\"b"), CsvParser.parseAll("a\\\"b", ',').get(0));
        assertEquals(List.of("a,b"), CsvParser.parseAll("a\\,b", ',').get(0));
        assertEquals(List.of("a|b"), CsvParser.parseAll("a\\|b", '|').get(0));
    }

    /** AWS drops a backslash that precedes anything else and keeps the character. */
    @Test
    void backslashBeforeAnyOtherCharacterIsDropped() {
        assertEquals(List.of("azb"), CsvParser.parseAll("a\\zb", ',').get(0));
    }

    @Test
    void honoursANonCommaDelimiter() {
        assertEquals(List.of("a", "b,c"), CsvParser.parseAll("a|\"b,c\"", '|').get(0));
    }

    @Test
    void skipsABlankTrailingLine() {
        assertEquals(1, CsvParser.parseAll("a,b\n", ',').size());
    }

    /**
     * S3 Select and Athena read line by line and expect a backslash to stay put, so the backslash
     * rules above must not reach parseLine.
     */
    @Test
    void parseLineKeepsBackslashesLiteral() {
        assertEquals(List.of("C:\\Users\\x"), CsvParser.parseLine("C:\\Users\\x"));
    }

    @Test
    void parseLineKeepsQuotedCommasTogether() {
        assertEquals(List.of("a", "b,c"), CsvParser.parseLine("a,\"b,c\""));
    }

    @Test
    void parseLineReturnsOneEmptyFieldForAnEmptyLine() {
        assertEquals(List.of(""), CsvParser.parseLine(""));
    }
}
