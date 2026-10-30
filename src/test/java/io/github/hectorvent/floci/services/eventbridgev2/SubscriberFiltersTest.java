package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriberFiltersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aFilterPerScopeValidates() {
        assertDoesNotThrow(() -> SubscriberFilters.validate(json("{\"Language\":\"EVENT_BRIDGE_PATTERN\",\"Filters\":["
                + filter("DATA", "{\"detail\":{\"total\":[{\"numeric\":[\">\",500]}]}}") + ","
                + filter("METADATA", "{\"region\":[\"EU\"]}") + ","
                + filter("SYSTEM_METADATA", "{\"aws:Source\":[{\"prefix\":\"com.\"}]}") + "]}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"Filters\":[]}",
            "{\"Language\":\"JSONATA\",\"Filters\":[F_DATA]}",
            "{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":\"\"}]}",
            "{\"Filters\":[{\"Scope\":\"PAYLOAD\",\"Pattern\":\"{}\"}]}",
            "{\"Filters\":[F_DATA,F_DATA]}",
            "{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":\"not json\"}]}",
            "{\"Filters\":[{\"Scope\":\"METADATA\",\"Pattern\":\"[]\"}]}",
            "{\"Filters\":[{\"Scope\":\"METADATA\",\"Pattern\":\"{\\\"region\\\":[{\\\"prefix\\\":\\\"E\\\"}]}\"}]}",
            "{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":\"{\\\"a\\\":[{\\\"wildcard\\\":\\\"*x\\\"}]}\"}]}",
            "{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":\"{\\\"a\\\":[{\\\"anything-but\\\":{\\\"wildcard\\\":\\\"*\\\"}}]}\"}]}"
    })
    void aConfigurationAwsRejectsIsInvalidInput(String configuration) {
        AwsException e = assertThrows(AwsException.class, () -> SubscriberFilters.validate(
                json(configuration.replace("F_DATA", filter("DATA", "{\"source\":[\"a\"]}")))));
        assertEquals("InvalidInputException", e.getErrorCode());
    }

    @Test
    void aWildcardIsRejectedWithAwsMessage() {
        AwsException e = assertThrows(AwsException.class, () -> SubscriberFilters.validate(json(
                "{\"Filters\":[" + filter("DATA", "{\"a\":[{\"wildcard\":\"x*\"}]}") + "]}")));
        assertEquals(SubscriberFilters.WILDCARD_MESSAGE, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"detail\":{\"wildcard\":[\"on\"],\"prefix\":{\"numeric\":[1]}}}",
            "{\"$or\":[{\"source\":[{\"prefix\":\"com.\"}]},{\"detail\":{\"total\":[{\"numeric\":[\">\",1,\"<\",9]}]}}]}",
            "{\"a\":[{\"exists\":false},{\"suffix\":{\"equals-ignore-case\":\".PNG\"}},{\"equals-ignore-case\":\"x\"}]}",
            "{\"a\":[{\"anything-but\":[\"x\",1]},{\"anything-but\":{\"prefix\":\"y\"}},"
                    + "{\"anything-but\":{\"equals-ignore-case\":[\"a\",\"b\"]}},{\"anything-but\":\"z\"}]}"
    })
    void aPatternWithOnlyMatchersFlociEvaluatesValidates(String pattern) {
        assertDoesNotThrow(() -> SubscriberFilters.validate(json("{\"Filters\":[" + filter("DATA", pattern) + "]}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ip\":[{\"cidr\":\"10.0.0.0/24\"}]}",
            "{\"a\":[{\"anything-but\":{\"numeric\":[\">\",1]}}]}",
            "{\"a\":[{\"prefix\":\"x\",\"suffix\":\"y\"}]}",
            "{\"$or\":[{\"a\":[{\"cidr\":\"10.0.0.0/8\"}]}]}"
    })
    void aMatcherFlociCannotEvaluateIsRejectedRatherThanNeverMatching(String pattern) {
        AwsException e = assertThrows(AwsException.class, () -> SubscriberFilters.validate(
                json("{\"Filters\":[" + filter("DATA", pattern) + "]}")));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertTrue(e.getMessage().startsWith("Floci does not evaluate the filter matcher"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"source\":\"my.app\"}",
            "{\"detail\":{\"total\":500}}",
            "{\"a\":[[\"x\"]]}",
            "{\"$or\":{\"a\":[\"x\"]}}",
            "{\"$or\":[\"x\"]}",
            "{\"a\":[{\"numeric\":[\">\",\"abc\"]}]}",
            "{\"a\":[{\"numeric\":[\"!=\",1]}]}",
            "{\"a\":[{\"numeric\":[]}]}"
    })
    void aPatternThatWouldSilentlyMatchNothingIsInvalidInput(String pattern) {
        AwsException e = assertThrows(AwsException.class, () -> SubscriberFilters.validate(
                json("{\"Filters\":[" + filter("DATA", pattern) + "]}")));
        assertEquals("InvalidInputException", e.getErrorCode());
    }

    @Test
    void nestedOrBranchesAreCountedAsAlternatives() {
        String inner = "{\"$or\":[{\"x\":[\"1\"]},{\"y\":[\"2\"]}]}";
        String outer = "{\"$or\":[" + String.join(",", Collections.nCopies(10, inner)) + "]}";
        assertDoesNotThrow(() -> SubscriberFilters.validate(json("{\"Filters\":[" + filter("DATA", outer) + "]}")),
                "10 branches of 2 alternatives each are 20 combinations, not 10 x 2^10");
    }

    @Test
    void orListsThatMustMatchTogetherMultiplyPastTheLimit() {
        String branches = IntStream.range(0, 32).mapToObj(i -> "{\"v\":[\"" + i + "\"]}")
                .collect(Collectors.joining(","));
        String pattern = "{\"a\":{\"$or\":[" + branches + "]},\"b\":{\"$or\":[" + branches + "]}}";
        AwsException e = assertThrows(AwsException.class, () -> SubscriberFilters.validate(
                json("{\"Filters\":[" + filter("DATA", pattern) + "]}")));
        assertTrue(e.getMessage().contains("1000 combinations"), e.getMessage());
    }

    @Test
    void patternsOverFourKilobytesInAllAreRejected() {
        String big = "{\"a\":[\"" + "x".repeat(2100) + "\"]}";
        assertThrows(AwsException.class, () -> SubscriberFilters.validate(json("{\"Filters\":["
                + filter("DATA", big) + "," + filter("SYSTEM_METADATA", big) + "]}")));
    }

    @Test
    void anEventMustMatchEveryFilterAndNoConfigurationMatchesAll() {
        ObjectNode metadata = (ObjectNode) json("{\"region\":\"EU\"}");
        ObjectNode system = (ObjectNode) json("{\"ContentType\":\"application/json\"}");
        PublishedEvent event = new PublishedEvent(json("{\"amount\":600}"), metadata, system);
        JsonNode both = json("{\"Filters\":[" + filter("DATA", "{\"amount\":[{\"numeric\":[\">\",500]}]}") + ","
                + filter("METADATA", "{\"region\":[\"EU\"]}") + "]}");
        assertTrue(SubscriberFilters.matches(both, event));
        assertFalse(SubscriberFilters.matches(both, new PublishedEvent(json("{\"amount\":600}"),
                (ObjectNode) json("{\"region\":\"US\"}"), system)));
        assertTrue(SubscriberFilters.matches(null, event));
        assertTrue(SubscriberFilters.matches(json("{}"), event));
    }

    private static String filter(String scope, String pattern) {
        return "{\"Scope\":\"" + scope + "\",\"Pattern\":" + MAPPER.valueToTree(pattern).toString() + "}";
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
