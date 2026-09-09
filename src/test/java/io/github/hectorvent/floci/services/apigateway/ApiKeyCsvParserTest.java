package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyCsvParserTest {

    @Test
    void parsesStandardCsvWithCommaInField() {
        String csv = "Name,Key,UsagePlanIds\nMyKey,abcdefghijklmnopqrst,\"up-1,up-2\"\n";
        List<List<String>> result = ApiKeyCsvParser.parse(csv);

        assertEquals(2, result.size());
        assertEquals(List.of("Name", "Key", "UsagePlanIds"), result.get(0));
        assertEquals(List.of("MyKey", "abcdefghijklmnopqrst", "up-1,up-2"), result.get(1));
    }

    @Test
    void parsesEscapedQuotes() {
        String csv = "Name,Key\n\"A \"\"quoted\"\" key\",abcdefghijklmnopqrst\n";
        List<List<String>> result = ApiKeyCsvParser.parse(csv);

        assertEquals(2, result.size());
        assertEquals(List.of("Name", "Key"), result.get(0));
        assertEquals(List.of("A \"quoted\" key", "abcdefghijklmnopqrst"), result.get(1));
    }

    @Test
    void parsesCRLFAndIgnoresFinalEmptyRow() {
        String csv = "Name,Key\r\nMyKey,abcdefghijklmnopqrst\r\n";
        List<List<String>> result = ApiKeyCsvParser.parse(csv);

        assertEquals(2, result.size());
        assertEquals(List.of("Name", "Key"), result.get(0));
        assertEquals(List.of("MyKey", "abcdefghijklmnopqrst"), result.get(1));
    }

    @Test
    void throwsOnUnterminatedQuote() {
        String csv = "Name,Key\n\"Unfinished,Value\n";

        assertThrows(IllegalArgumentException.class, () -> ApiKeyCsvParser.parse(csv));
    }
}
