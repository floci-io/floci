package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiKey;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlanKey;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ApiGateway CFN provisioner in isolation: ApiKey, UsagePlan and UsagePlanKey (issue #17
 * aws-bench gap batch).
 */
class ApiGatewayCfnProvisionerTest {

    private static final String REGION = "us-east-1";

    private final ApiGatewayService apiGatewayService = mock(ApiGatewayService.class);
    private final ApiGatewayCfnProvisioner provisioner = new ApiGatewayCfnProvisioner(apiGatewayService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    // ──────────────────────────── ApiKey ────────────────────────────

    @Test
    void apiKeyCreateSetsPhysicalIdAndGetAttAttributes() {
        ApiKey created = new ApiKey();
        created.setId("abc123");
        when(apiGatewayService.createApiKey(eq(REGION), any())).thenReturn(created);
        StackResource r = resource("AWS::ApiGateway::ApiKey", "MyKey");
        ObjectNode props = mapper.createObjectNode().put("Name", "my-key").put("Description", "a key");

        provisioner.provision(r, props, ctx());

        assertEquals("abc123", r.getPhysicalId());
        assertEquals("abc123", r.getAttributes().get("APIKeyId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(apiGatewayService).createApiKey(eq(REGION), captor.capture());
        assertEquals("my-key", captor.getValue().get("name"));
        assertEquals("a key", captor.getValue().get("description"));
    }

    @Test
    void apiKeyUpdateReconcilesInPlaceInsteadOfRecreating() {
        ApiKey created = new ApiKey();
        created.setId("abc123");
        when(apiGatewayService.createApiKey(eq(REGION), any())).thenReturn(created);
        ApiKey updated = new ApiKey();
        updated.setId("abc123");
        when(apiGatewayService.updateApiKey(eq(REGION), eq("abc123"), any())).thenReturn(updated);

        StackResource r = resource("AWS::ApiGateway::ApiKey", "MyKey");
        provisioner.provision(r, mapper.createObjectNode().put("Name", "my-key"), ctx());

        // UpdateStack re-invokes provision on the same StackResource, which now carries a physicalId.
        provisioner.provision(r, mapper.createObjectNode().put("Name", "renamed-key"), ctx());

        verify(apiGatewayService, times(1)).createApiKey(eq(REGION), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiGatewayService).updateApiKey(eq(REGION), eq("abc123"), captor.capture());
        assertTrue(captor.getValue().stream().anyMatch(op ->
                "/name".equals(op.get("path")) && "renamed-key".equals(op.get("value"))));
        assertEquals("abc123", r.getPhysicalId());
    }

    @Test
    void deleteApiKeyDelegatesToService() {
        provisioner.delete("AWS::ApiGateway::ApiKey", "abc123", REGION);
        verify(apiGatewayService).deleteApiKey(REGION, "abc123");
    }

    // ──────────────────────────── UsagePlan ────────────────────────────

    @Test
    void usagePlanCreateSetsPhysicalIdAndGetAttId() {
        UsagePlan created = new UsagePlan();
        created.setId("plan-1");
        when(apiGatewayService.createUsagePlan(eq(REGION), any())).thenReturn(created);
        StackResource r = resource("AWS::ApiGateway::UsagePlan", "MyPlan");
        ObjectNode props = mapper.createObjectNode().put("UsagePlanName", "my-plan");

        provisioner.provision(r, props, ctx());

        assertEquals("plan-1", r.getPhysicalId());
        assertEquals("plan-1", r.getAttributes().get("Id"));
    }

    @Test
    void usagePlanUpdateDoesNotRecreate() {
        UsagePlan created = new UsagePlan();
        created.setId("plan-1");
        when(apiGatewayService.createUsagePlan(eq(REGION), any())).thenReturn(created);
        StackResource r = resource("AWS::ApiGateway::UsagePlan", "MyPlan");

        provisioner.provision(r, mapper.createObjectNode().put("UsagePlanName", "my-plan"), ctx());
        provisioner.provision(r, mapper.createObjectNode().put("UsagePlanName", "renamed-plan"), ctx());

        verify(apiGatewayService, times(1)).createUsagePlan(eq(REGION), any());
        assertEquals("plan-1", r.getPhysicalId());
        assertEquals("plan-1", r.getAttributes().get("Id"));
    }

    @Test
    void deleteUsagePlanDelegatesToService() {
        provisioner.delete("AWS::ApiGateway::UsagePlan", "plan-1", REGION);
        verify(apiGatewayService).deleteUsagePlan(REGION, "plan-1");
    }

    // ──────────────────────────── UsagePlanKey ────────────────────────────

    @Test
    void usagePlanKeyCreateSetsPhysicalIdAndGetAttId() {
        UsagePlanKey created = new UsagePlanKey("api-key-1", "my-key", "API_KEY", "value123");
        when(apiGatewayService.createUsagePlanKey(eq(REGION), eq("plan-1"), any())).thenReturn(created);
        StackResource r = resource("AWS::ApiGateway::UsagePlanKey", "MyPlanKey");
        ObjectNode props = mapper.createObjectNode()
                .put("UsagePlanId", "plan-1")
                .put("KeyId", "api-key-1")
                .put("KeyType", "API_KEY");

        provisioner.provision(r, props, ctx());

        assertEquals("api-key-1", r.getPhysicalId());
        assertEquals("api-key-1", r.getAttributes().get("Id"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(apiGatewayService).createUsagePlanKey(eq(REGION), eq("plan-1"), captor.capture());
        assertEquals("api-key-1", captor.getValue().get("keyId"));
        assertEquals("API_KEY", captor.getValue().get("keyType"));
    }

    @Test
    void deleteUsagePlanKeyScansEveryPlanAndSwallowsNotAssociated() {
        UsagePlan plan1 = new UsagePlan();
        plan1.setId("plan-1");
        UsagePlan plan2 = new UsagePlan();
        plan2.setId("plan-2");
        when(apiGatewayService.getUsagePlans(REGION)).thenReturn(List.of(plan1, plan2));
        // Key is only associated with plan-2; plan-1 raises NotFound, which must be swallowed.
        org.mockito.Mockito.doThrow(new AwsException("NotFoundException", "Usage Plan Key not found", 404))
                .when(apiGatewayService).deleteUsagePlanKey(REGION, "plan-1", "api-key-1");

        provisioner.delete("AWS::ApiGateway::UsagePlanKey", "api-key-1", REGION);

        verify(apiGatewayService).deleteUsagePlanKey(REGION, "plan-1", "api-key-1");
        verify(apiGatewayService).deleteUsagePlanKey(REGION, "plan-2", "api-key-1");
    }
}
