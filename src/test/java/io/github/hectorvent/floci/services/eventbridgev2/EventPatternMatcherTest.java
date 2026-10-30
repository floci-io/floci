package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventPatternMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EVENT = "{\"source\":\"com.shop\",\"detail-type\":\"Order Placed\","
            + "\"resources\":[\"arn:aws:sqs:us-east-1:000000000000:a\",\"r2\"],"
            + "\"detail\":{\"total\":750,\"status\":\"NEW\",\"customer\":{\"tier\":\"gold\"},\"note\":null,"
            + "\"items\":[{\"sku\":\"A1\"},{\"sku\":\"B2\"}]}}";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"source\":[\"com.shop\"]}|true",
            "{\"source\":[\"com.other\",\"com.shop\"]}|true",
            "{\"source\":[\"com.other\"]}|false",
            "{\"detail\":{\"total\":[{\"numeric\":[\">\",500]}]}}|true",
            "{\"detail\":{\"total\":[{\"numeric\":[\">\",500,\"<=\",750]}]}}|true",
            "{\"detail\":{\"total\":[{\"numeric\":[\">\",750]}]}}|false",
            "{\"detail\":{\"total\":[750.0]}}|true",
            "{\"detail\":{\"status\":[{\"prefix\":\"NE\"}]}}|true",
            "{\"detail\":{\"status\":[{\"suffix\":\"EW\"}]}}|true",
            "{\"detail\":{\"status\":[{\"prefix\":{\"equals-ignore-case\":\"ne\"}}]}}|true",
            "{\"detail\":{\"status\":[{\"equals-ignore-case\":\"new\"}]}}|true",
            "{\"detail\":{\"status\":[{\"anything-but\":[\"OLD\",\"VOID\"]}]}}|true",
            "{\"detail\":{\"status\":[{\"anything-but\":\"NEW\"}]}}|false",
            "{\"detail\":{\"status\":[{\"anything-but\":{\"prefix\":\"N\"}}]}}|false",
            "{\"detail\":{\"missing\":[{\"anything-but\":\"x\"}]}}|false",
            "{\"detail\":{\"missing\":[{\"exists\":false}]}}|true",
            "{\"detail\":{\"status\":[{\"exists\":false}]}}|false",
            "{\"detail\":{\"note\":[null]}}|true",
            "{\"detail\":{\"customer\":{\"tier\":[\"gold\"]}}}|true",
            "{\"detail\":{\"customer\":{\"tier\":[\"silver\"]}}}|false",
            "{\"detail\":{\"items\":{\"sku\":[\"B2\"]}}}|true",
            "{\"resources\":[\"r2\"]}|true",
            "{\"$or\":[{\"source\":[\"x\"]},{\"detail\":{\"status\":[\"NEW\"]}}]}|true",
            "{\"$or\":[{\"source\":[\"x\"]},{\"detail\":{\"status\":[\"OLD\"]}}]}|false",
            "{\"source\":\"com.shop\"}|false"
    })
    void aPatternMatchesAsEventBridgeDefinesIt(String pattern, boolean matches) throws Exception {
        assertEquals(matches, EventPatternMatcher.matches(json(pattern), json(EVENT)), pattern);
    }

    private static JsonNode json(String value) throws Exception {
        return MAPPER.readTree(value);
    }
}
