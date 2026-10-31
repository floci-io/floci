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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The API Gateway usage plan CFN provisioner in isolation, against a mocked {@link ApiGatewayService}. */
class ApiGatewayUsagePlanCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String PLAN = "AWS::ApiGateway::UsagePlan";
    private static final String PLAN_KEY = "AWS::ApiGateway::UsagePlanKey";
    private static final AwsException NOT_FOUND =
            new AwsException("NotFoundException", "Usage Plan not found", 404);
    /** Stage api1:dev throttling /pets/GET at 10/5.0, throttle 20/10.0, quota 1000/0/MONTH. */
    private static final UsagePlan.Limits ORIGINAL_LIMITS = new UsagePlan.Limits(
            List.of(new UsagePlan.ApiStage("api1", "dev", Map.of("/pets/GET", new UsagePlan.Throttle(10, 5.0)))),
            new UsagePlan.Throttle(20, 10.0), new UsagePlan.Quota(1000, 0, "MONTH"));

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayUsagePlanCfnProvisioner provisioner = new ApiGatewayUsagePlanCfnProvisioner(apiGateway);
    private final ObjectMapper mapper = new ObjectMapper();

    /** The locked read hands its reader the plan {@code getUsagePlan} is stubbed with. */
    @BeforeEach
    void readTheStubbedPlan() {
        when(apiGateway.readUsagePlan(any(), any(), any())).thenAnswer(inv -> {
            Function<UsagePlan, Object> reader = inv.getArgument(2);
            return reader.apply(apiGateway.getUsagePlan(inv.getArgument(0), inv.getArgument(1)));
        });
    }

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
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void usagePlanCreateSendsThrottleQuotaAndStageThrottles() {
        when(apiGateway.createUsagePlan(eq(REGION), anyMap())).thenReturn(plan("plan-1", "gold", null, Map.of()));
        ObjectNode props = planProps("gold", null, null);
        props.putObject("Throttle").put("BurstLimit", 10).put("RateLimit", 5);
        props.putObject("Quota").put("Limit", 1000).put("Offset", 2).put("Period", "WEEK");
        ObjectNode stage = ((ArrayNode) props.get("ApiStages")).addObject().put("ApiId", "api1").put("Stage", "dev");
        stage.putObject("Throttle").putObject("/pets/GET").put("BurstLimit", 3).put("RateLimit", 1.5);

        provisioner.provision(resource(PLAN, "Plan", null), props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createUsagePlan(eq(REGION), request.capture());
        assertEquals(Map.of("burstLimit", "10", "rateLimit", "5"), request.getValue().get("throttle"));
        assertEquals(Map.of("limit", "1000", "offset", "2", "period", "WEEK"), request.getValue().get("quota"));
        assertEquals(List.of(Map.of("apiId", "api1", "stage", "dev",
                        "throttle", Map.of("/pets/GET", Map.of("burstLimit", "3", "rateLimit", "1.5")))),
                request.getValue().get("apiStages"));
    }

    @Test
    void aQuotaTheServiceWouldRefuseLeavesThePlanUntouched() {
        UsagePlan existing = plan("plan-1", "gold", null, Map.of());
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        ObjectNode props = planProps("platinum", null, null);
        props.putObject("Quota").put("Limit", 10);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", "plan-1"), props, ctx("plan-1")));

        assertEquals("BadRequestException", e.getErrorCode());
        verify(apiGateway, never()).updateUsagePlan(any(), any(), anyList());
        verify(apiGateway, never()).replaceUsagePlanLimits(any(), any(), any());
    }

    @Test
    void aBlankQuotaOffsetIsRefusedRatherThanReadAsZero() {
        UsagePlan existing = plan("plan-1", "gold", null, Map.of());
        existing.setQuota(new UsagePlan.Quota(10, 3, "WEEK"));
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        ObjectNode props = planProps("gold", null, null);
        props.putObject("Quota").put("Limit", 10).put("Offset", "").put("Period", "WEEK");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", "plan-1"), props, ctx("plan-1")));

        assertEquals("Usage Plan quota offset must be numeric and less than the number of time units in the period",
                e.getMessage());
        verify(apiGateway, never()).replaceUsagePlanLimits(any(), any(), any());
    }

    @Test
    void aBurstLimitThatIsAListIsRefusedRatherThanReadAsZero() {
        UsagePlan existing = plan("plan-1", "gold", null, Map.of());
        existing.setThrottle(new UsagePlan.Throttle(20, 10.0));
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        ObjectNode props = planProps("gold", null, null);
        props.putObject("Throttle").put("RateLimit", 5).putArray("BurstLimit");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", "plan-1"), props, ctx("plan-1")));

        assertEquals("Usage Plan throttle rate limit and burst limit must be numeric and greater than zero",
                e.getMessage());
        verify(apiGateway, never()).replaceUsagePlanLimits(any(), any(), any());
    }

    @Test
    void usagePlanUpdatePatchesNameAndDescriptionAndReplacesTheStages() {
        UsagePlan existing = plan("plan-1", "gold", "old", Map.of(),
                new UsagePlan.ApiStage("api1", "dev"), new UsagePlan.ApiStage("api1", "test"));
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        when(apiGateway.updateUsagePlan(eq(REGION), eq("plan-1"), anyList())).thenReturn(existing);
        when(apiGateway.replaceUsagePlanLimits(eq(REGION), eq("plan-1"), any())).thenReturn(existing);
        StackResource r = resource(PLAN, "Plan", "plan-1");

        provisioner.provision(r, planProps("platinum", "new", null, "api1:dev", "api1:prod"), ctx("plan-1"));

        verify(apiGateway, never()).createUsagePlan(any(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> patches = ArgumentCaptor.forClass(List.class);
        verify(apiGateway).updateUsagePlan(eq(REGION), eq("plan-1"), patches.capture());
        List<Map<String, String>> ops = patches.getValue();
        assertTrue(ops.contains(Map.of("op", "replace", "path", "/name", "value", "platinum")), ops.toString());
        assertTrue(ops.contains(Map.of("op", "replace", "path", "/description", "value", "new")), ops.toString());
        assertEquals(2, ops.size(), ops.toString());
        verify(apiGateway).replaceUsagePlanLimits(REGION, "plan-1", new UsagePlan.Limits(
                List.of(new UsagePlan.ApiStage("api1", "dev"), new UsagePlan.ApiStage("api1", "prod")), null, null));
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
        verify(apiGateway, never()).replaceUsagePlanLimits(any(), any(), any());
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

    private void assertCreateRefused(ObjectNode props, String message) {
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", null), props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals(message, e.getMessage());
        verify(apiGateway, never()).createUsagePlan(any(), anyMap());
    }

    @Test
    void aPlanThrottleThatIsNotAnObjectIsRefused() {
        ObjectNode props = planProps("gold", null, null);
        props.putArray("Throttle").addObject().put("BurstLimit", 10);

        assertCreateRefused(props, "Property [Throttle] expected type: JSONObject, found: JSONArray");
    }

    @Test
    void aQuotaThatIsNotAnObjectIsRefused() {
        ObjectNode props = planProps("gold", null, null);
        props.put("Quota", "1000 a month");

        assertCreateRefused(props, "Property [Quota] expected type: JSONObject, found: String");
    }

    @Test
    void anApiStagesEntryThatIsNotAnObjectIsRefusedByItsIndex() {
        ObjectNode props = planProps("gold", null, null, "api1:dev");
        ((ArrayNode) props.get("ApiStages")).add("api1:prod");

        assertCreateRefused(props, "Property [1] expected type: JSONObject, found: String");
    }

    @Test
    void aStageThrottleThatIsNotAnObjectIsRefused() {
        ObjectNode props = planProps("gold", null, null);
        props.putObject("Throttle").put("BurstLimit", 10).put("RateLimit", 5);
        ((ArrayNode) props.get("ApiStages")).addObject().put("ApiId", "api1").put("Stage", "dev")
                .putArray("Throttle").addObject().put("BurstLimit", 3);

        assertCreateRefused(props, "Property [Throttle] expected type: JSONObject, found: JSONArray");
    }

    @Test
    void aMethodThrottleSettingThatIsNotAnObjectIsRefused() {
        ObjectNode props = planProps("gold", null, null);
        props.putObject("Throttle").put("BurstLimit", 10).put("RateLimit", 5);
        ((ArrayNode) props.get("ApiStages")).addObject().put("ApiId", "api1").put("Stage", "dev")
                .putObject("Throttle").put("/pets/GET", 3);

        assertCreateRefused(props, "Property [/pets/GET] expected type: JSONObject, found: String");
    }

    @Test
    void anUpdateWithAThrottleThatIsNotAnObjectLeavesThePlanUntouched() {
        UsagePlan existing = plan("plan-1", "gold", null, Map.of());
        when(apiGateway.getUsagePlan(REGION, "plan-1")).thenReturn(existing);
        ObjectNode props = planProps("platinum", null, null);
        props.put("Throttle", "fast");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(PLAN, "Plan", "plan-1"), props, ctx("plan-1")));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("Property [Throttle] expected type: JSONObject, found: String", e.getMessage());
        verify(apiGateway, never()).updateUsagePlan(any(), any(), anyList());
        verify(apiGateway, never()).replaceUsagePlanLimits(any(), any(), any());
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

    // ──────────────────────────── Update rollback ────────────────────────────

    /** plan-1 "gold" with no description, tag stack=v1 and {@link #ORIGINAL_LIMITS}. */
    private static UsagePlan originalPlan() {
        UsagePlan plan = plan("plan-1", "gold", null, Map.of("stack", "v1"));
        plan.applyLimits(ORIGINAL_LIMITS);
        return plan;
    }

    /** A template that changes every value of {@link #originalPlan}. */
    private ObjectNode changedPlanProps() {
        ObjectNode props = planProps("platinum", "new", "v2", "api1:prod");
        props.putObject("Throttle").put("BurstLimit", "30").put("RateLimit", "15");
        props.putObject("Quota").put("Limit", "50").put("Offset", "1").put("Period", "WEEK");
        return props;
    }

    /**
     * Stubs the plan's lookups and writes the way the in-memory store behaves: every call sees the one
     * stored plan object, and a write changes that object in place.
     */
    private void storeHolds(UsagePlan stored) {
        when(apiGateway.getUsagePlan(REGION, stored.getId())).thenReturn(stored);
        when(apiGateway.updateUsagePlan(eq(REGION), eq(stored.getId()), anyList())).thenAnswer(inv -> {
            List<Map<String, String>> ops = inv.getArgument(2);
            for (Map<String, String> op : ops) {
                switch (op.get("path")) {
                    case "/name" -> stored.setName(op.get("value"));
                    case "/description" -> stored.setDescription(op.get("value"));
                    default -> throw new IllegalArgumentException(op.toString());
                }
            }
            return stored;
        });
        when(apiGateway.replaceUsagePlanLimits(eq(REGION), eq(stored.getId()), any())).thenAnswer(inv -> {
            stored.applyLimits(inv.getArgument(2));
            return stored;
        });
        when(apiGateway.replaceUsagePlanTags(eq(REGION), eq(stored.getId()), anyMap())).thenAnswer(inv -> {
            Map<String, String> tags = inv.getArgument(2);
            stored.setTags(new HashMap<>(tags));
            return stored;
        });
        when(apiGateway.restoreUsagePlan(eq(REGION), eq(stored.getId()), any(), any(), any(), anyMap()))
                .thenAnswer(inv -> {
                    stored.setName(inv.getArgument(2));
                    stored.setDescription(inv.getArgument(3));
                    stored.applyLimits(inv.getArgument(4));
                    Map<String, String> tags = inv.getArgument(5);
                    stored.setTags(new HashMap<>(tags));
                    return stored;
                });
    }

    private static void assertOriginal(UsagePlan plan) {
        assertEquals("gold", plan.getName());
        assertNull(plan.getDescription());
        assertEquals(ORIGINAL_LIMITS, plan.limits());
        assertEquals(Map.of("stack", "v1"), plan.getTags());
    }

    @Test
    void anUpdateSnapshotsThePlanBeforeItChangesIt() throws Exception {
        storeHolds(originalPlan());
        StackResource r = resource(PLAN, "Plan", "plan-1");

        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));

        JsonNode snapshot = mapper.readTree(r.getAttributes().get(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR));
        assertEquals(REGION, snapshot.path("region").asText());
        assertEquals("plan-1", snapshot.path("id").asText());
        assertEquals("gold", snapshot.path("name").asText());
        assertTrue(snapshot.path("description").isNull(), snapshot.toString());
        assertEquals(ORIGINAL_LIMITS, mapper.convertValue(snapshot.path("limits"), UsagePlan.Limits.class));
        assertEquals(Map.of("stack", "v1"), mapper.convertValue(snapshot.path("tags"), Map.class));
    }

    @Test
    void aFailedStackUpdatePutsThePlanBackAsItWas() {
        UsagePlan stored = originalPlan();
        storeHolds(stored);
        StackResource r = resource(PLAN, "Plan", "plan-1");
        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));
        assertEquals("platinum", stored.getName());
        clearInvocations(apiGateway);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).restoreUsagePlan(REGION, "plan-1", "gold", null, ORIGINAL_LIMITS, Map.of("stack", "v1"));
        assertOriginal(stored);
        assertFalse(r.getAttributes().containsKey(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR));
    }

    // A patch cannot set a description back to none; the service's replace of /description writes "".
    @Test
    void aRollbackRemovesADescriptionThePlanDidNotHave() {
        UsagePlan stored = originalPlan();
        storeHolds(stored);
        StackResource r = resource(PLAN, "Plan", "plan-1");
        ObjectNode describedOnly = planProps("gold", "new", "v1");
        describedOnly.set("ApiStages", mapper.valueToTree(List.of(Map.of("ApiId", "api1", "Stage", "dev",
                "Throttle", Map.of("/pets/GET", Map.of("BurstLimit", "10", "RateLimit", "5.0"))))));
        describedOnly.putObject("Throttle").put("BurstLimit", "20").put("RateLimit", "10.0");
        describedOnly.putObject("Quota").put("Limit", "1000").put("Period", "MONTH");
        provisioner.provision(r, describedOnly, ctx("plan-1"));
        assertEquals("new", stored.getDescription());
        clearInvocations(apiGateway);

        assertTrue(provisioner.rollbackUpdate(r));

        assertNull(stored.getDescription());
        verify(apiGateway, never()).updateUsagePlan(any(), any(), anyList());
    }

    @Test
    void aRollbackWithoutASnapshotTouchesNothing() {
        assertTrue(provisioner.rollbackUpdate(resource(PLAN, "Plan", "plan-1")));
        verifyNoInteractions(apiGateway);
    }

    @Test
    void aSnapshotFromAnEarlierUpdateIsReplacedSoAnUnchangedUpdateLeavesNothingToUndo() {
        UsagePlan stored = originalPlan();
        storeHolds(stored);
        StackResource r = resource(PLAN, "Plan", "plan-1");
        // An earlier update that changed the plan and committed, then one that changes nothing.
        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));
        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));
        clearInvocations(apiGateway);

        assertTrue(provisioner.rollbackUpdate(r));

        verifyNoInteractions(apiGateway);
        assertEquals("platinum", stored.getName());
    }

    // The provisioner does not compare the plan with its snapshot first: a read outside the service's
    // patch lock could mix two revisions, so it always restores, which is idempotent and runs under it.
    @Test
    void aRollbackRestoresThePlanEvenWhenItAlreadyMatchesItsSnapshot() {
        UsagePlan stored = originalPlan();
        storeHolds(stored);
        StackResource r = resource(PLAN, "Plan", "plan-1");
        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));
        UsagePlan original = originalPlan();
        stored.setName(original.getName());
        stored.setDescription(original.getDescription());
        stored.applyLimits(original.limits());
        stored.setTags(original.getTags());
        clearInvocations(apiGateway);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).restoreUsagePlan(REGION, "plan-1", "gold", null, ORIGINAL_LIMITS, Map.of("stack", "v1"));
        assertOriginal(stored);
        assertFalse(r.getAttributes().containsKey(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void aFailedStackUpdateDeletesThePlanItCreatedInPlaceOfOneDeletedOutOfBand() {
        when(apiGateway.getUsagePlan(REGION, "gone")).thenThrow(NOT_FOUND);
        when(apiGateway.createUsagePlan(eq(REGION), anyMap())).thenReturn(plan("plan-2", "gold", null, Map.of()));
        StackResource r = resource(PLAN, "Plan", "gone");
        r.getAttributes().put("Id", "gone");
        provisioner.provision(r, planProps("gold", null, null), ctx("gone"));
        assertEquals("plan-2", r.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).deleteUsagePlan(REGION, "plan-2");
        assertEquals("gone", r.getPhysicalId());
        assertEquals("gone", r.getAttributes().get("Id"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void aFailedWriteDuringTheUpdatePutsTheWholePlanBack() {
        UsagePlan stored = originalPlan();
        storeHolds(stored);
        RuntimeException failure = new IllegalStateException("store write failed");
        // The limits change the stored plan and then fail to save, as a failing store write does.
        doAnswer(inv -> {
            stored.applyLimits(inv.getArgument(2));
            throw failure;
        }).when(apiGateway).replaceUsagePlanLimits(eq(REGION), eq("plan-1"), any());
        StackResource r = resource(PLAN, "Plan", "plan-1");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> provisioner.provision(r, changedPlanProps(), ctx("plan-1")));

        assertSame(failure, thrown);
        verify(apiGateway).restoreUsagePlan(REGION, "plan-1", "gold", null, ORIGINAL_LIMITS, Map.of("stack", "v1"));
        assertOriginal(stored);
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aFailedUndoOfAFailedUpdateMarksTheRollbackFailed() {
        storeHolds(originalPlan());
        RuntimeException failure = new IllegalStateException("store write failed");
        doThrow(failure).when(apiGateway).replaceUsagePlanTags(eq(REGION), eq("plan-1"), anyMap());
        RuntimeException restoreFailure = new IllegalStateException("store write failed again");
        doThrow(restoreFailure).when(apiGateway).restoreUsagePlan(eq(REGION), eq("plan-1"), any(), any(), any(),
                anyMap());
        StackResource r = resource(PLAN, "Plan", "plan-1");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> provisioner.provision(r, changedPlanProps(), ctx("plan-1")));

        assertSame(failure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(restoreFailure));
        String reason = r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
        assertTrue(reason != null && reason.contains("plan-1") && reason.contains("store write failed again"),
                String.valueOf(reason));
    }

    @Test
    void aRestoreRethrowingTheUpdateFailureIsNotSuppressedInItself() {
        storeHolds(originalPlan());
        RuntimeException failure = new IllegalStateException("store write failed");
        // The update and its restore both fail to save the plan with the same exception object.
        doThrow(failure).when(apiGateway).replaceUsagePlanTags(eq(REGION), eq("plan-1"), anyMap());
        doThrow(failure).when(apiGateway).restoreUsagePlan(eq(REGION), eq("plan-1"), any(), any(), any(), anyMap());
        StackResource r = resource(PLAN, "Plan", "plan-1");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> provisioner.provision(r, changedPlanProps(), ctx("plan-1")));

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertTrue(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    // The snapshot is the plan read under the service's patch lock before the first write, not the
    // provisioner's earlier lookup: here a patch renamed the plan between the two, and the rollback
    // puts back the revision the locked read saw.
    @Test
    void theSnapshotIsTheRevisionReadUnderThePatchLockBeforeTheFirstWrite() {
        UsagePlan stored = originalPlan();
        storeHolds(stored);
        UsagePlan patched = originalPlan();
        patched.setName("renamed-by-a-patch");
        doAnswer(inv -> {
            Function<UsagePlan, Object> reader = inv.getArgument(2);
            return reader.apply(patched);
        }).when(apiGateway).readUsagePlan(eq(REGION), eq("plan-1"), any());
        StackResource r = resource(PLAN, "Plan", "plan-1");

        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));

        InOrder order = inOrder(apiGateway);
        order.verify(apiGateway).readUsagePlan(eq(REGION), eq("plan-1"), any());
        order.verify(apiGateway).updateUsagePlan(eq(REGION), eq("plan-1"), anyList());
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals("renamed-by-a-patch", stored.getName());
    }

    // An attempt that failed after its snapshot may have changed the plan, so the engine keeps it for
    // rollbackUpdate; one without a snapshot changed nothing and the engine puts the previous resource back.
    @Test
    void aFailedUpdateIsKeptForItsRollbackOnlyWhileItHasASnapshot() {
        storeHolds(originalPlan());
        StackResource r = resource(PLAN, "Plan", "plan-1");
        assertFalse(provisioner.retainsFailedUpdateState(r));

        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));
        assertTrue(provisioner.retainsFailedUpdateState(r));

        assertTrue(provisioner.rollbackUpdate(r));
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void aCommittedUpdateDropsItsSnapshot() {
        storeHolds(originalPlan());
        StackResource r = resource(PLAN, "Plan", "plan-1");
        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertFalse(result.applicable());
        assertFalse(r.getAttributes().containsKey(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR));
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void aRollbackOfAPlanDeletedSinceTheUpdateFails() {
        storeHolds(originalPlan());
        StackResource r = resource(PLAN, "Plan", "plan-1");
        provisioner.provision(r, changedPlanProps(), ctx("plan-1"));
        doThrow(NOT_FOUND).when(apiGateway).getUsagePlan(REGION, "plan-1");
        doThrow(NOT_FOUND).when(apiGateway).restoreUsagePlan(eq(REGION), eq("plan-1"), any(), any(), any(),
                anyMap());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("NotFoundException", e.getErrorCode());
        assertTrue(r.getAttributes().containsKey(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR));
    }
}
