package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iot.model.IotTopicRule;
import io.github.hectorvent.floci.services.iot.rules.RuleSql;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IotTopicRuleSqlIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    IotService iotService;

    @Inject
    IotPublishEventRecorder eventRecorder;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void clearEvents() {
        eventRecorder.clear();
    }

    @Test
    void aWhereClauseFiltersPublishesAndTheProjectionReachesTheAction() {
        createRule("sqlWhereRule", "SELECT *, topic() as topic FROM 'sqltest/where/+/update' "
                + "WHERE endswith(clientToken, ':inbound')", "sqltest/where-out");

        publish("sqltest/where/a/update", "{\"clientToken\":\"job:inbound\",\"topic\":\"stale\"}");
        publish("sqltest/where/b/update", "{\"clientToken\":\"job:outbound\"}");

        assertEquals(List.of("{\"clientToken\":\"job:inbound\",\"topic\":\"sqltest/where/a/update\"}"),
                republished("sqltest/where-out"));
    }

    @Test
    void aStatementOutsideTheSubsetKeepsFiringWithTheWholePayload() {
        createRule("sqlPassthroughRule", "SELECT clientid() as client FROM 'sqltest/passthrough/+'",
                "sqltest/passthrough-out");

        publish("sqltest/passthrough/a", "{\"any\":1}");

        assertEquals(List.of("{\"any\":1}"), republished("sqltest/passthrough-out"));
    }

    @Test
    void selectAllForwardsNonJsonBytesUnchanged() {
        createRule("sqlSelectAllRule", "SELECT * FROM 'sqltest/selectall/+'", "sqltest/selectall-out");

        publish("sqltest/selectall/a", "plain text");

        assertEquals(List.of("plain text"), republished("sqltest/selectall-out"));
    }

    @Test
    void aProjectingStatementSkipsANonJsonPayload() {
        createRule("sqlNonJsonRule", "SELECT *, topic() as topic FROM 'sqltest/nonjson/+'", "sqltest/nonjson-out");

        publish("sqltest/nonjson/a", "plain text");

        assertTrue(republished("sqltest/nonjson-out").isEmpty());
    }

    @Test
    void aStatementIsParsedOnceAndReusedAcrossPublishes() {
        createRule("sqlParseOnceRule", "SELECT *, topic() as topic FROM 'sqltest/parseonce/+'",
                "sqltest/parseonce-out");
        RuleSql parsed = compiledQuery("sqlParseOnceRule");
        assertNotNull(parsed);

        publish("sqltest/parseonce/a", "{\"v\":1}");
        publish("sqltest/parseonce/a", "{\"v\":2}");

        assertEquals(2, republished("sqltest/parseonce-out").size());
        assertSame(parsed, compiledQuery("sqlParseOnceRule"));
    }

    @Test
    void aRuleLoadedFromStorageIsParsedOnItsFirstPublish() {
        createRule("sqlLazyRule", "SELECT * FROM 'sqltest/lazy/+' WHERE endswith(clientToken, 'inbound')",
                "sqltest/lazy-out");
        IotTopicRule stored = iotService.getTopicRule("sqlLazyRule", REGION);
        stored.setCompiledSql(null);

        publish("sqltest/lazy/a", "{\"clientToken\":\"job:outbound\"}");

        assertTrue(republished("sqltest/lazy-out").isEmpty());
        assertNotNull(compiledQuery("sqlLazyRule"));
    }

    @Test
    void replacingARuleReplacesItsParsedStatement() {
        createRule("sqlReplaceRule", "SELECT * FROM 'sqltest/replace/+' WHERE endswith(clientToken, 'one')",
                "sqltest/replace-out");
        publish("sqltest/replace/a", "{\"clientToken\":\"one\"}");
        assertEquals(1, republished("sqltest/replace-out").size());

        iotService.replaceTopicRule("sqlReplaceRule",
                rulePayload("SELECT * FROM 'sqltest/replace/+' WHERE endswith(clientToken, 'two')",
                        "sqltest/replace-out"), REGION);

        publish("sqltest/replace/a", "{\"clientToken\":\"one\"}");
        assertEquals(1, republished("sqltest/replace-out").size());

        publish("sqltest/replace/a", "{\"clientToken\":\"two\"}");
        assertEquals(2, republished("sqltest/replace-out").size());
    }

    @Test
    void disablingAndEnablingARuleKeepsItsParsedStatement() {
        createRule("sqlToggleRule", "SELECT *, topic() as topic FROM 'sqltest/toggle/+'", "sqltest/toggle-out");
        RuleSql parsed = compiledQuery("sqlToggleRule");

        iotService.setTopicRuleEnabled("sqlToggleRule", false, REGION);
        publish("sqltest/toggle/a", "{\"v\":1}");
        assertTrue(republished("sqltest/toggle-out").isEmpty());

        iotService.setTopicRuleEnabled("sqlToggleRule", true, REGION);
        publish("sqltest/toggle/a", "{\"v\":1}");

        assertEquals(1, republished("sqltest/toggle-out").size());
        assertSame(parsed, compiledQuery("sqlToggleRule"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT *, topic() as topic FROM '$aws/things/+/shadow/name/building/update/accepted' WHERE endswith(clientToken, 'inbound') | $aws/things/sensor-1/shadow/name/building/update/accepted | {\"clientToken\":\"job:inbound\"}  | true",
            "SELECT *, topic() as topic FROM '$aws/things/+/shadow/name/building/update/accepted' WHERE endswith(clientToken, 'inbound') | $aws/things/sensor-1/shadow/name/building/update/accepted | {\"clientToken\":\"job:outbound\"} | false",
            "SELECT *, topic() as topic FROM '$aws/things/+/shadow/name/building/update/accepted' WHERE endswith(clientToken, 'inbound') | $aws/things/sensor-1/shadow/name/other/update/accepted    | {\"clientToken\":\"job:inbound\"}  | false",
            "SELECT *, topic(3) AS thingId FROM 'prod/fleet/+/ingest_v1'                                                                | prod/fleet/sensor-1/ingest_v1                             | {\"v\":1}                          | true",
            "SELECT *, topic(3) AS thingId FROM 'prod/fleet/+/ingest_v1'                                                                | prod/fleet/sensor-1/metrics_v1                            | {\"v\":1}                          | false",
            "SELECT * FROM '$aws/events/presence/connected/+' WHERE startswith(clientId, 'gw-')                                         | $aws/events/presence/connected/gw-1                       | {\"clientId\":\"gw-1\"}            | true",
            "SELECT * FROM '$aws/events/presence/connected/+' WHERE startswith(clientId, 'gw-')                                         | $aws/events/presence/connected/other                      | {\"clientId\":\"other\"}           | false",
            "SELECT * FROM 'plant/+/events' WHERE endswith(clientToken, ':ingest:inbound') OR (endswith(clientToken, ':events:outbound') AND startswith(clientToken, 'gateway:')) | plant/a/events | {\"clientToken\":\"gateway:x:events:outbound\"} | true",
            "SELECT * FROM 'plant/+/events' WHERE endswith(clientToken, ':ingest:inbound') OR (endswith(clientToken, ':events:outbound') AND startswith(clientToken, 'gateway:')) | plant/a/events | {\"clientToken\":\"other:x:events:outbound\"}   | false"
    })
    void evaluatesRuleStatementsAgainstTopicsAndPayloads(String sql, String topic, String payload, boolean fires) {
        String target = "sqltest/table-out";
        iotService.createTopicRule("sqlTableRule", rulePayload(sql, target), REGION);
        try {
            publish(topic, payload);

            assertEquals(fires, !republished(target).isEmpty());
        } finally {
            iotService.deleteTopicRule("sqlTableRule", REGION);
        }
    }

    private void createRule(String ruleName, String sql, String targetTopic) {
        iotService.createTopicRule(ruleName, rulePayload(sql, targetTopic), REGION);
    }

    private JsonNode rulePayload(String sql, String targetTopic) {
        try {
            return objectMapper.readTree("""
                    {
                      "sql": %s,
                      "actions": [
                        {
                          "republish": {
                            "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                            "topic": "%s"
                          }
                        }
                      ]
                    }
                    """.formatted(objectMapper.writeValueAsString(sql), targetTopic));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the topic rule payload", e);
        }
    }

    private void publish(String topic, String payload) {
        iotService.handlePublish(topic, payload.getBytes(StandardCharsets.UTF_8), true, REGION);
    }

    private List<String> republished(String targetTopic) {
        return eventRecorder.recentEvents().stream()
                .filter(event -> targetTopic.equals(event.topic()))
                .map(event -> new String(event.payload(), StandardCharsets.UTF_8))
                .toList();
    }

    private RuleSql compiledQuery(String ruleName) {
        return iotService.getTopicRule(ruleName, REGION).getCompiledSql().query();
    }
}
