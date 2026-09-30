package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiKey;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The API Gateway API key CFN provisioner in isolation, against a mocked {@link ApiGatewayService}. */
class ApiGatewayApiKeyCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TYPE = "AWS::ApiGateway::ApiKey";

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayApiKeyCfnProvisioner provisioner = new ApiGatewayApiKeyCfnProvisioner(apiGateway);
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

    private static StackResource resource(String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("MyKey");
        r.setResourceType(TYPE);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static ApiKey key(String id, String name, String value, boolean enabled, String description,
                              Map<String, String> tags) {
        ApiKey key = new ApiKey();
        key.setId(id);
        key.setName(name);
        key.setValue(value);
        key.setEnabled(enabled);
        key.setDescription(description);
        key.setTags(new HashMap<>(tags));
        return key;
    }

    private ObjectNode tags(String k, String v) {
        ObjectNode props = mapper.createObjectNode();
        props.putArray("Tags").addObject().put("Key", k).put("Value", v);
        return props;
    }

    @Test
    void createSendsTheTemplatePropertiesAndRecordsTheId() {
        when(apiGateway.createApiKey(eq(REGION), anyMap()))
                .thenReturn(key("abc123", "my-key", "0123456789abcdef0123", false, "a key", Map.of("team", "core")));
        ObjectNode props = tags("team", "core")
                .put("Name", "my-key")
                .put("CustomerId", "marketplace-customer-1")
                .put("Description", "a key")
                .put("Enabled", "false")
                .put("GenerateDistinctId", "true")
                .put("Value", "0123456789abcdef0123");
        StackResource r = resource(null);

        provisioner.provision(r, props, ctx());

        assertEquals("abc123", r.getPhysicalId());
        assertEquals("abc123", r.getAttributes().get("APIKeyId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createApiKey(eq(REGION), request.capture());
        assertEquals("my-key", request.getValue().get("name"));
        assertEquals("marketplace-customer-1", request.getValue().get("customerId"));
        assertEquals("a key", request.getValue().get("description"));
        assertEquals(Boolean.FALSE, request.getValue().get("enabled"));
        assertEquals(Boolean.TRUE, request.getValue().get("generateDistinctId"));
        assertEquals("0123456789abcdef0123", request.getValue().get("value"));
        assertEquals(Map.of("team", "core"), request.getValue().get("tags"));
    }

    @Test
    void unnamedKeyGetsAGeneratedName() {
        when(apiGateway.createApiKey(eq(REGION), anyMap()))
                .thenReturn(key("abc123", "generated", "abc123", true, null, Map.of()));

        provisioner.provision(resource(null), mapper.createObjectNode(), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createApiKey(eq(REGION), request.capture());
        String name = (String) request.getValue().get("name");
        assertTrue(name.startsWith("my-stack-MyKey-"), name);
        assertEquals(Map.of(), request.getValue().get("tags"));
    }

    @Test
    void createWithEnabledOmittedLeavesTheServiceDefault() {
        when(apiGateway.createApiKey(eq(REGION), anyMap()))
                .thenReturn(key("abc123", "my-key", "abc123", false, null, Map.of()));

        provisioner.provision(resource(null), mapper.createObjectNode().put("Name", "my-key"), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createApiKey(eq(REGION), request.capture());
        assertFalse(request.getValue().containsKey("enabled"), request.getValue().toString());
    }

    @Test
    void updateWithEnabledOmittedDisablesAnEnabledKey() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, null, Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);

        provisioner.provision(resource("abc123"), mapper.createObjectNode().put("Name", "my-key"), ctx("abc123"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> patches = ArgumentCaptor.forClass(List.class);
        verify(apiGateway).updateApiKey(eq(REGION), eq("abc123"), patches.capture());
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/enabled".equals(op.get("path")) && "false".equals(op.get("value"))), patches.getValue().toString());
    }

    @Test
    void updatePatchesDescriptionAndEnabledInPlace() {
        ApiKey existing = key("abc123", "my-key", "abc123", false, "old", Map.of());
        existing.setCustomerId("marketplace-customer-1");
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "my-key")
                .put("CustomerId", "marketplace-customer-2")
                .put("Description", "new")
                .put("Enabled", "true");
        StackResource r = resource("abc123");

        provisioner.provision(r, props, ctx("abc123"));

        verify(apiGateway, never()).createApiKey(any(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> patches = ArgumentCaptor.forClass(List.class);
        verify(apiGateway).updateApiKey(eq(REGION), eq("abc123"), patches.capture());
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/description".equals(op.get("path")) && "new".equals(op.get("value"))));
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/enabled".equals(op.get("path")) && "true".equals(op.get("value"))));
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/customerId".equals(op.get("path")) && "marketplace-customer-2".equals(op.get("value"))));
        assertEquals("abc123", r.getPhysicalId());
        assertEquals("abc123", r.getAttributes().get("APIKeyId"));
    }

    @Test
    void droppingDescriptionClearsItOnTheKey() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, "old", Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);

        provisioner.provision(resource("abc123"), mapper.createObjectNode().put("Name", "my-key"), ctx("abc123"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> patches = ArgumentCaptor.forClass(List.class);
        verify(apiGateway).updateApiKey(eq(REGION), eq("abc123"), patches.capture());
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/description".equals(op.get("path")) && op.get("value") == null), patches.getValue().toString());
    }

    @Test
    void anUnchangedUpdateTouchesNothing() {
        ApiKey existing = key("abc123", "my-key", "abc123", false, "same", Map.of("team", "core"));
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        ObjectNode props = tags("team", "core").put("Name", "my-key").put("Description", "same");
        StackResource r = resource("abc123");

        provisioner.provision(r, props, ctx("abc123"));

        verify(apiGateway, never()).createApiKey(any(), anyMap());
        verify(apiGateway, never()).updateApiKey(any(), any(), anyList());
        verify(apiGateway, never()).replaceApiKeyTags(any(), any(), anyMap());
        assertEquals("abc123", r.getAttributes().get("APIKeyId"));
    }

    @Test
    void changedTagsAreReplacedWholesale() {
        ApiKey existing = key("abc123", "my-key", "abc123", false, null, Map.of("team", "core", "stale", "x"));
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.replaceApiKeyTags(eq(REGION), eq("abc123"), anyMap())).thenReturn(existing);

        provisioner.provision(resource("abc123"), tags("team", "platform").put("Name", "my-key"), ctx("abc123"));

        verify(apiGateway).replaceApiKeyTags(REGION, "abc123", Map.of("team", "platform"));
        verify(apiGateway, never()).updateApiKey(any(), any(), anyList());
    }

    @Test
    void aRenameIsRefusedAsReplacementWorthy() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, null, Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("abc123"), mapper.createObjectNode().put("Name", "renamed"), ctx("abc123")));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("Name"), e.getMessage());
        verify(apiGateway, never()).createApiKey(any(), anyMap());
        verify(apiGateway, never()).updateApiKey(any(), any(), anyList());
    }

    @Test
    void aChangedValueIsRefusedAsReplacementWorthy() {
        ApiKey existing = key("abc123", "my-key", "0123456789abcdef0123", true, null, Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        ObjectNode props = mapper.createObjectNode().put("Name", "my-key").put("Value", "fedcba9876543210fedc");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("abc123"), props, ctx("abc123")));

        assertTrue(e.getMessage().contains("Value"), e.getMessage());
    }

    @Test
    void aKeyDeletedOutOfBandIsCreatedAgain() {
        when(apiGateway.findApiKey(REGION, "gone")).thenReturn(Optional.empty());
        when(apiGateway.createApiKey(eq(REGION), anyMap()))
                .thenReturn(key("new1", "my-key", "new1", true, null, Map.of()));
        StackResource r = resource("gone");

        provisioner.provision(r, mapper.createObjectNode().put("Name", "my-key"), ctx("gone"));

        assertEquals("new1", r.getPhysicalId());
        assertEquals("new1", r.getAttributes().get("APIKeyId"));
    }

    @Test
    void aFailedStackUpdatePutsBackEveryValueTheUpdateChanged() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, "old", Map.of("team", "core"));
        existing.setCustomerId("customer-1");
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);
        when(apiGateway.replaceApiKeyTags(eq(REGION), eq("abc123"), anyMap())).thenReturn(existing);
        ObjectNode props = tags("team", "platform")
                .put("Name", "my-key")
                .put("CustomerId", "customer-2")
                .put("Description", "new");
        StackResource r = resource("abc123");
        provisioner.provision(r, props, ctx("abc123"));
        clearInvocations(apiGateway);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).replaceApiKeyTags(REGION, "abc123", Map.of("team", "core"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> patches = ArgumentCaptor.forClass(List.class);
        verify(apiGateway).updateApiKey(eq(REGION), eq("abc123"), patches.capture());
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/customerId".equals(op.get("path")) && "customer-1".equals(op.get("value"))), patches.getValue().toString());
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/description".equals(op.get("path")) && "old".equals(op.get("value"))), patches.getValue().toString());
        assertTrue(patches.getValue().stream().anyMatch(op ->
                "/enabled".equals(op.get("path")) && "true".equals(op.get("value"))), patches.getValue().toString());
    }

    @Test
    void anUnchangedUpdateLeavesNothingForARollbackToUndo() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, "old", Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);
        StackResource r = resource("abc123");
        // An earlier update that changed the key and committed, then one that changes nothing.
        provisioner.provision(r, mapper.createObjectNode().put("Name", "my-key"), ctx("abc123"));
        ObjectNode unchanged = mapper.createObjectNode()
                .put("Name", "my-key")
                .put("Description", "old")
                .put("Enabled", "true");
        provisioner.provision(r, unchanged, ctx("abc123"));
        clearInvocations(apiGateway);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway, never()).updateApiKey(any(), any(), anyList());
        verify(apiGateway, never()).replaceApiKeyTags(any(), any(), anyMap());
    }

    @Test
    void aRefusedTagChangeLeavesTheOtherValuesUnpatched() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, "old", Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);
        when(apiGateway.replaceApiKeyTags(eq(REGION), eq("abc123"), anyMap()))
                .thenThrow(new AwsException("BadRequestException",
                        "Reserved tag key _custom_id_ can only be supplied during resource creation.", 400));
        ObjectNode props = tags("_custom_id_", "other").put("Name", "my-key").put("Description", "new");

        assertThrows(AwsException.class, () -> provisioner.provision(resource("abc123"), props, ctx("abc123")));

        verify(apiGateway, never()).updateApiKey(any(), any(), anyList());
    }

    @Test
    void aFailedPatchPutsTheReplacedTagsBack() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, "old", Map.of("team", "core"));
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        // The service swaps the tag map on the stored key object, which is the one findApiKey returned.
        when(apiGateway.replaceApiKeyTags(eq(REGION), eq("abc123"), anyMap())).thenAnswer(inv -> {
            Map<String, String> replaced = inv.getArgument(2);
            existing.setTags(new HashMap<>(replaced));
            return existing;
        });
        AwsException failure = new AwsException("NotFoundException", "Invalid API Key identifier specified", 404);
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenThrow(failure);
        ObjectNode props = tags("team", "platform")
                .put("Name", "my-key")
                .put("Description", "new")
                .put("Enabled", "true");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("abc123"), props, ctx("abc123")));

        assertSame(failure, thrown);
        InOrder order = inOrder(apiGateway);
        order.verify(apiGateway).replaceApiKeyTags(REGION, "abc123", Map.of("team", "platform"));
        order.verify(apiGateway).replaceApiKeyTags(REGION, "abc123", Map.of("team", "core"));
    }

    @Test
    void aRollbackOfAKeyDeletedSinceTheUpdateFails() {
        ApiKey existing = key("abc123", "my-key", "abc123", true, "old", Map.of());
        when(apiGateway.findApiKey(REGION, "abc123")).thenReturn(Optional.of(existing));
        when(apiGateway.updateApiKey(eq(REGION), eq("abc123"), anyList())).thenReturn(existing);
        StackResource r = resource("abc123");
        provisioner.provision(r, mapper.createObjectNode().put("Name", "my-key"), ctx("abc123"));
        AwsException gone = new AwsException("NotFoundException", "Invalid API Key identifier specified", 404);
        doThrow(gone).when(apiGateway).updateApiKey(eq(REGION), eq("abc123"), anyList());
        doThrow(gone).when(apiGateway).replaceApiKeyTags(eq(REGION), eq("abc123"), anyMap());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("NotFoundException", e.getErrorCode());
    }

    @Test
    void aFailedStackUpdateDeletesTheKeyItCreatedInPlaceOfOneDeletedOutOfBand() {
        when(apiGateway.findApiKey(REGION, "gone")).thenReturn(Optional.empty());
        when(apiGateway.createApiKey(eq(REGION), anyMap()))
                .thenReturn(key("new1", "my-key", "new1", true, null, Map.of()));
        StackResource r = resource("gone");
        r.getAttributes().put("APIKeyId", "gone");
        provisioner.provision(r, mapper.createObjectNode().put("Name", "my-key"), ctx("gone"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).deleteApiKey(REGION, "new1");
        assertEquals("gone", r.getPhysicalId());
        assertEquals("gone", r.getAttributes().get("APIKeyId"));
    }

    @Test
    void deleteDelegatesToTheService() {
        provisioner.delete(TYPE, "abc123", REGION);
        verify(apiGateway).deleteApiKey(REGION, "abc123");
    }

    @Test
    void deleteToleratesAKeyThatIsAlreadyGone() {
        doThrow(new AwsException("NotFoundException", "Invalid API Key identifier specified", 404))
                .when(apiGateway).deleteApiKey(REGION, "gone");

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", REGION));
    }
}
