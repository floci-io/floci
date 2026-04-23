package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipesFilterMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PipesFilterMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new PipesFilterMatcher(MAPPER);
    }

    // ──────────────────────────── applyFilterCriteria ────────────────────────────

    @Test
    void noFilterCriteria_returnsAllRecords() throws Exception {
        List<JsonNode> records = List.of(
                MAPPER.readTree("{\"body\": \"hello\"}"),
                MAPPER.readTree("{\"body\": \"world\"}")
        );
        List<JsonNode> result = matcher.applyFilterCriteria(records, null);
        assertEquals(2, result.size());
    }

    @Test
    void emptyFiltersArray_returnsAllRecords() throws Exception {
        List<JsonNode> records = List.of(MAPPER.readTree("{\"body\": \"hello\"}"));
        JsonNode sp = MAPPER.readTree("{\"FilterCriteria\": {\"Filters\": []}}");
        List<JsonNode> result = matcher.applyFilterCriteria(records, sp);
        assertEquals(1, result.size());
    }

    @Test
    void multipleFilters_orSemantics() throws Exception {
        List<JsonNode> records = List.of(
                MAPPER.readTree("{\"eventSource\": \"aws:sqs\"}"),
                MAPPER.readTree("{\"eventSource\": \"aws:kinesis\"}"),
                MAPPER.readTree("{\"eventSource\": \"aws:dynamodb\"}")
        );
        JsonNode sp = MAPPER.readTree("""
                {"FilterCriteria": {"Filters": [
                    {"Pattern": "{\\"eventSource\\": [\\"aws:sqs\\"]}"},
                    {"Pattern": "{\\"eventSource\\": [\\"aws:kinesis\\"]}"}
                ]}}""");
        List<JsonNode> result = matcher.applyFilterCriteria(records, sp);
        assertEquals(2, result.size());
        assertEquals("aws:sqs", result.get(0).get("eventSource").asText());
        assertEquals("aws:kinesis", result.get(1).get("eventSource").asText());
    }

    // ──────────────────────────── matchesRecord ────────────────────────────

    @Test
    void exactStringMatch() throws Exception {
        JsonNode record = MAPPER.readTree("{\"eventSource\": \"aws:sqs\", \"body\": \"hello\"}");
        assertTrue(matcher.matchesRecord(record, "{\"eventSource\": [\"aws:sqs\"]}"));
        assertFalse(matcher.matchesRecord(record, "{\"eventSource\": [\"aws:kinesis\"]}"));
    }

    @Test
    void nullPatternMatchesEverything() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": \"hello\"}");
        assertTrue(matcher.matchesRecord(record, null));
        assertTrue(matcher.matchesRecord(record, ""));
    }

    @Test
    void allFieldsMustMatch_andSemantics() throws Exception {
        JsonNode record = MAPPER.readTree("{\"eventSource\": \"aws:sqs\", \"awsRegion\": \"us-east-1\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [\"aws:sqs\"], \"awsRegion\": [\"us-east-1\"]}"));
        assertFalse(matcher.matchesRecord(record,
                "{\"eventSource\": [\"aws:sqs\"], \"awsRegion\": [\"eu-west-1\"]}"));
    }

    @Test
    void nestedJsonBodyMatch_parsesStringField() throws Exception {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("messageId", "msg-1");
        record.put("body", "{\"status\": \"active\", \"count\": 5}");
        record.put("eventSource", "aws:sqs");

        assertTrue(matcher.matchesRecord(record, "{\"body\": {\"status\": [\"active\"]}}"));
        assertFalse(matcher.matchesRecord(record, "{\"body\": {\"status\": [\"inactive\"]}}"));
    }

    @Test
    void nestedObjectMatch_recursesDirectly() throws Exception {
        JsonNode record = MAPPER.readTree("{\"detail\": {\"status\": \"active\", \"type\": \"order\"}}");
        assertTrue(matcher.matchesRecord(record, "{\"detail\": {\"status\": [\"active\"]}}"));
        assertFalse(matcher.matchesRecord(record, "{\"detail\": {\"status\": [\"inactive\"]}}"));
    }

    // ──────────────────────────── Operators ────────────────────────────

    @Test
    void prefixOperator() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": \"order-12345\"}");
        assertTrue(matcher.matchesRecord(record, "{\"body\": [{\"prefix\": \"order-\"}]}"));
        assertFalse(matcher.matchesRecord(record, "{\"body\": [{\"prefix\": \"invoice-\"}]}"));
    }

    @Test
    void suffixOperator() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": \"report.json\"}");
        assertTrue(matcher.matchesRecord(record, "{\"body\": [{\"suffix\": \".json\"}]}"));
        assertFalse(matcher.matchesRecord(record, "{\"body\": [{\"suffix\": \".xml\"}]}"));
    }

    @Test
    void equalsIgnoreCase() throws Exception {
        JsonNode record = MAPPER.readTree("{\"status\": \"Active\"}");
        assertTrue(matcher.matchesRecord(record, "{\"status\": [{\"equals-ignore-case\": \"active\"}]}"));
        assertTrue(matcher.matchesRecord(record, "{\"status\": [{\"equals-ignore-case\": \"ACTIVE\"}]}"));
        assertFalse(matcher.matchesRecord(record, "{\"status\": [{\"equals-ignore-case\": \"inactive\"}]}"));
    }

    @Test
    void anythingBut_array() throws Exception {
        JsonNode record = MAPPER.readTree("{\"eventSource\": \"aws:sqs\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": [\"aws:kinesis\", \"aws:dynamodb\"]}]}"));
        assertFalse(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": [\"aws:sqs\", \"aws:kinesis\"]}]}"));
    }

    @Test
    void anythingBut_string() throws Exception {
        JsonNode record = MAPPER.readTree("{\"eventSource\": \"aws:sqs\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": \"aws:kinesis\"}]}"));
        assertFalse(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": \"aws:sqs\"}]}"));
    }

    @Test
    void anythingButPrefix() throws Exception {
        JsonNode record = MAPPER.readTree("{\"eventSource\": \"custom:source\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": {\"prefix\": \"aws:\"}}]}"));

        JsonNode awsRecord = MAPPER.readTree("{\"eventSource\": \"aws:sqs\"}");
        assertFalse(matcher.matchesRecord(awsRecord,
                "{\"eventSource\": [{\"anything-but\": {\"prefix\": \"aws:\"}}]}"));
    }

    @Test
    void existsTrue_matchesWhenPresent() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": \"hello\"}");
        assertTrue(matcher.matchesRecord(record, "{\"body\": [{\"exists\": true}]}"));
        assertFalse(matcher.matchesRecord(record, "{\"missingField\": [{\"exists\": true}]}"));
    }

    @Test
    void existsFalse_matchesWhenAbsent() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": \"hello\"}");
        assertTrue(matcher.matchesRecord(record, "{\"missingField\": [{\"exists\": false}]}"));
        assertFalse(matcher.matchesRecord(record, "{\"body\": [{\"exists\": false}]}"));
    }

    @Test
    void nullMatch() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": null}");
        assertTrue(matcher.matchesRecord(record, "{\"body\": [null]}"));

        JsonNode nonNull = MAPPER.readTree("{\"body\": \"hello\"}");
        assertFalse(matcher.matchesRecord(nonNull, "{\"body\": [null]}"));
    }

    @Test
    void numericExactMatch() throws Exception {
        JsonNode record = MAPPER.readTree("{\"count\": 42}");
        assertTrue(matcher.matchesRecord(record, "{\"count\": [42]}"));
        assertFalse(matcher.matchesRecord(record, "{\"count\": [99]}"));
    }

    @Test
    void numericRangeFilter() throws Exception {
        JsonNode record = MAPPER.readTree("{\"price\": 50}");
        assertTrue(matcher.matchesRecord(record, "{\"price\": [{\"numeric\": [\">\", 10, \"<\", 100]}]}"));
        assertFalse(matcher.matchesRecord(record, "{\"price\": [{\"numeric\": [\">\", 100]}]}"));
    }

    @Test
    void multipleValuesInArray_orWithinField() throws Exception {
        JsonNode record = MAPPER.readTree("{\"status\": \"pending\"}");
        assertTrue(matcher.matchesRecord(record, "{\"status\": [\"active\", \"pending\"]}"));
        assertFalse(matcher.matchesRecord(record, "{\"status\": [\"active\", \"completed\"]}"));
    }

    // ──────────────────────────── Blocker coverage ────────────────────────────

    @Test
    void scalarPatternValue_doesNotMatch() throws Exception {
        JsonNode record = MAPPER.readTree("{\"status\": \"active\"}");
        assertFalse(matcher.matchesRecord(record, "{\"status\": \"active\"}"));
    }

    @Test
    void anythingBut_array_matchesMissingField() throws Exception {
        JsonNode record = MAPPER.readTree("{\"other\": \"value\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": [\"aws:sqs\"]}]}"));
    }

    @Test
    void anythingBut_string_matchesMissingField() throws Exception {
        JsonNode record = MAPPER.readTree("{\"other\": \"value\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": \"aws:sqs\"}]}"));
    }

    @Test
    void anythingButPrefix_matchesMissingField() throws Exception {
        JsonNode record = MAPPER.readTree("{\"other\": \"value\"}");
        assertTrue(matcher.matchesRecord(record,
                "{\"eventSource\": [{\"anything-but\": {\"prefix\": \"aws:\"}}]}"));
    }

    @Test
    void existsTrue_doesNotMatchNullValue() throws Exception {
        JsonNode record = MAPPER.readTree("{\"body\": null}");
        assertFalse(matcher.matchesRecord(record, "{\"body\": [{\"exists\": true}]}"));
    }

    @Test
    void prefixAgainstNonString_doesNotMatch() throws Exception {
        JsonNode record = MAPPER.readTree("{\"count\": 42}");
        assertFalse(matcher.matchesRecord(record, "{\"count\": [{\"prefix\": \"4\"}]}"));
    }

    @Test
    void numericFilter_withNonNumber_doesNotMatch() throws Exception {
        JsonNode record = MAPPER.readTree("{\"count\": \"not-a-number\"}");
        assertFalse(matcher.matchesRecord(record, "{\"count\": [{\"numeric\": [\"=\", 42]}]}"));
    }
}
