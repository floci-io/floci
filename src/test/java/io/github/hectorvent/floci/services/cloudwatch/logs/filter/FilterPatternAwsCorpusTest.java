package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterPatternAwsCorpusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    List<DynamicTest> observedAwsPatterns() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (String fixture : List.of("filter-pattern-aws.json", "filter-pattern-aws-edges.json",
                "filter-pattern-aws-boundaries.json")) {
            try (InputStream input = getClass().getResourceAsStream("/cloudwatchlogs/" + fixture)) {
                JsonNode corpus = MAPPER.readTree(input);
                for (JsonNode example : corpus.path("cases")) {
                    addCase(tests, example);
                }
            }
        }
        return tests;
    }

    private static void addCase(List<DynamicTest> tests, JsonNode example) {
        String id = example.path("id").textValue();
        String pattern = example.path("request").path("filterPattern").textValue();
        if (example.has("error")) {
            tests.add(DynamicTest.dynamicTest(id, () ->
                    assertThrows(FilterPatternException.class, () -> FilterPattern.parse(pattern), pattern)));
            return;
        }
        JsonNode messages = example.path("request").path("logEventMessages");
        for (int index = 0; index < messages.size(); index++) {
            String message = messages.get(index).textValue();
            int eventNumber = index + 1;
            JsonNode expected = null;
            for (JsonNode match : example.path("response").path("matches")) {
                if (match.path("eventNumber").intValue() == eventNumber) {
                    expected = match;
                    break;
                }
            }
            JsonNode expectedMatch = expected;
            tests.add(DynamicTest.dynamicTest(id + "/" + eventNumber, () -> {
                FilterMatch actual = FilterPattern.parse(pattern).match(message);
                assertEquals(expectedMatch != null, actual.matched(), pattern + " on " + message);
                if (expectedMatch != null) {
                    Map<String, String> extracted = MAPPER.convertValue(expectedMatch.path("extractedValues"),
                            new TypeReference<>() {});
                    assertEquals(extracted, actual.extractedValues(), pattern + " on " + message);
                }
            }));
        }
    }
}
