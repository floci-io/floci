package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message functions, {@code isNull}, {@code isUndefined}, the {@code IN} operator and
 * array literals, against the tables in the AWS IoT SQL reference.
 */
class RuleSqlFunctionsTest {

    private static final long NOW = 1481825251155L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private static final RuleSqlContext MQTT = new RuleSqlContext("a/b", "sensor-7", "123456789012");
    private static final RuleSqlContext HTTP = new RuleSqlContext("a/b", null, "123456789012");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleSqlEvaluator evaluator = new RuleSqlEvaluator(objectMapper, CLOCK);

    @Test
    void timestampIsTheClockInMillisecondsSinceTheEpoch() {
        assertEquals("{\"ts\":" + NOW + "}", text(evaluate("SELECT timestamp() AS ts FROM 'a/b'", MQTT, "{}")));
    }

    @Test
    void timestampComparesAsANumber() {
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE timestamp() > 1481825251154", MQTT, "{}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE timestamp() > 1481825251155", MQTT, "{}").isPresent());
    }

    @Test
    void newuuidIsAFreshUuidEachTime() throws Exception {
        String first = json(evaluate("SELECT newuuid() AS id FROM 'a/b'", MQTT, "{}")).get("id").textValue();
        String second = json(evaluate("SELECT newuuid() AS id FROM 'a/b'", MQTT, "{}")).get("id").textValue();

        assertDoesNotThrow(() -> UUID.fromString(first));
        assertDoesNotThrow(() -> UUID.fromString(second));
        assertNotEquals(first, second);
    }

    @Test
    void accountidIsTheAccountThatOwnsTheRule() {
        assertEquals("{\"account\":\"123456789012\"}",
                text(evaluate("SELECT accountid() AS account FROM 'a/b'", MQTT, "{}")));
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE accountid() = '123456789012'", HTTP, "{}").isPresent());
    }

    @Test
    void clientidIsTheMqttClientOrNaWhenTheMessageDidNotComeOverMqtt() {
        assertEquals("{\"client\":\"sensor-7\"}", text(evaluate("SELECT clientid() AS client FROM 'a/b'", MQTT, "{}")));
        assertEquals("{\"client\":\"n/a\"}", text(evaluate("SELECT clientid() AS client FROM 'a/b'", HTTP, "{}")));
        assertTrue(evaluate("SELECT * FROM 'a/b' WHERE startswith(clientid(), 'sensor-')", MQTT, "{}").isPresent());
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE startswith(clientid(), 'sensor-')", HTTP, "{}").isPresent());
    }

    @Test
    void functionsWithoutAnAliasAreWrittenUnderTheirOwnName() throws Exception {
        JsonNode document = json(evaluate(
                "SELECT timestamp(), accountid(), clientid(), isNull(n), isUndefined(missing) FROM 'a/b'",
                MQTT, "{\"n\":null}"));

        assertEquals(NOW, document.get("timestamp").longValue());
        assertEquals("123456789012", document.get("accountid").textValue());
        assertEquals("sensor-7", document.get("clientid").textValue());
        assertTrue(document.get("isNull").booleanValue());
        assertTrue(document.get("isUndefined").booleanValue());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "i          | false | false",
            "d          | false | false",
            "b          | false | false",
            "s          | false | false",
            "a          | false | false",
            "o          | false | false",
            "n          | true  | false",
            "missing    | false | true",
            "o.missing  | false | true",
            "s.deeper   | false | true",
            "NULL       | true  | false",
            "5          | false | false",
            "topic(9)   | false | true",
            "topic(1)   | false | false",
            "clientid() | false | false"
    })
    void isNullAndIsUndefinedFollowTheReferenceTables(String argument, boolean isNull, boolean isUndefined) {
        String payload = "{\"i\":1,\"d\":1.5,\"b\":true,\"s\":\"x\",\"a\":[1],\"o\":{},\"n\":null}";

        assertEquals("{\"n\":" + isNull + ",\"u\":" + isUndefined + "}", text(evaluate(
                "SELECT isNull(" + argument + ") AS n, isUndefined(" + argument + ") AS u FROM 'a/b'", MQTT, payload)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "3 IN arr                   | true",
            "4 IN arr                   | false",
            "'three' IN arr             | true",
            "5.7 IN arr                 | true",
            "3.0 IN arr                 | true",
            "NULL IN arr                | true",
            "TRUE IN arr                | false",
            "[1] IN arr                 | true",
            "num IN arr                 | true",
            "three IN arr               | true",
            "s IN arr                   | false",
            "missing IN arr             | false",
            "3 IN s                     | false",
            "3 IN missing               | false",
            "3 IN o                     | false",
            "3 IN [1, 2, 3]             | true",
            "'x' IN ['x', 'y']          | true",
            "'z' IN ['x', 'y']          | false",
            "3 IN []                    | false",
            "s IN [s]                   | true",
            "NOT 3 IN [1]               | true",
            "NOT missing IN [1]         | false",
            "3 IN [1] OR num = 3        | true",
            "num IN arr AND s = 'x'     | true",
            "topic(1) IN ['a', 'x']     | true",
            "'3' IN arr                 | false",
            "3 IN ['3']                 | false"
    })
    void inChecksMembershipOfAnArrayWithTheEqualityRules(String predicate, boolean fires) {
        String payload = "{\"arr\":[1,2,3,\"three\",5.7,null,[1],{\"k\":1}],\"three\":\"three\",\"s\":\"x\",\"num\":3,\"o\":{}}";

        assertEquals(fires, evaluate("SELECT * FROM 'a/b' WHERE " + predicate, MQTT, payload).isPresent());
    }

    @Test
    void anArrayLiteralProjectsAsAnArray() {
        String payload = "{\"lat\":47.606,\"long\":-122.332}";

        assertEquals("{\"lat_long\":[47.606,-122.332]}",
                text(evaluate("SELECT [lat, long] AS lat_long FROM 'a/b'", MQTT, payload)));
        assertEquals("{\"mixed\":[1,\"a\",true,null,\"a\",[2],[]]}",
                text(evaluate("SELECT [1, 'a', TRUE, NULL, topic(1), [2], []] AS mixed FROM 'a/b'", MQTT, payload)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1 IN list                          | true",
            "2 IN list                          | false",
            "NOT 2 IN list                      | true",
            "pair = [9007199254740993.0]        | true",
            "pair = [9007199254740992]          | false",
            "NOT pair = [9007199254740992]      | true",
            "pair <> [9007199254740992]         | true",
            "obj = obj                          | true",
            "NOT obj = obj                      | false",
            "list = [9007199254740993, 1.0]     | true",
            "list = [9007199254740993, 1.00001] | false",
            "pair = [1, 2]                      | false",
            "pair = 'x'                         | false",
            "small = [0.30000000000000004]      | true",
            "small = [0.3]                      | false"
    })
    void comparesNumbersInsideArraysAndObjectsExactly(String predicate, boolean fires) {
        String payload = "{\"list\":[9007199254740993.0, 1],\"pair\":[9007199254740993.0],"
                + "\"obj\":{\"k\":0.30000000000000004},\"small\":[0.30000000000000004]}";

        assertEquals(fires, evaluate("SELECT * FROM 'a/b' WHERE " + predicate, MQTT, payload).isPresent());
    }

    @Test
    void anArrayLiteralWithAnUndefinedElementIsUndefined() {
        assertEquals("{\"lat\":47.606}",
                text(evaluate("SELECT lat, [lat, missing] AS lat_long FROM 'a/b'", MQTT, "{\"lat\":47.606}")));
        assertFalse(evaluate("SELECT * FROM 'a/b' WHERE 1 IN [1, missing]", MQTT, "{}").isPresent());
    }

    private Optional<byte[]> evaluate(String sql, RuleSqlContext context, String payload) {
        return evaluator.evaluate("test-rule", RuleSqlParser.parse(sql), context, payload.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(Optional<byte[]> result) throws Exception {
        return objectMapper.readTree(text(result));
    }

    private String text(Optional<byte[]> result) {
        assertTrue(result.isPresent(), "Expected the rule to fire");
        return new String(result.get(), StandardCharsets.UTF_8);
    }
}
