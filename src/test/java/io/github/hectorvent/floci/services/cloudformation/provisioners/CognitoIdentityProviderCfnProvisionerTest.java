package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoIdentityProviderCfnProvisionerTest {

    private static final String POOL = "us-east-1_original";
    private static final String NEW_POOL = "us-east-1_replacement";
    private static final String NAME = "ExampleOIDC";
    private static final String POOL_ATTR = "__FlociIdentityProviderPoolId";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoIdentityProviderCfnProvisioner provisioner = new CognitoIdentityProviderCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(invocation -> {
            List<String> values = new ArrayList<>();
            JsonNode node = invocation.getArgument(0);
            node.forEach(value -> values.add(value.asText()));
            return values;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack", priorId);
    }

    private ProvisionContext intrinsicContext() {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(
                "000000000000", "us-east-1", "test-stack", mapper, null);
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack");
    }

    private ObjectNode properties(String poolId, String providerName, String providerType, String clientId) {
        ObjectNode properties = mapper.createObjectNode();
        if (poolId != null) {
            properties.put("UserPoolId", poolId);
        }
        if (providerName != null) {
            properties.put("ProviderName", providerName);
        }
        if (providerType != null) {
            properties.put("ProviderType", providerType);
        }
        if (clientId != null) {
            properties.putObject("ProviderDetails")
                    .put("client_id", clientId)
                    .put("oidc_issuer", "https://issuer.example.com");
        }
        return properties;
    }

    private ObjectNode oidc(String poolId, String providerName, String clientId) {
        return properties(poolId, providerName, "OIDC", clientId);
    }

    private static Map<String, String> details(String clientId) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("client_id", clientId);
        details.put("oidc_issuer", "https://issuer.example.com");
        return details;
    }

    private StackResource resource(String providerName, String poolId) {
        StackResource resource = new StackResource();
        resource.setLogicalId("Idp");
        resource.setResourceType("AWS::Cognito::UserPoolIdentityProvider");
        resource.setPhysicalId(providerName);
        resource.setAttributes(new HashMap<>());
        if (poolId != null) {
            resource.getAttributes().put(POOL_ATTR, poolId);
        }
        return resource;
    }

    private static IdentityProvider provider(String poolId, String providerName, String clientId) {
        IdentityProvider provider = new IdentityProvider();
        provider.setUserPoolId(poolId);
        provider.setProviderName(providerName);
        provider.setProviderType("OIDC");
        provider.setProviderDetails(details(clientId));
        provider.setAttributeMapping(new LinkedHashMap<>(Map.of("email", "email")));
        provider.setIdpIdentifiers(new ArrayList<>(List.of("old-idp")));
        return provider;
    }

    @Test
    void servesIdentityProviderType() {
        assertEquals(Set.of("AWS::Cognito::UserPoolIdentityProvider"), provisioner.resourceTypes());
    }

    @Test
    void createsProviderWithResolvedMembersAndSetsRefAndPoolTracking() {
        ObjectNode properties = oidc(POOL, NAME, "client");
        properties.putObject("AttributeMapping").put("email", "email");
        properties.putArray("IdpIdentifiers").add("example.com");
        StackResource resource = resource(null, null);

        provisioner.provision(resource, properties, ctx(null));

        verify(cognito).createIdentityProvider(POOL, NAME, "OIDC", details("client"),
                Map.of("email", "email"), List.of("example.com"));
        assertEquals(NAME, resource.getPhysicalId());
        assertEquals(POOL, resource.getAttributes().get(POOL_ATTR));
        assertFalse(resource.getAttributes().containsKey("ProviderName"));
    }

    @Test
    void absentOptionalMembersReachCognitoAsNullSoItsDefaultsApply() {
        provisioner.provision(resource(null, null), oidc(POOL, NAME, "client"), ctx(null));

        verify(cognito).createIdentityProvider(POOL, NAME, "OIDC", details("client"), null, null);
    }

    @Test
    void anAttributeMappingOfNoValueIsOmitted() {
        ObjectNode properties = oidc(POOL, NAME, "client");
        properties.putObject("AttributeMapping").put("Ref", "AWS::NoValue");

        provisioner.provision(resource(null, null), properties, intrinsicContext());

        verify(cognito).createIdentityProvider(POOL, NAME, "OIDC", details("client"), null, null);
    }

    @Test
    void rejectsMissingRequiredPropertiesBeforeCallingCognito() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                oidc(null, NAME, "client"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                oidc(POOL, null, "client"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(POOL, NAME, null, "client"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                oidc(POOL, NAME, null), ctx(null)));
        verifyNoInteractions(cognito);
    }

    @Test
    void validatesSchemaBoundsBeforeCallingCognito() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                properties(POOL, NAME, "Okta", "client"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                oidc(POOL, "n".repeat(33), "client"), ctx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null),
                oidc("not a pool", NAME, "client"), ctx(null)));
        ObjectNode nestedDetails = oidc(POOL, NAME, null);
        nestedDetails.putObject("ProviderDetails").putObject("client_id");
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null), nestedDetails, ctx(null)));
        ObjectNode tooMany = oidc(POOL, NAME, "client");
        for (int index = 0; index < 51; index++) {
            tooMany.withArray("IdpIdentifiers").add("idp" + index);
        }
        assertThrows(AwsException.class, () -> provisioner.provision(resource(null, null), tooMany, ctx(null)));
        verifyNoInteractions(cognito);
    }

    @Test
    void unchangedIdentityUpdatesInPlaceAndRollbackRestoresTheSnapshot() {
        when(cognito.describeIdentityProvider(POOL, NAME)).thenReturn(provider(POOL, NAME, "old-client"));
        StackResource resource = resource(NAME, POOL);

        provisioner.provision(resource, oidc(POOL, NAME, "new-client"), ctx(NAME));

        verify(cognito).updateIdentityProvider(POOL, NAME, details("new-client"), null, null);
        verify(cognito, never()).createIdentityProvider(any(), any(), any(), any(), any(), any());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.retainsFailedUpdateState(resource));

        assertTrue(provisioner.rollbackUpdate(resource));

        verify(cognito).updateIdentityProvider(POOL, NAME, details("old-client"),
                Map.of("email", "email"), List.of("old-idp"));
        assertEquals(NAME, resource.getPhysicalId());
        assertEquals(POOL, resource.getAttributes().get(POOL_ATTR));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void committingAnInPlaceUpdateDropsItsSnapshot() {
        when(cognito.describeIdentityProvider(POOL, NAME)).thenReturn(provider(POOL, NAME, "old-client"));
        StackResource resource = resource(NAME, POOL);
        provisioner.provision(resource, oidc(POOL, NAME, "new-client"), ctx(NAME));

        assertTrue(provisioner.completeUpdate(resource).complete());

        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
        verify(cognito, never()).deleteIdentityProvider(any(), any());
    }

    @Test
    void changingOnlyTheProviderTypeIsRefusedBeforeChangingAnything() {
        when(cognito.describeIdentityProvider(POOL, NAME)).thenReturn(provider(POOL, NAME, "client"));
        StackResource resource = resource(NAME, POOL);
        HashMap<String, String> priorAttributes = new HashMap<>(resource.getAttributes());

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource,
                properties(POOL, NAME, "SAML", "client"), ctx(NAME)));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("custom-named resource requires replacing"));
        assertEquals(NAME, resource.getPhysicalId());
        assertEquals(priorAttributes, resource.getAttributes());
        verify(cognito, never()).createIdentityProvider(any(), any(), any(), any(), any(), any());
        verify(cognito, never()).updateIdentityProvider(any(), any(), any(), any(), any());
        verify(cognito, never()).deleteIdentityProvider(any(), any());
    }

    @Test
    void changingTheTypeWithANewNameReplacesTheProvider() {
        StackResource resource = resource(NAME, POOL);

        provisioner.provision(resource, properties(POOL, "ExampleSAML", "SAML", "client"), ctx(NAME));

        verify(cognito).createIdentityProvider(POOL, "ExampleSAML", "SAML", details("client"), null, null);
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(cognito).deleteIdentityProvider(POOL, NAME);
    }

    @Test
    void theOriginalTypeUpdatesInPlaceAfterARolledBackTypeAndNameReplacement() {
        when(cognito.describeIdentityProvider(POOL, NAME)).thenReturn(provider(POOL, NAME, "client"));
        StackResource resource = resource(NAME, POOL);
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        provisioner.provision(resource, properties(POOL, "ExampleSAML", "SAML", "client"), ctx(NAME));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(NAME, resource.getPhysicalId());

        provisioner.provision(resource, oidc(POOL, NAME, "updated-client"), ctx(NAME));

        verify(cognito).deleteIdentityProvider(POOL, "ExampleSAML");
        verify(cognito).updateIdentityProvider(POOL, NAME, details("updated-client"), null, null);
        verify(cognito, never()).createIdentityProvider(eq(POOL), eq(NAME), any(), any(), any(), any());
    }

    @Test
    void renamingReplacesAndCommitDeletesTheOldProvider() {
        StackResource resource = resource(NAME, POOL);

        provisioner.provision(resource, oidc(POOL, "RenamedOIDC", "client"), ctx(NAME));

        verify(cognito).createIdentityProvider(POOL, "RenamedOIDC", "OIDC", details("client"), null, null);
        verify(cognito, never()).describeIdentityProvider(any(), any());
        assertEquals("RenamedOIDC", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(NAME, provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(cognito).deleteIdentityProvider(POOL, NAME);
        verify(cognito, never()).deleteIdentityProvider(POOL, "RenamedOIDC");
    }

    @Test
    void changingPoolReplacesEvenWhenRefIsUnchanged() {
        StackResource resource = resource(NAME, POOL);

        provisioner.provision(resource, oidc(NEW_POOL, NAME, "client"), ctx(NAME));

        verify(cognito).createIdentityProvider(NEW_POOL, NAME, "OIDC", details("client"), null, null);
        assertEquals(NAME, resource.getPhysicalId());
        assertEquals(NEW_POOL, resource.getAttributes().get(POOL_ATTR));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(cognito).deleteIdentityProvider(POOL, NAME);
        verify(cognito, never()).deleteIdentityProvider(NEW_POOL, NAME);
    }

    @Test
    void replacementRollbackRestoresPriorIdentityAndDeletesOnlyTheNewProvider() {
        StackResource resource = resource(NAME, POOL);
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        HashMap<String, String> priorAttributes = new HashMap<>(resource.getAttributes());
        provisioner.provision(resource, oidc(NEW_POOL, NAME, "client"), ctx(NAME));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(NAME, resource.getPhysicalId());
        assertEquals(priorAttributes, resource.getAttributes());
        verify(cognito).deleteIdentityProvider(NEW_POOL, NAME);
        verify(cognito, never()).deleteIdentityProvider(POOL, NAME);
    }

    @Test
    void retainingAReplacementDoesNotDeleteThePriorProvider() {
        StackResource resource = resource(NAME, POOL);
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, oidc(POOL, "RenamedOIDC", "client"), ctx(NAME));

        assertNull(provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(cognito, never()).deleteIdentityProvider(any(), any());
    }

    @Test
    void aFailedReplacementCreateLeavesThePriorAddressAndMetadataUnchanged() {
        StackResource resource = resource(NAME, POOL);
        HashMap<String, String> priorAttributes = new HashMap<>(resource.getAttributes());
        AwsException failure = new AwsException("DuplicateProviderException", "exists", 400);
        when(cognito.createIdentityProvider(eq(POOL), eq("RenamedOIDC"), any(), any(), any(), any()))
                .thenThrow(failure);

        assertEquals(failure, assertThrows(AwsException.class, () -> provisioner.provision(resource,
                oidc(POOL, "RenamedOIDC", "client"), ctx(NAME))));

        assertEquals(NAME, resource.getPhysicalId());
        assertEquals(priorAttributes, resource.getAttributes());
        verify(cognito, never()).deleteIdentityProvider(any(), any());
    }

    @Test
    void migratesDispatcherStubByCreatingTheProviderWithoutOwingTheFakeIdADelete() {
        StackResource resource = resource("Idp-1a2b3c4d", null);
        resource.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + resource.getLogicalId());

        provisioner.provision(resource, oidc(POOL, NAME, "client"), ctx(resource.getPhysicalId()));

        verify(cognito).createIdentityProvider(POOL, NAME, "OIDC", details("client"), null, null);
        assertEquals(NAME, resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey("Arn"));
        assertNull(provisioner.updateCleanupPhysicalId(resource));

        provisioner.completeUpdate(resource);

        verify(cognito, never()).deleteIdentityProvider(any(), any());
    }

    @Test
    void deleteUsesStoredPoolAndOnlyToleratesAlreadyMissingProviders() {
        StackResource resource = resource(NAME, POOL);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 400))
                .when(cognito).deleteIdentityProvider(POOL, NAME);
        provisioner.delete(resource, "us-east-1");
        verify(cognito).deleteIdentityProvider(POOL, NAME);

        StackResource failing = resource(NAME, POOL);
        doThrow(new AwsException("InternalErrorException", "storage unavailable", 500))
                .when(cognito).deleteIdentityProvider(POOL, NAME);
        assertThrows(AwsException.class, () -> provisioner.delete(failing, "us-east-1"));
    }
}
