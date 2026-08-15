package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.InsightRule;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchInsightRulesTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFINITION = """
            {"Schema":{"Name":"CloudWatchLogRule","Version":1},\
            "LogGroupNames":["/app/backend"],"LogFormat":"JSON",\
            "Contribution":{"Keys":["$.requestId"]},"AggregateOn":"Count"}""";

    private CloudWatchMetricsService service;
    private CloudWatchMetricsQueryHandler queryHandler;
    private CloudWatchMetricsJsonHandler jsonHandler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"),
                MAPPER
        );
        queryHandler = new CloudWatchMetricsQueryHandler(service);
        jsonHandler = new CloudWatchMetricsJsonHandler(service, MAPPER);
    }

    private MultivaluedMap<String, String> putParams(String name) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("RuleName", name);
        params.add("RuleDefinition", DEFINITION);
        return params;
    }

    @Test
    void putInsightRuleIsEnabledFromTheFirstRead() {
        queryHandler.handle("PutInsightRule", putParams("rule-a"), REGION);

        InsightRule stored = service.getInsightRule("rule-a", REGION);
        assertEquals(InsightRule.STATE_ENABLED, stored.getState());
        assertEquals(DEFINITION, stored.getDefinition());
        assertEquals("{\"Name\":\"CloudWatchLogRule\",\"Version\":1}", stored.getSchema());
        assertEquals("arn:aws:cloudwatch:us-east-1:000000000000:insight-rule/rule-a", stored.getArn());
        assertFalse(stored.isManagedRule());
    }

    @Test
    void putInsightRuleHonoursAnExplicitDisabledState() {
        MultivaluedMap<String, String> params = putParams("rule-b");
        params.add("RuleState", InsightRule.STATE_DISABLED);
        queryHandler.handle("PutInsightRule", params, REGION);

        assertEquals(InsightRule.STATE_DISABLED, service.getInsightRule("rule-b", REGION).getState());
    }

    @Test
    void putInsightRuleReturnsAnEmptyResultElement() {
        String xml = (String) queryHandler.handle("PutInsightRule", putParams("rule-c"), REGION).getEntity();
        assertTrue(xml.contains("<PutInsightRuleResult></PutInsightRuleResult>"));
    }

    @Test
    void describeInsightRulesReturnsEveryRule() {
        queryHandler.handle("PutInsightRule", putParams("rule-d"), REGION);
        queryHandler.handle("PutInsightRule", putParams("rule-e"), REGION);

        String xml = (String) queryHandler.handle("DescribeInsightRules",
                new MultivaluedHashMap<>(), REGION).getEntity();

        assertTrue(xml.contains("<InsightRules>"));
        assertTrue(xml.contains("<Name>rule-d</Name>"));
        assertTrue(xml.contains("<Name>rule-e</Name>"));
        assertTrue(xml.contains("<State>ENABLED</State>"));
        assertTrue(xml.contains("<ManagedRule>false</ManagedRule>"));
    }

    @Test
    void disableAndEnableInsightRulesToggleStateAndReturnEmptyFailures() {
        queryHandler.handle("PutInsightRule", putParams("rule-f"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("RuleNames.member.1", "rule-f");

        String disabled = (String) queryHandler.handle("DisableInsightRules", params, REGION).getEntity();
        assertTrue(disabled.contains("<Failures></Failures>"));
        assertEquals(InsightRule.STATE_DISABLED, service.getInsightRule("rule-f", REGION).getState());

        String enabled = (String) queryHandler.handle("EnableInsightRules", params, REGION).getEntity();
        assertTrue(enabled.contains("<Failures></Failures>"));
        assertEquals(InsightRule.STATE_ENABLED, service.getInsightRule("rule-f", REGION).getState());
    }

    @Test
    void deleteInsightRulesRemovesThemAndReturnsEmptyFailures() {
        queryHandler.handle("PutInsightRule", putParams("rule-g"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("RuleNames.member.1", "rule-g");
        String xml = (String) queryHandler.handle("DeleteInsightRules", params, REGION).getEntity();

        assertTrue(xml.contains("<Failures></Failures>"));
        assertTrue(service.describeInsightRules(REGION).isEmpty());
    }

    @Test
    void getUnknownInsightRuleThrowsResourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.getInsightRule("absent", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void putInsightRuleWithoutADefinitionIsRejected() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("RuleName", "rule-h");
        AwsException e = assertThrows(AwsException.class,
                () -> queryHandler.handle("PutInsightRule", params, REGION));
        assertEquals("MissingRequiredParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void insightRuleTagsAreReadableByListTagsForResource() {
        MultivaluedMap<String, String> params = putParams("rule-i");
        params.add("Tags.member.1.Key", "tofu-estate");
        params.add("Tags.member.1.Value", "probe1");
        queryHandler.handle("PutInsightRule", params, REGION);

        String arn = service.getInsightRule("rule-i", REGION).getArn();
        assertEquals("probe1", service.listTagsForResource(arn, REGION).get("tofu-estate"));

        service.tagResource(arn, Map.of("owner", "platform"), REGION);
        assertEquals("platform", service.listTagsForResource(arn, REGION).get("owner"));

        service.untagResource(arn, List.of("owner"), REGION);
        assertFalse(service.listTagsForResource(arn, REGION).containsKey("owner"));
    }

    @Test
    void jsonHandlerRoundTripsAnInsightRule() throws Exception {
        JsonNode request = MAPPER.createObjectNode()
                .put("RuleName", "json-rule")
                .put("RuleDefinition", DEFINITION)
                .put("ApplyOnTransformedLogs", true);
        jsonHandler.handle("PutInsightRule", request, REGION);

        JsonNode describe = MAPPER.valueToTree(jsonHandler.handle("DescribeInsightRules",
                MAPPER.createObjectNode(), REGION).getEntity());
        JsonNode rule = describe.path("InsightRules").get(0);
        assertEquals("json-rule", rule.path("Name").asText());
        assertEquals("ENABLED", rule.path("State").asText());
        assertEquals(DEFINITION, rule.path("Definition").asText());
        assertEquals("{\"Name\":\"CloudWatchLogRule\",\"Version\":1}", rule.path("Schema").asText());
        assertTrue(rule.path("ApplyOnTransformedLogs").asBoolean());

        JsonNode disabled = MAPPER.valueToTree(jsonHandler.handle("DisableInsightRules",
                MAPPER.readTree("{\"RuleNames\": [\"json-rule\"]}"), REGION).getEntity());
        assertEquals(0, disabled.path("Failures").size());
        assertEquals(InsightRule.STATE_DISABLED, service.getInsightRule("json-rule", REGION).getState());

        JsonNode deleted = MAPPER.valueToTree(jsonHandler.handle("DeleteInsightRules",
                MAPPER.readTree("{\"RuleNames\": [\"json-rule\"]}"), REGION).getEntity());
        assertEquals(0, deleted.path("Failures").size());
        assertTrue(service.describeInsightRules(REGION).isEmpty());
    }
}
