package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlanKey;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The API Gateway usage plan CFN provisioner in isolation, against a mocked {@link ApiGatewayService}. */
class ApiGatewayUsagePlanCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String PLAN = "AWS::ApiGateway::UsagePlan";
    private static final String PLAN_KEY = "AWS::ApiGateway::UsagePlanKey";
    private static final AwsException NOT_FOUND =
            new AwsException("NotFoundException", "Usage Plan not found", 404);

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayUsagePlanCfnProvisioner provisioner = new ApiGatewayUsagePlanCfnProvisioner(apiGateway);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String logicalId, String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static UsagePlan plan(String id, String name, String description, Map<String, String> tags,
                                  UsagePlan.ApiStage... stages) {
        UsagePlan plan = new UsagePlan();
        plan.setId(id);
        plan.setName(name);
        plan.setDescription(description);
        plan.setTags(new HashMap<>(tags));
        plan.getApiStages().addAll(List.of(stages));
        return plan;
    }

    private ObjectNode planProps(String name, String description, String tagValue, String... apiIdStagePairs) {
        ObjectNode props = mapper.createObjectNode();
        if (name != null) {
            props.put("UsagePlanName", name);
        }
        if (description != null) {
            props.put("Description", description);
        }
        if (tagValue != null) {
            props.putArray("Tags").addObject().put("Key", "stack").put("Value", tagValue);
        }
        ArrayNode stages = props.putArray("ApiStages");
        for (String pair : apiIdStagePairs) {
            String[] parts = pair.split(":");
            stages.addObject().put("ApiId", parts[0]).put("Stage", parts[1]);
        }
        return props;
    }

    private ObjectNode keyProps(String planId, String keyId, String keyType) {
        return mapper.createObjectNode().put("UsagePlanId", planId).put("KeyId", keyId).put("KeyType", keyType);
    }

    // ──────────────────────────── UsagePlan ────────────────────────────

    @Test
    void usagePlanCreateSendsNameDescriptionStagesAndTags() {
        when(apiGateway.createUsagePlan(eq(REGION), anyMap()))
                .thenReturn(plan("plan-1", "gold", "a plan", Map.of("stack", "v1")));
        StackResource r = resource(PLAN, "Plan", null);

        provisioner.provision(r, planProps("gold", "a plan", "v1", "api1:dev", "api2:prod"), ctx());

        assertEquals("plan-1", r.getPhysicalId());
        assertEquals("plan-1", r.getAttributes().get("Id"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createUsagePlan(eq(REGION), request.capture());
        assertEquals("gold", request.getValue().get("name"));
        assertEquals("a plan", request.getValue().get("description"));
        assertEquals(Map.of("stack", "v1"), request.getValue().get("tags"));
        assertEquals(List.of(Map.of("apiId", "api1", "stage", "dev"), Map.of("apiId", "api2", "stage", "prod")),
                request.getValue().get("apiStages"));
    }

    @Test
    void usagePlanWithoutANameGetsAGeneratedOne() {
        when(apiGateway.createUsagePlan(eq(REGION), anyMap())).thenReturn(plan("plan-1", "x", null, Map.of()));

        provisioner.provision(resource(PLAN, "Plan", null), mapper.createObjectNode(), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createUsagePlan(eq(REGION), request.capture());
        String name = (String) request.getValue().get("name");
        assertTrue(name.startsWith("my-stack-Plan-"), name);
        assertEquals(List.of(), request.getValue().get("apiStages"));
    }

    @Test
    void aStageWithoutAnApiIdIsRejected() {
        ObjectNode props = mapper.createObjectNode();
        props.putArray("ApiStages").addObject().put("Stage", "dev");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", null), props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verify(apiGateway, never()).createUsagePlan(any(), anyMap());
    }

    @Test
    void usagePlanUpdatePatchesNameDescriptionAndStagesInPlace() {
        UsagePlan existing = plan("plan-1", "gold", "old", Map.of(),
                new UsagePlan.ApiStage("api1", "dev"), new UsagePlan.ApiStage("api1", "test"));
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        when(apiGateway.updateUsagePlan(eq(REGION), eq("plan-1"), anyList())).thenReturn(existing);
        StackResource r = resource(PLAN, "Plan", "plan-1");

        provisioner.provision(r, planProps("platinum", "new", null, "api1:dev", "api1:prod"), ctx("plan-1"));

        verify(apiGateway, never()).createUsagePlan(any(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> patches = ArgumentCaptor.forClass(List.class);
        verify(apiGateway).updateUsagePlan(eq(REGION), eq("plan-1"), patches.capture());
        List<Map<String, String>> ops = patches.getValue();
        assertTrue(ops.contains(Map.of("op", "replace", "path", "/name", "value", "platinum")), ops.toString());
        assertTrue(ops.contains(Map.of("op", "replace", "path", "/description", "value", "new")), ops.toString());
        assertTrue(ops.contains(Map.of("op", "remove", "path", "/apiStages", "value", "api1:test")), ops.toString());
        assertTrue(ops.contains(Map.of("op", "add", "path", "/apiStages", "value", "api1:prod")), ops.toString());
        assertEquals(4, ops.size(), ops.toString());
        assertEquals("plan-1", r.getPhysicalId());
        assertEquals("plan-1", r.getAttributes().get("Id"));
    }

    @Test
    void anUnchangedUsagePlanUpdateTouchesNothing() {
        UsagePlan existing = plan("plan-1", "gold", "same", Map.of("stack", "v1"), new UsagePlan.ApiStage("api1", "dev"));
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);

        provisioner.provision(resource(PLAN, "Plan", "plan-1"), planProps("gold", "same", "v1", "api1:dev"), ctx("plan-1"));

        verify(apiGateway, never()).createUsagePlan(any(), anyMap());
        verify(apiGateway, never()).updateUsagePlan(any(), any(), anyList());
        verify(apiGateway, never()).replaceUsagePlanTags(any(), any(), anyMap());
    }

    @Test
    void changedUsagePlanTagsAreReplacedWholesale() {
        UsagePlan existing = plan("plan-1", "gold", null, Map.of("stack", "v1", "stale", "x"));
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        when(apiGateway.replaceUsagePlanTags(eq(REGION), eq("plan-1"), anyMap())).thenReturn(existing);

        provisioner.provision(resource(PLAN, "Plan", "plan-1"), planProps("gold", null, "v2"), ctx("plan-1"));

        verify(apiGateway).replaceUsagePlanTags(REGION, "plan-1", Map.of("stack", "v2"));
        verify(apiGateway, never()).updateUsagePlan(any(), any(), anyList());
    }

    @Test
    void aUsagePlanDeletedOutOfBandIsCreatedAgain() {
        when(apiGateway.getUsagePlan(REGION, "gone")).thenThrow(NOT_FOUND);
        when(apiGateway.createUsagePlan(eq(REGION), anyMap())).thenReturn(plan("plan-2", "gold", null, Map.of()));
        StackResource r = resource(PLAN, "Plan", "gone");

        provisioner.provision(r, planProps("gold", null, null), ctx("gone"));

        assertEquals("plan-2", r.getPhysicalId());
        assertEquals("plan-2", r.getAttributes().get("Id"));
    }

    @Test
    void deleteUsagePlanDelegatesAndToleratesAMissingPlan() {
        provisioner.delete(PLAN, "plan-1", REGION);
        verify(apiGateway).deleteUsagePlan(REGION, "plan-1");

        doThrow(NOT_FOUND).when(apiGateway).deleteUsagePlan(REGION, "gone");
        assertDoesNotThrow(() -> provisioner.delete(PLAN, "gone", REGION));
    }

    // ──────────────────────────── UsagePlanKey ────────────────────────────

    @Test
    void usagePlanKeyCreateRecordsTheCompositeId() {
        when(apiGateway.createUsagePlanKey(eq(REGION), eq("plan-1"), anyMap()))
                .thenReturn(new UsagePlanKey("key-1", "my-key", "API_KEY", "value"));
        StackResource r = resource(PLAN_KEY, "PlanKey", null);

        provisioner.provision(r, keyProps("plan-1", "key-1", "API_KEY"), ctx());

        assertEquals("key-1:plan-1", r.getPhysicalId());
        assertEquals("key-1:plan-1", r.getAttributes().get("Id"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createUsagePlanKey(eq(REGION), eq("plan-1"), request.capture());
        assertEquals("key-1", request.getValue().get("keyId"));
        assertEquals("API_KEY", request.getValue().get("keyType"));
    }

    @Test
    void anUnchangedUsagePlanKeyUpdateIsANoOp() {
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(plan("plan-1", "gold", null, Map.of()));
        when(apiGateway.getUsagePlanKey(REGION, "plan-1", "key-1"))
                .thenReturn(new UsagePlanKey("key-1", "my-key", "API_KEY", "value"));
        StackResource r = resource(PLAN_KEY, "PlanKey", "key-1:plan-1");

        provisioner.provision(r, keyProps("plan-1", "key-1", "API_KEY"), ctx("key-1:plan-1"));

        verify(apiGateway, never()).createUsagePlanKey(any(), any(), anyMap());
        assertEquals("key-1:plan-1", r.getPhysicalId());
        assertEquals("key-1:plan-1", r.getAttributes().get("Id"));
    }

    @Test
    void aChangedKeyOrPlanIsRefusedAsReplacementWorthy() {
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(plan("plan-1", "gold", null, Map.of()));
        when(apiGateway.getUsagePlanKey(REGION, "plan-1", "key-1"))
                .thenReturn(new UsagePlanKey("key-1", "my-key", "API_KEY", "value"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(PLAN_KEY, "PlanKey", "key-1:plan-1"), keyProps("plan-1", "key-2", "API_KEY"), ctx("key-1:plan-1")));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("KeyId"), e.getMessage());
        verify(apiGateway, never()).createUsagePlanKey(any(), any(), anyMap());
    }

    @Test
    void aUsagePlanKeyGoneFromThePlanIsAssociatedAgain() {
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(plan("plan-1", "gold", null, Map.of()));
        when(apiGateway.getUsagePlanKey(REGION, "plan-1", "key-1"))
                .thenThrow(new AwsException("NotFoundException", "Usage Plan Key not found", 404));
        when(apiGateway.createUsagePlanKey(eq(REGION), eq("plan-1"), anyMap()))
                .thenReturn(new UsagePlanKey("key-1", "my-key", "API_KEY", "value"));
        StackResource r = resource(PLAN_KEY, "PlanKey", "key-1:plan-1");

        provisioner.provision(r, keyProps("plan-1", "key-1", "API_KEY"), ctx("key-1:plan-1"));

        verify(apiGateway).createUsagePlanKey(eq(REGION), eq("plan-1"), anyMap());
        assertEquals("key-1:plan-1", r.getPhysicalId());
    }

    @Test
    void anAssociationWhosePlanIsGoneIsRecreatedOnTheNewPlan() {
        when(apiGateway.getUsagePlan(REGION, "plan-old")).thenThrow(NOT_FOUND);
        when(apiGateway.createUsagePlanKey(eq(REGION), eq("plan-new"), anyMap()))
                .thenReturn(new UsagePlanKey("key-1", "my-key", "API_KEY", "value"));
        StackResource r = resource(PLAN_KEY, "PlanKey", "key-1:plan-old");

        provisioner.provision(r, keyProps("plan-new", "key-1", "API_KEY"), ctx("key-1:plan-old"));

        // The stale association under the deleted plan is not consulted, so the new plan id is not a rename.
        verify(apiGateway, never()).getUsagePlanKey(any(), any(), any());
        verify(apiGateway).createUsagePlanKey(eq(REGION), eq("plan-new"), anyMap());
        assertEquals("key-1:plan-new", r.getPhysicalId());
    }

    @Test
    void aChangedKeyIsRefusedEvenWhileRecoveringADeletedPlan() {
        when(apiGateway.getUsagePlan(REGION, "plan-old")).thenThrow(NOT_FOUND);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(PLAN_KEY, "PlanKey", "key-1:plan-old"), keyProps("plan-new", "key-2", "API_KEY"),
                ctx("key-1:plan-old")));

        assertTrue(e.getMessage().contains("KeyId"), e.getMessage());
        verify(apiGateway, never()).createUsagePlanKey(any(), any(), anyMap());
    }

    @Test
    void aKeyTypeOtherThanApiKeyIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(PLAN_KEY, "PlanKey", null), keyProps("plan-1", "key-1", "BEARER"), ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verify(apiGateway, never()).createUsagePlanKey(any(), any(), anyMap());
    }

    @Test
    void aMissingKeyIdIsRejected() {
        ObjectNode props = mapper.createObjectNode().put("UsagePlanId", "plan-1").put("KeyType", "API_KEY");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(PLAN_KEY, "PlanKey", null), props, ctx()));
        verify(apiGateway, never()).createUsagePlanKey(any(), any(), anyMap());
    }

    @Test
    void apiStagesThatIsNotAListIsRejected() {
        ObjectNode props = mapper.createObjectNode().put("UsagePlanName", "gold").put("ApiStages", "api1:dev");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", null), props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verify(apiGateway, never()).createUsagePlan(any(), anyMap());
    }

    @Test
    void deleteUsagePlanKeySplitsTheIdAndToleratesAMissingAssociation() {
        provisioner.delete(PLAN_KEY, "key-1:plan-1", REGION);
        verify(apiGateway).deleteUsagePlanKey(REGION, "plan-1", "key-1");

        doThrow(new AwsException("NotFoundException", "Usage Plan Key not found", 404))
                .when(apiGateway).deleteUsagePlanKey(REGION, "plan-1", "gone");
        assertDoesNotThrow(() -> provisioner.delete(PLAN_KEY, "gone:plan-1", REGION));
    }
}
