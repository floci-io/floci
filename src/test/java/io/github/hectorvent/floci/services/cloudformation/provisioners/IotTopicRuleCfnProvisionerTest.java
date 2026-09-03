package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotService;
import io.github.hectorvent.floci.services.iot.model.IotTopicRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.REGION;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.ctx;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.notFound;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IoT::TopicRule} through the IoT CFN provisioner in isolation: one mocked service.
 * Every case asserts the exact physical id and the exact {@code Fn::GetAtt} attribute key, because
 * an unmapped type still reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class IotTopicRuleCfnProvisionerTest {

    private static final String TYPE = "AWS::IoT::TopicRule";
    private static final String ARN = "arn:aws:iot:us-east-1:000000000000:rule/telemetryRule";
    private static final String RENAMED_ARN = "arn:aws:iot:us-east-1:000000000000:rule/renamedRule";

    private final IotService iot = mock(IotService.class);
    private final IotCfnProvisioner provisioner = new IotCfnProvisioner(iot);
    private final ObjectMapper mapper = new ObjectMapper();

    private static IotTopicRule rule(String name) {
        IotTopicRule rule = new IotTopicRule();
        rule.setRuleName(name);
        rule.setRuleArn("arn:aws:iot:us-east-1:000000000000:rule/" + name);
        return rule;
    }

    /** The payload as CloudFormation spells it, with an intrinsic where CDK puts one. */
    private ObjectNode templatePayload() {
        ObjectNode payload = mapper.createObjectNode()
                .put("Sql", "SELECT * FROM 'devices/+/telemetry'")
                .put("AwsIotSqlVersion", "2016-03-23")
                .put("RuleDisabled", false)
                .put("Description", "telemetry fan-out");
        ObjectNode functionArn = mapper.createObjectNode();
        functionArn.set("Fn::GetAtt", mapper.createArrayNode().add("Handler").add("Arn"));
        payload.withArray("Actions").addObject().putObject("Lambda").set("FunctionArn", functionArn);
        ObjectNode sqs = payload.withArray("Actions").addObject().putObject("Sqs");
        sqs.put("RoleArn", "arn:aws:iam::000000000000:role/rule").put("UseBase64", false);
        sqs.set("QueueUrl", mapper.createObjectNode().put("Ref", "Queue"));
        payload.putObject("ErrorAction").putObject("Sqs")
                .put("QueueUrl", "http://localhost:4566/000000000000/dlq")
                .put("RoleArn", "arn:aws:iam::000000000000:role/rule");
        return payload;
    }

    private ObjectNode props(String name, ObjectNode payload, Map<String, String> tags) {
        ObjectNode props = mapper.createObjectNode();
        if (name != null) {
            props.put("RuleName", name);
        }
        if (payload != null) {
            props.set("TopicRulePayload", payload);
        }
        if (tags != null) {
            tags.forEach((key, value) -> props.withArray("Tags").addObject().put("Key", key).put("Value", value));
        }
        return props;
    }

    private JsonNode capturedPayload(String ruleName) {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(iot).createTopicRule(eq(ruleName), captor.capture(), eq(REGION));
        return captor.getValue();
    }

    @Test
    void topicRuleHandsTheServiceThePayloadInTheApiShapeAndSetsArn() {
        when(iot.createTopicRule(eq("telemetryRule"), any(), eq(REGION))).thenReturn(rule("telemetryRule"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("telemetryRule", templatePayload(), null), ctx());

        assertEquals("telemetryRule", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        JsonNode payload = capturedPayload("telemetryRule");
        assertEquals("SELECT * FROM 'devices/+/telemetry'", payload.get("sql").asText());
        assertEquals("2016-03-23", payload.get("awsIotSqlVersion").asText());
        assertEquals("telemetry fan-out", payload.get("description").asText());
        assertTrue(payload.get("ruleDisabled").isBoolean());
        assertFalse(payload.get("ruleDisabled").asBoolean());
        assertEquals("resolved:Handler.Arn", payload.at("/actions/0/lambda/functionArn").asText());
        assertEquals("resolved:Queue", payload.at("/actions/1/sqs/queueUrl").asText());
        assertTrue(payload.at("/actions/1/sqs/useBase64").isBoolean());
        assertEquals("http://localhost:4566/000000000000/dlq", payload.at("/errorAction/sqs/queueUrl").asText());
        assertFalse(payload.has("Sql") || payload.has("Actions") || payload.has("ErrorAction"),
                "no PascalCase keys may reach the service: " + payload);
    }

    @Test
    void topicRuleDropsNullPayloadMembers() {
        when(iot.createTopicRule(eq("telemetryRule"), any(), eq(REGION))).thenReturn(rule("telemetryRule"));
        ObjectNode payload = mapper.createObjectNode().put("Sql", "SELECT * FROM 'a'");
        payload.putNull("Description");
        payload.withArray("Actions").addObject().putObject("Lambda").put("FunctionArn", "arn:fn");
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("telemetryRule", payload, null), ctx());

        assertFalse(capturedPayload("telemetryRule").has("description"));
    }

    @Test
    void topicRuleRequiresAPayload() {
        StackResource r = resource(TYPE, "Rule");
        ObjectNode props = props("telemetryRule", null, null);

        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(r, props, ctx()));
        verify(iot, never()).createTopicRule(anyString(), any(), anyString());
    }

    @Test
    void topicRuleWithoutNameGetsAGeneratedNameWithoutHyphens() {
        when(iot.createTopicRule(anyString(), any(), eq(REGION))).thenAnswer(inv -> rule(inv.getArgument(0)));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props(null, templatePayload(), null), ctx());

        assertTrue(r.getPhysicalId().matches("mystackRule[0-9a-f]{12}"), "generated rule name: " + r.getPhysicalId());
        assertEquals("arn:aws:iot:us-east-1:000000000000:rule/" + r.getPhysicalId(), r.getAttributes().get("Arn"));
    }

    @Test
    void topicRuleHandsAnExplicitNameToTheServiceUnchanged() {
        // Only generated names are trimmed to the characters a rule name allows; validating an
        // explicit name is the service's job, so it must not be altered on the way there.
        when(iot.createTopicRule(eq("telemetry-rule"), any(), eq(REGION))).thenReturn(rule("telemetry-rule"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("telemetry-rule", templatePayload(), null), ctx());

        assertEquals("telemetry-rule", r.getPhysicalId());
        verify(iot).createTopicRule(eq("telemetry-rule"), any(), eq(REGION));
    }

    @Test
    void topicRuleCreateAppliesTheTemplateTags() {
        when(iot.createTopicRule(eq("telemetryRule"), any(), eq(REGION))).thenReturn(rule("telemetryRule"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("telemetryRule", templatePayload(), Map.of("env", "test")), ctx());

        verify(iot).tagResource(ARN, Map.of("env", "test"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void topicRuleUpdateWithTheSameNameReplacesTheRule() {
        when(iot.replaceTopicRule(eq("telemetryRule"), any(), eq(REGION))).thenReturn(rule("telemetryRule"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("telemetryRule", templatePayload(), null), ctx("telemetryRule"));

        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(iot).replaceTopicRule(eq("telemetryRule"), captor.capture(), eq(REGION));
        assertEquals("SELECT * FROM 'devices/+/telemetry'", captor.getValue().get("sql").asText());
        verify(iot, never()).createTopicRule(anyString(), any(), anyString());
        verify(iot, never()).deleteTopicRule(anyString(), anyString());
        assertEquals("telemetryRule", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void topicRuleUpdateWithoutAnExplicitNameKeepsThePriorGeneratedName() {
        when(iot.replaceTopicRule(eq("mystackRule0123456789ab"), any(), eq(REGION))).thenReturn(rule("mystackRule0123456789ab"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props(null, templatePayload(), null), ctx("mystackRule0123456789ab"));

        verify(iot, never()).createTopicRule(anyString(), any(), anyString());
        assertEquals("mystackRule0123456789ab", r.getPhysicalId());
    }

    @Test
    void topicRuleUpdateDrivesTheTagsToTheTemplate() {
        when(iot.replaceTopicRule(eq("telemetryRule"), any(), eq(REGION))).thenReturn(rule("telemetryRule"));
        when(iot.listTagsForResource(ARN)).thenReturn(Map.of("stale", "1"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("telemetryRule", templatePayload(), Map.of("env", "test")), ctx("telemetryRule"));

        verify(iot).untagResource(ARN, List.of("stale"));
        verify(iot).tagResource(ARN, Map.of("env", "test"));
    }

    @Test
    void topicRuleRenameCreatesTheReplacementThenDeletesThePriorRule() {
        when(iot.createTopicRule(eq("renamedRule"), any(), eq(REGION))).thenReturn(rule("renamedRule"));
        StackResource r = resource(TYPE, "Rule");

        provisioner.provision(r, props("renamedRule", templatePayload(), null), ctx("telemetryRule"));

        verify(iot).deleteTopicRule("telemetryRule", REGION);
        verify(iot, never()).replaceTopicRule(anyString(), any(), anyString());
        assertEquals("renamedRule", r.getPhysicalId());
        assertEquals(RENAMED_ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void topicRuleReplacementRemovesTheNewRuleWhenTaggingItFails() {
        when(iot.createTopicRule(eq("renamedRule"), any(), eq(REGION))).thenReturn(rule("renamedRule"));
        doThrow(new AwsException("InternalFailureException", "tagging failed", 500)).when(iot).tagResource(eq(RENAMED_ARN), any());
        StackResource r = resource(TYPE, "Rule");
        ObjectNode props = props("renamedRule", templatePayload(), Map.of("env", "test"));

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("telemetryRule")));

        verify(iot).deleteTopicRule("renamedRule", REGION);
        verify(iot, never()).deleteTopicRule("telemetryRule", REGION);
    }

    @Test
    void deleteTopicRuleToleratesOnlyNotFound() {
        doThrow(notFound("Topic rule")).when(iot).deleteTopicRule("gone", REGION);
        doThrow(new AwsException("InternalFailureException", "storage", 500)).when(iot).deleteTopicRule("stuck", REGION);

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", REGION));
        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "stuck", REGION));
    }
}
