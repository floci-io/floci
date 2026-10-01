package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoResourceServerCfnProvisionerTest {

    private static final String POOL = "us-east-1_original";
    private static final String NEW_POOL = "us-east-1_replacement";
    private static final String IDENTIFIER = "https://api.example.com";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoResourceServerCfnProvisioner provisioner = new CognitoResourceServerCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack", priorId);
    }

    private ObjectNode properties(String poolId, String identifier, String name) {
        ObjectNode properties = mapper.createObjectNode();
        if (poolId != null) {
            properties.put("UserPoolId", poolId);
        }
        if (identifier != null) {
            properties.put("Identifier", identifier);
        }
        if (name != null) {
            properties.put("Name", name);
        }
        return properties;
    }

    private StackResource resource(String identifier, String poolId) {
        StackResource resource = new StackResource();
        resource.setLogicalId("ApiResourceServer");
        resource.setResourceType("AWS::Cognito::UserPoolResourceServer");
        resource.setPhysicalId(identifier);
        resource.setAttributes(new HashMap<>());
        if (poolId != null) {
            resource.getAttributes().put("__FlociResourceServerPoolId", poolId);
        }
        return resource;
    }

    private static ResourceServer server(String poolId, String identifier, String name) {
        ResourceServer server = new ResourceServer();
        server.setUserPoolId(poolId);
        server.setIdentifier(identifier);
        server.setName(name);
        ResourceServerScope scope = new ResourceServerScope();
        scope.setScopeName("read");
        scope.setScopeDescription("Read access");
        server.setScopes(List.of(scope));
        return server;
    }

    @Test
    void servesResourceServerType() {
        assertEquals(Set.of("AWS::Cognito::UserPoolResourceServer"), provisioner.resourceTypes());
    }

    @Test
    void createsResourceServerWithResolvedScopesAndSetsRefAndPoolTracking() {
        ObjectNode properties = properties(POOL, IDENTIFIER, "Example API");
        ObjectNode scope = properties.putArray("Scopes").addObject();
        scope.put("ScopeName", "read");
        scope.put("ScopeDescription", "Read access");
        StackResource resource = resource(null, null);

        provisioner.provision(resource, properties, ctx(null));

        ArgumentCaptor<List<ResourceServerScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(cognito).createResourceServer(eq(POOL), eq(IDENTIFIER), eq("Example API"), scopes.capture());
        assertEquals("read", scopes.getValue().getFirst().getScopeName());
        assertEquals("Read access", scopes.getValue().getFirst().getScopeDescription());
        assertEquals(IDENTIFIER, resource.getPhysicalId());
        assertEquals(POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
        assertFalse(resource.getAttributes().containsKey("Identifier"));
        assertFalse(resource.getAttributes().containsKey("Name"));
        assertFalse(resource.getAttributes().containsKey("UserPoolId"));
    }

    @Test
    void rejectsMissingRequiredPropertiesAndMalformedScopesBeforeCallingCognito() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(null, IDENTIFIER, "API"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(POOL, null, "API"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(POOL, IDENTIFIER, null), ctx(null)));
        ObjectNode invalid = properties(POOL, IDENTIFIER, "API");
        invalid.putArray("Scopes").addObject().put("ScopeName", "read");
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null), invalid, ctx(null)));
        verifyNoInteractions(cognito);
    }

    @Test
    void aFailedLaterUpdateCannotRollBackUsingAnOlderSnapshot() {
        StackResource resource = resource(IDENTIFIER, POOL);
        resource.getAttributes().put("__FlociResourceServerUpdate", "{\"poolId\":\"old-pool\",\"identifier\":\"old\"}");

        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                properties(POOL, IDENTIFIER, null), ctx(IDENTIFIER)));

        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerUpdate"));
        assertFalse(provisioner.rollbackUpdate(resource));
        verifyNoInteractions(cognito);
    }

    @Test
    void unchangedIdentityUpdatesInPlaceAndRollbackRestoresNameAndScopes() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);

        provisioner.provision(resource, properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));

        verify(cognito).updateResourceServer(POOL, IDENTIFIER, "New API", List.of());
        verify(cognito, never()).createResourceServer(any(), any(), any(), any());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        ArgumentCaptor<List<ResourceServerScope>> restored = ArgumentCaptor.forClass(List.class);
        verify(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), restored.capture());
        assertEquals("read", restored.getValue().getFirst().getScopeName());
        assertEquals(POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
    }

    @Test
    void changingPoolReplacesEvenWhenRefIdentifierIsUnchanged() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);

        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));

        verify(cognito).createResourceServer(NEW_POOL, IDENTIFIER, "New API", List.of());
        assertEquals(IDENTIFIER, resource.getPhysicalId());
        assertEquals(NEW_POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(IDENTIFIER, provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(cognito).deleteResourceServer(POOL, IDENTIFIER);
        verify(cognito, never()).deleteResourceServer(NEW_POOL, IDENTIFIER);
    }

    @Test
    void retainingAReplacementDoesNotDeleteThePriorResource() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));

        assertNull(provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(cognito, never()).deleteResourceServer(any(), any());
    }

    @Test
    void replacementRollbackRestoresPriorIdentityAndDeletesOnlyTheNewServer() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
        verify(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);
        verify(cognito, never()).deleteResourceServer(POOL, IDENTIFIER);
    }

    @Test
    void deleteUsesStoredPoolAndOnlyToleratesAlreadyMissingServers() {
        StackResource resource = resource(IDENTIFIER, POOL);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 400))
                .when(cognito).deleteResourceServer(POOL, IDENTIFIER);
        provisioner.delete(resource, "us-east-1");
        verify(cognito).deleteResourceServer(POOL, IDENTIFIER);

        StackResource failing = resource(IDENTIFIER, POOL);
        doThrow(new AwsException("InternalError", "storage unavailable", 500))
                .when(cognito).deleteResourceServer(POOL, IDENTIFIER);
        assertThrows(AwsException.class, () -> provisioner.delete(failing, "us-east-1"));
    }

    @Test
    void cleanupFailureRemainsPendingForRetry() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        doThrow(new AwsException("InternalError", "storage unavailable", 500))
                .when(cognito).deleteResourceServer(POOL, IDENTIFIER);

        UpdateCleanupResult result = provisioner.completeUpdate(resource);

        assertFalse(result.complete());
        assertEquals(1, result.attempts());
        assertTrue(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
    }

    @Test
    void aPartiallyAppliedUpdateKeepsTheSnapshotForRollback() {
        ResourceServer stored = server(POOL, IDENTIFIER, "Old API");
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(stored);
        doAnswer(invocation -> {
            stored.setName("New API");
            throw new AwsException("InternalErrorException", "write failed", 500);
        }).when(cognito).updateResourceServer(POOL, IDENTIFIER, "New API", List.of());
        StackResource resource = resource(IDENTIFIER, POOL);

        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER)));

        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void failedReplacementRollbackKeepsCleanupTrackingOnTheRestoredResource() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource attempted = resource(IDENTIFIER, POOL);
        provisioner.provision(attempted, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        doThrow(new AwsException("InternalErrorException", "delete failed", 500))
                .when(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(attempted));

        StackResource restored = resource(IDENTIFIER, POOL);
        provisioner.mergeFailedUpdateResourceTracking(restored, attempted);
        assertEquals(POOL, restored.getAttributes().get("__FlociResourceServerPoolId"));
        assertTrue(restored.getAttributes().containsKey("__FlociResourceServerCleanup"));
        assertThrows(AwsException.class, () -> provisioner.delete(restored, "us-east-1"));
        verify(cognito, never()).deleteResourceServer(POOL, IDENTIFIER);
    }

    @Test
    void validatesSchemaBoundsBeforeCallingCognito() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(POOL, "contains a space", "API"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(POOL, IDENTIFIER, "n".repeat(257)), ctx(null)));
        ObjectNode invalidScope = properties(POOL, IDENTIFIER, "API");
        invalidScope.putArray("Scopes").addObject().put("ScopeName", "read/all").put("ScopeDescription", "Access");
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null), invalidScope, ctx(null)));
        ObjectNode tooMany = properties(POOL, IDENTIFIER, "API");
        for (int index = 0; index < 101; index++) {
            tooMany.withArray("Scopes").addObject().put("ScopeName", "s" + index).put("ScopeDescription", "Access");
        }
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null), tooMany, ctx(null)));
        verifyNoInteractions(cognito);
    }
}
