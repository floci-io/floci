package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSqlEvaluatorTest {

    private static final String SHADOW_TOPIC = "$aws/things/sensor-1/shadow/name/building/update/accepted";

    private final RuleSqlEvaluator evaluator = new RuleSqlEvaluator(new ObjectMapper());

    @Test
    void selectAllWithoutWhereForwardsTheOriginalBytes() {
        byte[] payload = "not-json at all".getBytes(StandardCharsets.UTF_8);

        Optional<byte[]> result = evaluate("SELECT * FROM 'devices/+/telemetry'", "devices/a/telemetry", payload);

        assertTrue(result.isPresent());
        assertArrayEquals(payload, result.get());
    }

    @Test
    void selectAllWithAWhereForwardsTheOriginalBytesUnchanged() {
        byte[] payload = "{\n  \"clientToken\" : \"job:inbound\"\n}".getBytes(StandardCharsets.UTF_8);

        Optional<byte[]> result = evaluate("SELECT * FROM 'a/b' WHERE endswith(clientToken, 'inbound')", "a/b", payload);

        assertTrue(result.isPresent());
        assertArrayEquals(payload, result.get());
    }

    @Test
    void aProjectionAliasOverridesAPayloadFieldOfTheSameName() {
        Optional<byte[]> result = evaluate("SELECT *, topic() as topic FROM '$aws/things/+/shadow/name/building/update/accepted'",
                SHADOW_TOPIC, "{\"topic\":\"stale\",\"clientToken\":\"ingest:inbound\"}");

        assertEquals("{\"topic\":\"" + SHADOW_TOPIC + "\",\"clientToken\":\"ingest:inbound\"}", text(result));
    }

    @Test
    void projectsTopicSegmentsStartingAtOne() {
        Optional<byte[]> result = evaluate("SELECT topic(1) AS env, topic(3) AS thingId FROM 'prod/fleet/+/ingest'",
                "prod/fleet/sensor-7/ingest", "{\"value\":1}");

        assertEquals("{\"env\":\"prod\",\"thingId\":\"sensor-7\"}", text(result));
    }

    @Test
    void omitsAProjectionWhoseValueIsUndefined() {
        Optional<byte[]> result = evaluate("SELECT topic(9) AS missingSegment, absent AS gone, value FROM 'a/b'",
                "a/b", "{\"value\":1}");

        assertEquals("{\"value\":1}", text(result));
    }

    @Test
    void projectsDottedPathsUnderTheirLastSegment() {
        Optional<byte[]> result = evaluate("SELECT state.reported.temperature FROM 'a/b'",
                "a/b", "{\"state\":{\"reported\":{\"temperature\":21.5}}}");

        assertEquals("{\"temperature\":21.5}", text(result));
    }

    @Test
    void projectsAJsonNullFieldButNeverMatchesItInAComparison() {
        Optional<byte[]> projected = evaluate("SELECT clientToken FROM 'a/b'", "a/b", "{\"clientToken\":null}");
        assertEquals("{\"clientToken\":null}", text(projected));

        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE clientToken = 'x'", "a/b", "{\"clientToken\":null}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE clientToken <> 'x'", "a/b", "{\"clientToken\":null}").isPresent());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"clientToken\":\"ingest:inbound\"}      | true",
            "{\"clientToken\":\"cc-evt:outbound\"}     | false",
            "{\"clientToken\":\"inbound-but-not-last\"}| false",
            "{\"other\":\"ingest:inbound\"}            | false",
            "{\"clientToken\":42}                      | false",
            "{}                                        | false"
    })
    void evaluatesEndswithWithUndefinedSemantics(String payload, boolean fires) {
        assertEquals(fires, evaluate("SELECT * FROM 'a/b' WHERE endswith(clientToken, 'inbound')", "a/b", payload)
                .isPresent());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"clientToken\":\"any:ingest:inbound\"}       | true",
            "{\"clientToken\":\"gateway:x:events:outbound\"}| true",
            "{\"clientToken\":\"other:x:events:outbound\"}  | false",
            "{\"clientToken\":\"gateway:x:events:inbound\"} | false",
            "{\"clientToken\":\"gateway:\"}                 | false"
    })
    void evaluatesAGroupedPredicateInsideADisjunction(String payload, boolean fires) {
        String sql = "SELECT *, topic() as topic FROM 'a/b' WHERE endswith(clientToken, ':ingest:inbound') "
                + "OR (endswith(clientToken, ':events:outbound') AND startswith(clientToken, 'gateway:'))";

        assertEquals(fires, evaluate(sql, "a/b", payload).isPresent());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "level = 3          | true",
            "level = 4          | false",
            "level <> 4         | true",
            "level != 3         | false",
            "level > 2          | true",
            "level >= 3         | true",
            "level < 3          | false",
            "level <= 3         | true",
            "level = '3'        | false",
            "name = 'ok'        | true",
            "name > 'a'         | false",
            "enabled = TRUE     | true",
            "enabled <> FALSE   | true",
            "enabled = 'true'   | false",
            "missing = 3        | false",
            "missing <> 3       | false",
            "NOT level = 4      | true",
            "NOT missing = 3    | false"
    })
    void evaluatesComparisonsWithUndefinedSemantics(String predicate, boolean fires) {
        String payload = "{\"level\":3,\"name\":\"ok\",\"enabled\":true}";

        assertEquals(fires, evaluate("SELECT level FROM 'a/b' WHERE " + predicate, "a/b", payload).isPresent());
    }

    @Test
    void treatsAnUndefinedOperandAsUnknownInsteadOfFalseUnderNot() {
        assertTrue(evaluate("SELECT level FROM 'a/b' WHERE missing = 3 OR level = 3", "a/b", "{\"level\":3}")
                .isPresent());
        assertFalse(evaluate("SELECT level FROM 'a/b' WHERE missing = 3 AND level = 3", "a/b", "{\"level\":3}")
                .isPresent());
    }

    @Test
    void comparesNumbersThatDoNotFitADoubleOrALong() {
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level > 1", "a/b", "{\"level\":1e400}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level < 1", "a/b", "{\"level\":1e400}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level > 1", "a/b",
                "{\"level\":99999999999999999999999}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level = 3", "a/b", "{\"level\":3.0}").isPresent());
    }

    @Test
    void skipsANonJsonPayloadWhenTheStatementNeedsFields() {
        assertFalse(evaluate("SELECT *, topic() as topic FROM 'a/b'", "a/b", "plain text").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE endswith(clientToken, 'x')", "a/b", "plain text").isPresent());
    }

    @Test
    void skipsAJsonPayloadThatIsNotAnObject() {
        assertFalse(evaluate("SELECT *, topic() as topic FROM 'a/b'", "a/b", "[1,2,3]").isPresent());
    }

    @Test
    void treatsAnEmptyPayloadAsNonJson() {
        assertFalse(evaluate("SELECT *, topic() as topic FROM 'a/b'", "a/b", "").isPresent());
    }

    @Test
    void matchesStringFunctionsOnTopicSegments() {
        assertTrue(evaluate("SELECT * FROM '$aws/events/presence/connected/+' WHERE startswith(topic(5), 'gw-')",
                "$aws/events/presence/connected/gw-1", "{}").isPresent());
    }

    private Optional<byte[]> evaluate(String sql, String topic, String payload) {
        return evaluate(sql, topic, payload.getBytes(StandardCharsets.UTF_8));
    }

    private Optional<byte[]> evaluate(String sql, String topic, byte[] payload) {
        return evaluator.evaluate("test-rule", RuleSqlParser.parse(sql), topic, payload);
    }

    private String text(Optional<byte[]> result) {
        assertTrue(result.isPresent(), "Expected the rule to fire");
        return new String(result.get(), StandardCharsets.UTF_8);
    }
}
