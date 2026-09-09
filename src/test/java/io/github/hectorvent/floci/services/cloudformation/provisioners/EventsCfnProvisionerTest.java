package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Events CFN provisioner in isolation, with only {@link EventBridgeService} mocked. */
class EventsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/my-stack-Rule-ab12cd";

    private final EventBridgeService events = mock(EventBridgeService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventsCfnProvisioner provisioner = new EventsCfnProvisioner(events, mapper);

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setResourceType(type);
        r.setLogicalId(logicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private void stubPutRule() {
        Rule rule = new Rule();
        rule.setArn(RULE_ARN);
        when(events.putRule(anyString(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(rule);
    }

    private ObjectNode target(String id, String arn) {
        return mapper.createObjectNode().put("Id", id).put("Arn", arn);
    }

    private static Target storedTarget(String id) {
        return new Target(id, "arn:aws:sqs:us-east-1:000000000000:q-" + id, null, null);
    }

    // ── the rule keeps its identity across an update ──────────────────────────

    @Test
    void anUnnamedRuleKeepsItsGeneratedNameAcrossUpdates() {
        // The defect: generating unconditionally gave an unnamed rule a fresh random name on every
        // UpdateStack, creating a second rule and leaving the first behind still matching events.
        stubPutRule();
        StackResource r = resource("AWS::Events::Rule", "Rule");

        provisioner.provision(r, mapper.createObjectNode(), ctx("my-stack-Rule-ab12cd"));

        assertEquals("my-stack-Rule-ab12cd", r.getPhysicalId());
        verify(events).putRule(eq("my-stack-Rule-ab12cd"), any(), any(), any(), any(), any(), any(),
                any(), anyString());
    }

    @Test
    void anExplicitlyNamedRuleStillUsesTheTemplateName() {
        stubPutRule();
        StackResource r = resource("AWS::Events::Rule", "Rule");

        provisioner.provision(r, mapper.createObjectNode().put("Name", "orders"), ctx(null));

        assertEquals("orders", r.getPhysicalId());
        assertEquals(RULE_ARN, r.getAttributes().get("Arn"));
    }

    // ── targets are driven to the template's desired state ───────────────────

    @Test
    void aTargetDroppedFromTheTemplateIsRemovedFromTheRule() {
        // PutTargets only upserts by id, so without the sweep a dropped target stayed on the rule
        // and kept receiving events after the template stopped declaring it.
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString()))
                .thenReturn(List.of(storedTarget("keep"), storedTarget("gone")));
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(target("keep", "arn:aws:sqs:us-east-1:000000000000:q-keep"));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx("orders"));

        ArgumentCaptor<List<String>> removed = ArgumentCaptor.forClass(List.class);
        verify(events).removeTargets(eq("orders"), any(), removed.capture(), anyString());
        assertEquals(List.of("gone"), removed.getValue());
    }

    @Test
    void aTemplateThatDeclaresNoTargetsClearsThem() {
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString()))
                .thenReturn(List.of(storedTarget("stale")));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"),
                mapper.createObjectNode().put("Name", "orders"), ctx("orders"));

        ArgumentCaptor<List<String>> removed = ArgumentCaptor.forClass(List.class);
        verify(events).removeTargets(eq("orders"), any(), removed.capture(), anyString());
        assertEquals(List.of("stale"), removed.getValue());
        verify(events, never()).putTargets(anyString(), any(), any(), anyString());
    }

    @Test
    void anUnchangedTargetSetRemovesNothing() {
        stubPutRule();
        when(events.listTargetsByRule(anyString(), any(), anyString()))
                .thenReturn(List.of(storedTarget("keep")));
        ObjectNode props = mapper.createObjectNode().put("Name", "orders");
        props.putArray("Targets").add(target("keep", "arn:aws:sqs:us-east-1:000000000000:q-keep"));

        provisioner.provision(resource("AWS::Events::Rule", "Rule"), props, ctx("orders"));

        verify(events).putTargets(eq("orders"), any(), any(), anyString());
        verify(events, never()).removeTargets(anyString(), any(), any(), anyString());
    }

    // ── a failed policy removal is not swallowed ─────────────────────────────

    @Test
    void anAlreadyGonePolicyStatementCountsAsDeleted() {
        doThrowOnRemovePermission(new AwsException("ResourceNotFoundException",
                "Statement not found: sid-1", 400));

        provisioner.delete("AWS::Events::EventBusPolicy", "orders-bus|sid-1", REGION);

        verify(events).removePermission("orders-bus", "sid-1", false, REGION);
    }

    @Test
    void aFailedPolicyRemovalReachesTheStack() {
        // The previous catch-all logged every failure at debug and returned, so the statement
        // stayed on the bus while the stack still reported DELETE_COMPLETE.
        doThrowOnRemovePermission(new AwsException("ValidationException", "StatementId is required.", 400));

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Events::EventBusPolicy", "orders-bus|sid-1", REGION));

        assertEquals("ValidationException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("StatementId"));
    }

    // ── the event bus reads its prior identity from the provision context ─────

    @Test
    void anEventBusUpdateComparesAgainstThePriorIdFromTheContext() {
        // provision assigns the new id as it runs, so the prior name has to come from the context.
        // Reading it off the resource made a rename look like a create and silently switched buses.
        StackResource r = resource("AWS::Events::EventBus", "Bus");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                r, mapper.createObjectNode().put("Name", "renamed"), ctx("orders-bus")));

        assertTrue(thrown.getMessage().contains("requires resource replacement"));
    }

    // ── the policy statement id is held to the modeled pattern ────────────────

    @Test
    void aStatementIdCarryingTheIdSeparatorIsRejected() {
        // AWS models StatementId as [a-zA-Z0-9-_]+, so a pipe cannot reach the service. Refusing it
        // is parity first, and it also keeps "<bus>|<sid>" splittable back into the pair.
        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Events::EventBusPolicy", "Policy"),
                policyProps("orders-bus|sid"), ctx(null)));

        assertEquals("ValidationException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("StatementId"));
    }

    @Test
    void aStatementIdBeyondTheModeledLengthIsRejected() {
        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Events::EventBusPolicy", "Policy"),
                policyProps("s".repeat(65)), ctx(null)));

        assertEquals("ValidationException", thrown.getErrorCode());
        verify(events, never()).putPermission(anyString(), any(), any(), anyString(), any(), any(), anyString());
    }

    private ObjectNode policyProps(String statementId) {
        return mapper.createObjectNode()
                .put("EventBusName", "orders-bus")
                .put("StatementId", statementId)
                .put("Action", "events:PutEvents")
                .put("Principal", "111122223333");
    }

    private void doThrowOnRemovePermission(AwsException e) {
        org.mockito.Mockito.doThrow(e).when(events)
                .removePermission(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    }
}
