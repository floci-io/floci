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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack", priorId);
    }

    private ProvisionContext intrinsicContext() {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(
                "000000000000", "us-east-1", "test-stack", mapper, null);
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack");
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

    @ParameterizedTest
    @ValueSource(strings = {"provision", "priorRollback", "delete", "rollback", "updateCleanup", "deleteCleanup"})
    void historicalCleanupAbandonsAnotherStacksCurrentClaimPermanently(String operation) {
        StackResource resource = resource(IDENTIFIER, POOL);
        resource.getAttributes().put("__FlociResourceServerCleanup", mapper.createObjectNode()
                .put("poolId", NEW_POOL).put("identifier", IDENTIFIER).put("retainable", false).toString());
        AtomicReference<List<StackResource>> claims = new AtomicReference<>(List.of(resource(IDENTIFIER, NEW_POOL)));
        CfnResourceContext context = new CfnResourceContext(claims::get);
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        if ("rollback".equals(operation) || "priorRollback".equals(operation)) {
            resource.getAttributes().put("__FlociResourceServerPoolId", NEW_POOL);
            ObjectNode snapshot = mapper.createObjectNode().put("poolId", POOL).put("identifier", IDENTIFIER)
                    .put("name", "Old API").put("replacement", true);
            snapshot.putArray("scopes");
            resource.getAttributes().put("__FlociResourceServerUpdate", snapshot.toString());
        }
        switch (operation) {
            case "provision", "priorRollback" -> {
                ProvisionContext base = ctx(IDENTIFIER);
                provisioner.provision(resource, properties(POOL, IDENTIFIER, "Next API"),
                        new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(),
                                base.priorPhysicalId(), base.progress(), context));
            }
            case "delete" -> provisioner.delete(resource, "us-east-1", context);
            case "rollback" -> assertTrue(provisioner.rollbackUpdate(resource, event -> {}, context));
            case "updateCleanup" -> assertTrue(provisioner.completeUpdate(resource, context).complete());
            case "deleteCleanup" -> assertTrue(provisioner.completeDeleteCleanup(resource, context).complete());
            default -> throw new IllegalArgumentException(operation);
        }
        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
        claims.set(List.of());
        assertFalse(provisioner.completeDeleteCleanup(resource, context).applicable());
        verify(cognito, never()).deleteResourceServer(NEW_POOL, IDENTIFIER);
        if ("delete".equals(operation)) {
            verify(cognito).deleteResourceServer(POOL, IDENTIFIER);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pool", "identifier", "type"})
    void unrelatedCurrentClaimsDoNotBlockHistoricalCleanup(String difference) {
        StackResource resource = resource(IDENTIFIER, POOL);
        resource.getAttributes().put("__FlociResourceServerCleanup", mapper.createObjectNode()
                .put("poolId", NEW_POOL).put("identifier", IDENTIFIER).toString());
        StackResource other = resource(IDENTIFIER, NEW_POOL);
        switch (difference) {
            case "pool" -> other.getAttributes().put("__FlociResourceServerPoolId", POOL);
            case "identifier" -> other.setPhysicalId("https://other.example.com");
            case "type" -> other.setResourceType("AWS::Cognito::UserPool");
            default -> throw new IllegalArgumentException(difference);
        }
        assertTrue(provisioner.completeDeleteCleanup(resource, new CfnResourceContext(() -> List.of(other))).complete());
        verify(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);
        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
    }

    @Test
    void aFailedClaimLookupPreservesHistoricalCleanupForRetry() {
        StackResource resource = resource(IDENTIFIER, POOL);
        String cleanup = mapper.createObjectNode().put("poolId", NEW_POOL).put("identifier", IDENTIFIER).toString();
        resource.getAttributes().put("__FlociResourceServerCleanup", cleanup);
        CfnResourceContext context = new CfnResourceContext(() -> { throw new IllegalStateException("lookup failed"); });
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, "us-east-1", context));
        assertEquals(cleanup, resource.getAttributes().get("__FlociResourceServerCleanup"));
        verifyNoInteractions(cognito);
        UpdateCleanupResult result = provisioner.completeDeleteCleanup(resource, context);
        assertFalse(result.complete());
        assertEquals(1, result.attempts());
        assertTrue(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
        verifyNoInteractions(cognito);
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
    void conditionallyOmittedScopesCreateAnEmptyScopeList() throws Exception {
        ObjectNode properties = properties(POOL, IDENTIFIER, "Example API");
        properties.set("Scopes", mapper.readTree("""
                {"Fn::If": ["IncludeScopes", [{"ScopeName": "read", "ScopeDescription": "Access"}],
                    {"Ref": "AWS::NoValue"}]}
                """));

        provisioner.provision(resource(null, null), properties, intrinsicContext());

        verify(cognito).createResourceServer(POOL, IDENTIFIER, "Example API", List.of());
    }

    @Test
    void conditionallyOmittedScopeEntriesPreserveTheOtherScopes() throws Exception {
        ObjectNode properties = properties(POOL, IDENTIFIER, "Example API");
        properties.set("Scopes", mapper.readTree("""
                [{"ScopeName": "read", "ScopeDescription": "Access"},
                    {"Fn::If": ["IncludeWrite", {"ScopeName": "write", "ScopeDescription": "Write access"},
                        {"Ref": "AWS::NoValue"}]}]
                """));

        provisioner.provision(resource(null, null), properties, intrinsicContext());

        ArgumentCaptor<List<ResourceServerScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(cognito).createResourceServer(eq(POOL), eq(IDENTIFIER), eq("Example API"), scopes.capture());
        assertEquals(1, scopes.getValue().size());
        assertEquals("read", scopes.getValue().getFirst().getScopeName());
    }

    @Test
    void anOmittedEntryInsideAConditionalScopeListCreatesNoScopes() throws Exception {
        ObjectNode properties = properties(POOL, IDENTIFIER, "Example API");
        properties.set("Scopes", mapper.readTree("""
                {"Fn::If": ["UseOtherScopes", [],
                    [{"Fn::If": ["IncludeScopes", {"ScopeName": "read", "ScopeDescription": "Access"},
                        {"Ref": "AWS::NoValue"}]}]]}
                """));

        provisioner.provision(resource(null, null), properties, intrinsicContext());

        verify(cognito).createResourceServer(POOL, IDENTIFIER, "Example API", List.of());
    }

    @Test
    void literalEmptyStringsAndInvalidScopeEntriesAreNotOmitted() throws Exception {
        for (String scopes : List.of("\"\"", "[\"\"]", "[null]", "[false]", "[{}]",
                "[{\"ScopeName\":\"\",\"ScopeDescription\":\"Access\"}]")) {
            ObjectNode properties = properties(POOL, IDENTIFIER, "Example API");
            properties.set("Scopes", mapper.readTree(scopes));

            assertThrows(AwsException.class, () ->
                    provisioner.provision(resource(null, null), properties, intrinsicContext()), scopes);
        }
        verifyNoInteractions(cognito);
    }

    @Test
    void aFailedLaterUpdateCannotRollBackUsingAnOlderSnapshot() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "Committed API"), ctx(IDENTIFIER));
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);

        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                properties(POOL, IDENTIFIER, null), ctx(IDENTIFIER)));

        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerUpdate"));
        assertFalse(provisioner.rollbackUpdate(resource));
        verify(cognito, never()).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
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
    void aPendingRollbackIsRetriedBeforeAnotherAttemptCanReplaceItsSnapshot() {
        ResourceServer original = server(POOL, IDENTIFIER, "Old API");
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(original);
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        doThrow(new AwsException("InternalErrorException", "restore failed", 500))
                .when(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        String pending = resource.getAttributes().get("__FlociResourceServerUpdate");
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "New API"));

        assertThrows(RuntimeException.class, () -> provisioner.provision(resource,
                properties(POOL, IDENTIFIER, "Third API"), ctx(IDENTIFIER)));
        assertEquals(pending, resource.getAttributes().get("__FlociResourceServerUpdate"));
        verify(cognito, never()).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Third API"), any());

        doReturn(original).when(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(original);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "Third API"), ctx(IDENTIFIER));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void cleanupOfASkippedResourceCannotClearItsPendingRollbackSnapshot() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        doThrow(new AwsException("InternalErrorException", "restore failed", 500))
                .when(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        resource.setStatus("UPDATE_FAILED");
        String pending = resource.getAttributes().get("__FlociResourceServerUpdate");

        assertThrows(IllegalStateException.class, () -> provisioner.completeUpdate(resource));

        assertEquals(pending, resource.getAttributes().get("__FlociResourceServerUpdate"));
    }

    @Test
    void aRecoveredReplacementUsesTheRestoredIdentityForTheNextAttempt() {
        String replacementIdentifier = "https://replacement.example.com";
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(NEW_POOL, replacementIdentifier, "New API"), ctx(IDENTIFIER));

        provisioner.provision(resource, properties(POOL, IDENTIFIER, "Third API"), ctx(replacementIdentifier));

        verify(cognito).deleteResourceServer(NEW_POOL, replacementIdentifier);
        verify(cognito).updateResourceServer(POOL, IDENTIFIER, "Third API", List.of());
        verify(cognito, never()).createResourceServer(eq(POOL), eq(IDENTIFIER), any(), any());
        assertEquals(POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
        assertEquals(IDENTIFIER, resource.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
    }

    @Test
    void deletionCleanupKeepsAPendingSnapshotWhenDeletingTheManagedServerFails() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        resource.setStatus("UPDATE_FAILED");
        String pending = resource.getAttributes().get("__FlociResourceServerUpdate");

        provisioner.completeDeleteCleanup(resource);
        provisioner.clearDeleteCleanup(resource);
        assertEquals(pending, resource.getAttributes().get("__FlociResourceServerUpdate"));
        doThrow(new AwsException("InternalErrorException", "delete failed", 500))
                .doNothing().when(cognito).deleteResourceServer(POOL, IDENTIFIER);
        assertThrows(AwsException.class, () -> provisioner.delete(resource, "us-east-1"));
        assertEquals(pending, resource.getAttributes().get("__FlociResourceServerUpdate"));

        provisioner.delete(resource, "us-east-1");

        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(cognito, times(2)).deleteResourceServer(POOL, IDENTIFIER);
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
    void committedReplacementAbandonsOldServerAfterThreeCleanupFailures() throws Exception {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        doThrow(new AwsException("InternalError", "storage unavailable", 500))
                .when(cognito).deleteResourceServer(POOL, IDENTIFIER);

        for (int attempt = 1; attempt <= 3; attempt++) {
            UpdateCleanupResult result = provisioner.completeUpdate(resource);
            assertFalse(result.complete());
            assertEquals(attempt, result.attempts());
        }
        provisioner.clearUpdate(resource);
        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
        assertFalse(provisioner.retainsFailedUpdateState(resource),
                "A committed replacement must not be rolled back by a later update");

        when(cognito.describeResourceServer(NEW_POOL, IDENTIFIER))
                .thenReturn(server(NEW_POOL, IDENTIFIER, "New API"));
        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "Third API"), ctx(IDENTIFIER));

        JsonNode snapshot = mapper.readTree(resource.getAttributes().get("__FlociResourceServerUpdate"));
        assertEquals(NEW_POOL, snapshot.path("poolId").asText());
        assertEquals("New API", snapshot.path("name").asText());
        assertEquals("read", snapshot.path("scopes").get(0).path("scopeName").asText());
        assertFalse(snapshot.path("replacement").asBoolean());
        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
        assertEquals(NEW_POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
        assertEquals(IDENTIFIER, resource.getPhysicalId());
        verify(cognito, times(3)).deleteResourceServer(POOL, IDENTIFIER);
        verify(cognito, never()).deleteResourceServer(NEW_POOL, IDENTIFIER);
        verify(cognito, never()).createResourceServer(eq(POOL), any(), any(), any());
        verify(cognito).createResourceServer(NEW_POOL, IDENTIFIER, "New API", List.of());
        verify(cognito).updateResourceServer(NEW_POOL, IDENTIFIER, "Third API", List.of());

        assertTrue(provisioner.rollbackUpdate(resource));
        ArgumentCaptor<List<ResourceServerScope>> restored = ArgumentCaptor.forClass(List.class);
        verify(cognito).updateResourceServer(eq(NEW_POOL), eq(IDENTIFIER), eq("New API"), restored.capture());
        assertEquals("read", restored.getValue().getFirst().getScopeName());
        assertEquals(NEW_POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));
        verify(cognito, never()).deleteResourceServer(NEW_POOL, IDENTIFIER);
    }

    @Test
    void exhaustedRollbackCleanupIsAbandonedBeforeRetryingCurrentDeletion() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(NEW_POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        doThrow(new AwsException("InternalError", "storage unavailable", 500))
                .when(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));

        for (int attempt = 1; attempt <= 3; attempt++) {
            UpdateCleanupResult result = provisioner.completeDeleteCleanup(resource);
            assertFalse(result.complete());
            assertEquals(attempt, result.attempts());
        }
        provisioner.clearDeleteCleanup(resource);
        provisioner.clearUpdate(resource);
        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
        assertEquals(POOL, resource.getAttributes().get("__FlociResourceServerPoolId"));

        doNothing().when(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);
        assertFalse(provisioner.completeDeleteCleanup(resource).applicable());
        provisioner.delete(resource, "us-east-1");
        verify(cognito, times(4)).deleteResourceServer(NEW_POOL, IDENTIFIER);
        verify(cognito).deleteResourceServer(POOL, IDENTIFIER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedCleanupHooksDoNotRetryHistoricalDeletion(boolean deleting) {
        StackResource resource = resource(IDENTIFIER, POOL);
        resource.getAttributes().put("__FlociResourceServerCleanup", mapper.createObjectNode()
                .put("poolId", NEW_POOL).put("identifier", IDENTIFIER).put("retainable", false)
                .put("attempts", 3).toString());
        doThrow(new AwsException("InternalErrorException", "historical deletion must not be retried", 500))
                .when(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);

        UpdateCleanupResult result = deleting ? provisioner.completeDeleteCleanup(resource)
                : provisioner.completeUpdate(resource);

        assertTrue(result.applicable());
        assertFalse(result.complete());
        assertEquals(3, result.attempts());
        verifyNoInteractions(cognito);
        if (deleting) {
            provisioner.clearDeleteCleanup(resource);
        } else {
            provisioner.clearUpdate(resource);
        }
        assertFalse(resource.getAttributes().containsKey("__FlociResourceServerCleanup"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawProvisionAndDeleteDoNotRetryExhaustedCleanup(boolean deleting) {
        StackResource previous = resource(IDENTIFIER, POOL);
        previous.getAttributes().put("__FlociResourceServerCleanup", mapper.createObjectNode()
                .put("poolId", NEW_POOL).put("identifier", IDENTIFIER).put("retainable", false)
                .put("attempts", 3).toString());
        StackResource attempted = resource(IDENTIFIER, POOL);
        attempted.getAttributes().putAll(previous.getAttributes());
        AwsException currentFailure = new AwsException("InternalErrorException", "current server unavailable", 500);
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenThrow(currentFailure);
        doThrow(currentFailure).when(cognito).deleteResourceServer(POOL, IDENTIFIER);

        AwsException failure = assertThrows(AwsException.class, () -> {
            if (deleting) {
                provisioner.delete(attempted, "us-east-1");
            } else {
                provisioner.provision(attempted, properties(POOL, IDENTIFIER, "Retry API"), ctx(IDENTIFIER));
            }
        });
        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals(currentFailure, failure);
        assertFalse(previous.getAttributes().containsKey("__FlociResourceServerCleanup"));
        verify(cognito, never()).deleteResourceServer(NEW_POOL, IDENTIFIER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearingDeleteCleanupPreservesPendingSnapshotAndUnspentBudget(boolean exhausted) {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        String pending = resource.getAttributes().get("__FlociResourceServerUpdate");
        String cleanup = mapper.createObjectNode().put("poolId", NEW_POOL).put("identifier", IDENTIFIER)
                .put("retainable", false).put("attempts", exhausted ? 3 : 2).toString();
        resource.getAttributes().put("__FlociResourceServerCleanup", cleanup);

        provisioner.clearDeleteCleanup(resource);

        assertEquals(pending, resource.getAttributes().get("__FlociResourceServerUpdate"));
        assertEquals(exhausted ? null : cleanup, resource.getAttributes().get("__FlociResourceServerCleanup"));
        doThrow(new AwsException("InternalErrorException", "current deletion failed", 500))
                .doNothing().when(cognito).deleteResourceServer(POOL, IDENTIFIER);
        assertThrows(AwsException.class, () -> provisioner.deleteAfterCleanup(resource, "us-east-1"));
        assertEquals(pending, resource.getAttributes().get("__FlociResourceServerUpdate"));
        provisioner.deleteAfterCleanup(resource, "us-east-1");
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(cognito, times(2)).deleteResourceServer(POOL, IDENTIFIER);
        verify(cognito, never()).deleteResourceServer(NEW_POOL, IDENTIFIER);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedRetryDoesNotRestoreConsumedOrAbandonedCleanup(boolean claimed) {
        StackResource previous = resource(IDENTIFIER, POOL);
        previous.getAttributes().put("__FlociResourceServerCleanup", mapper.createObjectNode()
                .put("poolId", NEW_POOL).put("identifier", IDENTIFIER).put("retainable", false).toString());
        StackResource attempted = resource(IDENTIFIER, POOL);
        attempted.getAttributes().putAll(previous.getAttributes());
        CfnResourceContext context = new CfnResourceContext(() -> claimed
                ? List.of(resource(IDENTIFIER, NEW_POOL)) : List.of());
        ProvisionContext base = ctx(IDENTIFIER);
        ProvisionContext retry = new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(),
                base.priorPhysicalId(), base.progress(), context);
        when(cognito.describeResourceServer(POOL, IDENTIFIER))
                .thenThrow(new AwsException("ResourceNotFoundException", "current resource server is missing", 400));

        assertThrows(AwsException.class, () -> provisioner.provision(attempted,
                properties(POOL, IDENTIFIER, "Retry API"), retry));
        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertFalse(previous.getAttributes().containsKey("__FlociResourceServerCleanup"));
        assertEquals(IDENTIFIER, previous.getPhysicalId());
        assertEquals(POOL, previous.getAttributes().get("__FlociResourceServerPoolId"));
        verify(cognito, times(claimed ? 0 : 1)).deleteResourceServer(NEW_POOL, IDENTIFIER);
        assertFalse(provisioner.completeDeleteCleanup(previous).applicable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"validation", "lookup", "delete"})
    void aFailedRetryPreservesHistoricalCleanupWhenItWasNotConsumed(String failurePoint) {
        StackResource previous = resource(IDENTIFIER, POOL);
        String cleanup = mapper.createObjectNode().put("poolId", NEW_POOL).put("identifier", IDENTIFIER)
                .put("retainable", false).toString();
        previous.getAttributes().put("__FlociResourceServerCleanup", cleanup);
        StackResource attempted = resource(IDENTIFIER, POOL);
        attempted.getAttributes().putAll(previous.getAttributes());
        CfnResourceContext context = new CfnResourceContext(() -> {
            if ("lookup".equals(failurePoint)) {
                throw new IllegalStateException("lookup failed");
            }
            return List.of();
        });
        if ("delete".equals(failurePoint)) {
            doThrow(new AwsException("InternalErrorException", "delete failed", 500))
                    .when(cognito).deleteResourceServer(NEW_POOL, IDENTIFIER);
        }
        ProvisionContext base = ctx(IDENTIFIER);
        ProvisionContext retry = new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(),
                base.priorPhysicalId(), base.progress(), context);
        String name = "validation".equals(failurePoint) ? null : "Retry API";

        assertThrows(RuntimeException.class, () -> provisioner.provision(attempted,
                properties(POOL, IDENTIFIER, name), retry));
        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals(cleanup, previous.getAttributes().get("__FlociResourceServerCleanup"));
        verify(cognito, never()).describeResourceServer(any(), any());
        verify(cognito, times("delete".equals(failurePoint) ? 1 : 0)).deleteResourceServer(NEW_POOL, IDENTIFIER);
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

    @Test
    void anUpdateUsesTheManagedAddressAndSnapshotsTheOutOfBandConfiguration() throws Exception {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Out of band API"));
        StackResource resource = resource(IDENTIFIER, POOL);

        provisioner.provision(resource, properties(POOL, IDENTIFIER, "Template API"), ctx(IDENTIFIER));

        verify(cognito).updateResourceServer(POOL, IDENTIFIER, "Template API", List.of());
        verify(cognito, never()).createResourceServer(any(), any(), any(), any());
        JsonNode snapshot = mapper.readTree(resource.getAttributes().get("__FlociResourceServerUpdate"));
        assertEquals(POOL, snapshot.path("poolId").asText());
        assertEquals(IDENTIFIER, snapshot.path("identifier").asText());
        assertEquals("Out of band API", snapshot.path("name").asText());
        assertFalse(snapshot.has("incarnationId"));
    }

    @Test
    void snapshotRestorationUsesTheManagedAddressAfterAnOutOfBandChange() {
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Old API"));
        StackResource resource = resource(IDENTIFIER, POOL);
        provisioner.provision(resource, properties(POOL, IDENTIFIER, "New API"), ctx(IDENTIFIER));
        when(cognito.describeResourceServer(POOL, IDENTIFIER)).thenReturn(server(POOL, IDENTIFIER, "Out of band API"));

        assertTrue(provisioner.rollbackUpdate(resource));

        verify(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Old API"), any());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void olderLifecycleRecordsRestoreAndDeleteByAddressWithoutPrivateIncarnationChecks() {
        StackResource resource = resource(IDENTIFIER, POOL);
        resource.getAttributes().put("__FlociResourceServerIncarnationId", "removed-private-value");
        ObjectNode snapshot = mapper.createObjectNode().put("poolId", POOL).put("identifier", IDENTIFIER)
                .put("name", "Original API").put("incarnationId", "removed-private-value")
                .put("replacement", false);
        snapshot.putArray("scopes").addObject().put("scopeName", "read").put("scopeDescription", "Read access");
        resource.getAttributes().put("__FlociResourceServerUpdate", snapshot.toString());

        assertTrue(provisioner.rollbackUpdate(resource));

        ArgumentCaptor<List<ResourceServerScope>> restored = ArgumentCaptor.forClass(List.class);
        verify(cognito).updateResourceServer(eq(POOL), eq(IDENTIFIER), eq("Original API"), restored.capture());
        assertEquals("read", restored.getValue().getFirst().getScopeName());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        provisioner.delete(resource, "us-east-1");
        verify(cognito).deleteResourceServer(POOL, IDENTIFIER);
    }
}
