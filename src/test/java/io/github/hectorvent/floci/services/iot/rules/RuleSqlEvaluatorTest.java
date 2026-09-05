package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSqlEvaluatorTest {

    private static final String SHADOW_TOPIC = "$aws/things/sensor-1/shadow/name/building/update/accepted";

    private final RuleSqlEvaluator evaluator = new RuleSqlEvaluator(new ObjectMapper(), Clock.systemUTC());

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
    void treatsJsonNullAsAValueDistinctFromUndefined() {
        Optional<byte[]> projected = evaluate("SELECT clientToken FROM 'a/b'", "a/b", "{\"clientToken\":null}");
        assertEquals("{\"clientToken\":null}", text(projected));

        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE clientToken = 'x'", "a/b", "{\"clientToken\":null}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE clientToken <> 'x'", "a/b", "{\"clientToken\":null}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE clientToken = NULL", "a/b", "{\"clientToken\":null}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE clientToken <> 'x'", "a/b", "{}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE clientToken = NULL", "a/b", "{}").isPresent());
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
            "level <> '3'       | true",
            "NOT level = '3'    | true",
            "name = 'ok'        | true",
            "name > 'a'         | false",
            "name = 3           | false",
            "name <> 3          | true",
            "enabled = TRUE     | true",
            "enabled <> FALSE   | true",
            "enabled = 'true'   | false",
            "enabled <> 'true'  | true",
            "level > '2'        | true",
            "level >= '3.0'     | true",
            "level < '1E1'      | true",
            "level < 'abc'      | false",
            "code > 9           | true",
            "code > '9'         | true",
            "missing = 3        | false",
            "missing <> 3       | false",
            "NOT level = 4      | true",
            "NOT missing = 3    | false"
    })
    void evaluatesComparisonsWithUndefinedSemantics(String predicate, boolean fires) {
        String payload = "{\"level\":3,\"name\":\"ok\",\"enabled\":true,\"code\":\"10\"}";

        assertEquals(fires, evaluate("SELECT level FROM 'a/b' WHERE " + predicate, "a/b", payload).isPresent());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "missing = 3 OR level = 3   | false",
            "level = 3 OR missing = 3   | false",
            "missing = 3 AND level = 3  | false",
            "NOT missing = 3            | false",
            "level = 3 AND level = 3    | true",
            "level = 4 OR level = 3     | true",
            "flag                       | true",
            "flag AND level = 3         | true",
            "NOT off                    | true",
            "off OR flag                | true",
            "name AND flag              | false",
            "NOT name                   | false",
            "level AND flag             | false"
    })
    void propagatesUndefinedThroughAndOrNotAndCoercesBooleanStrings(String predicate, boolean fires) {
        String payload = "{\"level\":3,\"name\":\"ok\",\"flag\":\"TRUE\",\"off\":\"false\"}";

        assertEquals(fires, evaluate("SELECT level FROM 'a/b' WHERE " + predicate, "a/b", payload).isPresent());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "endswith(code, '2')        | true",
            "startswith(flag, 'tr')     | true",
            "startswith(obj, '{')       | true",
            "endswith(list, ']')        | true",
            "endswith(name, 5)          | true",
            "startswith(name, 5)        | false",
            "endswith(nothing, 'x')     | false",
            "endswith(name, nothing)    | false",
            "endswith(missing, 'x')     | false"
    })
    void convertsStringFunctionArgumentsToStringsExceptNullAndUndefined(String predicate, boolean fires) {
        String payload = "{\"code\":42,\"flag\":true,\"obj\":{\"a\":1},\"list\":[1],\"name\":\"a5\",\"nothing\":null}";

        assertEquals(fires, evaluate("SELECT * FROM 'a/b' WHERE " + predicate, "a/b", payload).isPresent());
    }

    @Test
    void comparesNumbersThatDoNotFitALong() {
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level > 1", "a/b",
                "{\"level\":99999999999999999999999}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level = 3", "a/b", "{\"level\":3.0}").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e-2147483648", "1e99999999999", "-1e-99999999999", "9e9999999999"})
    void treatsANumericStringWhoseExponentOverflowsAsUndefined(String value) {
        String payload = "{\"level\":\"" + value + "\"}";

        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level > 1", "a/b", payload).isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level < 1", "a/b", payload).isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level >= '1e5'", "a/b", payload).isPresent());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1e400                   | level > 1                          | true",
            "1e400                   | level < 1                          | false",
            "1e-400                  | level = 0                          | false",
            "1e-400                  | level > 0                          | true",
            "9007199254740993.0      | level = 9007199254740993.0         | true",
            "9007199254740993.0      | level = 9007199254740992.0         | false",
            "0.1                     | level = 0.1                        | true",
            "0.30000000000000004     | level <> 0.3                       | true",
            "0.30000000000000004     | level = 0.30000000000000004        | true",
            "3.0                     | level = 3                          | true",
            "123456789012345678901234567890.123456789 | level > 123456789012345678901234567890.123456788 | true",
            "1E2                     | level = 100                        | true"
    })
    void readsPayloadNumbersExactlyWhateverTheirSizeOrPrecision(String number, String predicate, boolean fires) {
        assertEquals(fires, evaluate("SELECT * FROM 'a/b' WHERE " + predicate, "a/b",
                "{\"level\":" + number + "}").isPresent());
    }

    @Test
    void comparesAHugePayloadNumberAgainstItsPlainForm() {
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level = 1" + "0".repeat(400) + ".0", "a/b",
                "{\"level\":1e400}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level = 1" + "0".repeat(399) + ".0", "a/b",
                "{\"level\":1e400}").isPresent());
    }

    @Test
    void projectsADecimalPayloadNumberAsWritten() {
        assertEquals("{\"level\":21.50}", text(evaluate("SELECT level FROM 'a/b'", "a/b", "{\"level\":21.50}")));
        assertEquals("{\"level\":9007199254740993.0}",
                text(evaluate("SELECT level FROM 'a/b'", "a/b", "{\"level\":9007199254740993.0}")));
    }

    @Test
    void comparesAWholeNumberAgainstADecimalLiteralExactly() {
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level = 9007199254740992.0", "a/b",
                "{\"level\":9007199254740993}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level > 9007199254740992.0", "a/b",
                "{\"level\":9007199254740993}").isPresent());
    }

    @Test
    void keepsADecimalLiteralExactInsteadOfRoundingItToADouble() {
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE level = 9007199254740993.0", "a/b",
                "{\"level\":9007199254740992}").isPresent());
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE level = 9007199254740993.0", "a/b",
                "{\"level\":9007199254740993}").isPresent());
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
        return evaluator.evaluate("test-rule", RuleSqlParser.parse(sql), new RuleSqlContext(topic, null, "000000000000"), payload);
    }

    private String text(Optional<byte[]> result) {
        assertTrue(result.isPresent(), "Expected the rule to fire");
        return new String(result.get(), StandardCharsets.UTF_8);
    }
}
