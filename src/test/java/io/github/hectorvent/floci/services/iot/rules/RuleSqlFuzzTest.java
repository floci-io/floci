package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the parser thousands of deterministic mutations of valid statements and requires that
 * the only exception it ever raises is {@link RuleSqlParseException}, and that whatever it does
 * accept can be evaluated against any payload without an exception at all. The statement text
 * and the payload both come from users, so this is the guarantee that matters most.
 */
class RuleSqlFuzzTest {

    private static final long SEED = 20260904L;
    private static final int MUTANTS_PER_STATEMENT = 400;

    private static final List<String> STATEMENTS = List.of(
            "SELECT * FROM 'devices/+/telemetry'",
            "SELECT color AS my_color, temperature as fahrenheit FROM 'topic/subtopic'",
            "SELECT *, 15 as speed FROM 'topic/subtopic'",
            "SELECT *, topic() as topic FROM '$aws/things/+/shadow/name/building/update/accepted' WHERE endswith(clientToken, 'inbound')",
            "SELECT *, topic(3) AS thingId FROM 'prod/fleet/+/ingest_v1'",
            "SELECT * FROM '$aws/events/presence/connected/+' WHERE startswith(clientId, 'gw-')",
            "SELECT * FROM 'plant/+/events' WHERE endswith(clientToken, ':ingest:inbound') OR (endswith(clientToken, ':events:outbound') AND startswith(clientToken, 'gateway:'))",
            "SELECT state.reported.temperature AS temp FROM '$aws/things/+/shadow/update/accepted' WHERE state.reported.temperature >= 30.5",
            "SELECT * FROM 'a/b' WHERE NOT active = FALSE AND (size > 10 OR weight <= -2.5) AND name <> NULL",
            "select level, name from \"a/b\" where level != 3 and name = \"o''brien\"",
            "SELECT NULL AS n, TRUE AS t, 'x' AS s, 1 AS i FROM 'topic/subtopic'",
            "SELECT * FROM 'a/#' WHERE startswith(topic(2), 'b') AND endswith(topic(), 'c')",
            "SELECT clientid() AS client, timestamp() AS ts, accountid() AS account, newuuid() AS id FROM 'a/b' WHERE clientid() <> 'n/a'",
            "SELECT [a, b, 1, 'x', NULL] AS list, isNull(n) AS n, isUndefined(missing) AS u FROM 'a/b' WHERE a IN arr OR 3 IN [1, 2, 3]");

    private static final String ALPHABET = " \t\n'\"(),.*=<>!-+[]{}$/#_abcXYZ019äé😀\\;:%";

    private static final List<String> TOKENS = List.of(
            "SELECT", "FROM", "WHERE", "AS", "AND", "OR", "NOT", "TRUE", "FALSE", "NULL", "topic()", "topic(2)",
            "topic(0)", "startswith(", "endswith(", "clientid()", "timestamp()", "accountid()", "newuuid()", "isNull(",
            "isUndefined(", "principal()", "'x'", "\"y\"", "''", "42", "-1.5", "1e400", "99999999999999999999",
            "*", ",", "(", ")", ".", "=", "<>", "!=", "<", "<=", ">", ">=", "a.b", "IN", "[", "]", "[1, 'a']", "arr");

    private static final List<String> TOPICS = List.of("a/b/c", "$aws/things/x/shadow/update/accepted", "", "a");

    private static final List<byte[]> PAYLOADS = List.of(
            bytes("{}"),
            bytes("{\"a\":1,\"b\":\"x\",\"c\":true,\"level\":3,\"name\":\"o'brien\",\"clientToken\":\"job:inbound\",\"clientId\":\"gw-1\"}"),
            bytes("{\"state\":{\"reported\":{\"temperature\":31}},\"size\":11,\"weight\":-3,\"active\":false}"),
            bytes("{\"a\":{\"b\":[1,{\"c\":null}]},\"n\":null,\"level\":1e400,\"big\":99999999999999999999999}"),
            bytes("{\"level\":1e-400,\"size\":0.30000000000000004,\"weight\":9007199254740993.0,\"temperature\":-1E-2}"),
            bytes("{\"a\":[],\"b\":{},\"c\":\"\",\"level\":\"3\",\"active\":\"TRUE\"}"),
            bytes("{\"size\":\"1e-2147483648\",\"weight\":\"1e99999999999\",\"level\":\"9e9999999999\",\"temperature\":\"-1e-2147483649\"}"),
            bytes("[1,2,3]"),
            bytes("plain text"),
            bytes(""),
            new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x7b});

    private final RuleSqlEvaluator evaluator = new RuleSqlEvaluator(new ObjectMapper(), Clock.systemUTC());

    @Test
    void theParserOnlyEverThrowsRuleSqlParseExceptionAndTheEvaluatorNeverThrows() {
        Random random = new Random(SEED);
        int parsed = 0;
        int rejected = 0;
        for (String statement : STATEMENTS) {
            for (int end = 0; end <= statement.length(); end++) {
                if (attempt(statement.substring(0, end))) {
                    parsed++;
                } else {
                    rejected++;
                }
            }
            for (int i = 0; i < MUTANTS_PER_STATEMENT; i++) {
                if (attempt(mutate(statement, random))) {
                    parsed++;
                } else {
                    rejected++;
                }
            }
        }
        assertTrue(parsed >= STATEMENTS.size(), "every seed statement and some mutants must parse, got " + parsed);
        assertTrue(rejected > parsed, "most mutants must be rejected, got " + rejected + " rejected vs " + parsed + " parsed");
    }

    @Test
    void everySeedStatementParsesAndEvaluatesAsItIs() {
        for (String statement : STATEMENTS) {
            assertTrue(attempt(statement), statement);
        }
    }

    /**
     * True when the statement parsed. A parse failure is the one exception the parser may raise;
     * anything else, and anything at all from the evaluator, fails the test naming the input.
     */
    private boolean attempt(String sql) {
        RuleSql query;
        try {
            query = RuleSqlParser.parse(sql);
        } catch (RuleSqlParseException expected) {
            return false;
        } catch (RuntimeException unexpected) {
            throw new AssertionError("Parser must only throw RuleSqlParseException but threw " + unexpected
                    + " for: " + sql, unexpected);
        }
        for (String topic : TOPICS) {
            for (byte[] payload : PAYLOADS) {
                assertDoesNotThrow(() -> evaluator.evaluate("fuzz", query,
                                new RuleSqlContext(topic, topic.isEmpty() ? null : "fuzz-client", "000000000000"), payload),
                        () -> "Evaluator threw for statement [" + sql + "] on topic [" + topic + "] with payload "
                                + new String(payload, StandardCharsets.UTF_8));
            }
        }
        return true;
    }

    private static String mutate(String statement, Random random) {
        String current = statement;
        int edits = 1 + random.nextInt(3);
        for (int i = 0; i < edits; i++) {
            current = switch (random.nextInt(5)) {
                case 0 -> deleteChar(current, random);
                case 1 -> insertAt(current, random, String.valueOf(ALPHABET.charAt(random.nextInt(ALPHABET.length()))));
                case 2 -> insertAt(current, random, " " + TOKENS.get(random.nextInt(TOKENS.size())) + " ");
                case 3 -> swapWords(current, random);
                default -> deleteSlice(current, random);
            };
        }
        return current;
    }

    private static String deleteChar(String text, Random random) {
        if (text.isEmpty()) {
            return text;
        }
        int at = random.nextInt(text.length());
        return text.substring(0, at) + text.substring(at + 1);
    }

    private static String insertAt(String text, Random random, String insertion) {
        int at = random.nextInt(text.length() + 1);
        return text.substring(0, at) + insertion + text.substring(at);
    }

    private static String deleteSlice(String text, Random random) {
        if (text.isEmpty()) {
            return text;
        }
        int from = random.nextInt(text.length());
        int to = Math.min(text.length(), from + 1 + random.nextInt(8));
        return text.substring(0, from) + text.substring(to);
    }

    private static String swapWords(String text, Random random) {
        List<String> words = new ArrayList<>(Arrays.asList(text.split(" ")));
        if (words.size() < 2) {
            return text;
        }
        int first = random.nextInt(words.size());
        int second = random.nextInt(words.size());
        String swapped = words.get(first);
        words.set(first, words.get(second));
        words.set(second, swapped);
        return String.join(" ", words);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
