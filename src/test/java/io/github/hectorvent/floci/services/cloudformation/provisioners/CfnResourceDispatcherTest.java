package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dispatch itself: what reaches the owning provisioner and how Cloud Control's stack-less
 * path is wired. The stub arm and strict mode are pinned by
 * {@code CloudFormationUnsupportedResourceTypeTest}.
 */
class CfnResourceDispatcherTest {

    private static final String TYPE = "AWS::Test::Thing";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudFormationResourceRegistry registry = mock(CloudFormationResourceRegistry.class);
    private final CfnDynamicReferences dynamicReferences = mock(CfnDynamicReferences.class);
    private final CfnResourceDispatcher dispatcher =
            new CfnResourceDispatcher(mapper, registry, dynamicReferences, null);

    /**
     * A type the registry routes to the generic custom-resource handler is a custom resource: the
     * handler's own type and an unregistered {@code Custom::} type. An exactly registered
     * {@code Custom::} type with its own provisioner, any other type, a missing type, and every type
     * of a registry without the handler, are not.
     */
    @Test
    void onlyTypesRoutedToTheCustomResourceHandlerAreCustomResources() {
        CfnResourceDispatcher routed = new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(
                List.of(serving("AWS::CloudFormation::CustomResource"), serving("Custom::DynamoDBReplica"),
                        serving(TYPE))), dynamicReferences, null);
        CfnResourceDispatcher withoutHandler = new CfnResourceDispatcher(mapper,
                new CloudFormationResourceRegistry(List.of(serving(TYPE))), dynamicReferences, null);

        assertTrue(routed.isCustomResourceType("AWS::CloudFormation::CustomResource"));
        assertTrue(routed.isCustomResourceType("Custom::Namer"));
        assertFalse(routed.isCustomResourceType("Custom::DynamoDBReplica"));
        assertFalse(routed.isCustomResourceType(TYPE));
        assertFalse(routed.isCustomResourceType(null));
        assertFalse(withoutHandler.isCustomResourceType("Custom::Namer"));
    }

    /**
     * Each provisioner says how its own types' physical ids relate to a parent's: a bucket policy,
     * secret target attachment, function URL and capacity provider associations take their
     * parent's id; a web ACL, IP set and custom action type id names no parent. Every other type,
     * a missing type and a type nothing serves keep the defaults.
     */
    @Test
    void provisionersSayHowTheirIdsRelateToAParent() {
        CfnResourceDispatcher routed = new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(
                List.of(real(S3CfnProvisioner.class), real(SecretTargetAttachmentCfnProvisioner.class),
                        real(LambdaAddressingCfnProvisioner.class), real(EcsCapacityCfnProvisioner.class),
                        real(WafV2CfnProvisioner.class), real(CodePipelineCfnProvisioner.class),
                        serving("AWS::CloudFormation::CustomResource"))),
                dynamicReferences, null);

        for (String type : List.of("AWS::S3::BucketPolicy", "AWS::SecretsManager::SecretTargetAttachment",
                "AWS::Lambda::Url", "AWS::ECS::ClusterCapacityProviderAssociations")) {
            assertTrue(routed.takesParentPhysicalId(type), type);
            assertTrue(routed.idPrefixCanNameParent(type), type);
        }
        for (String type : List.of("AWS::WAFv2::WebACL", "AWS::WAFv2::IPSet", "AWS::CodePipeline::CustomActionType")) {
            assertFalse(routed.takesParentPhysicalId(type), type);
            assertFalse(routed.idPrefixCanNameParent(type), type);
        }
        for (String type : Arrays.asList("AWS::S3::Bucket", "AWS::Lambda::Permission", "AWS::ECS::CapacityProvider",
                "AWS::WAFv2::WebACLAssociation", "AWS::CodePipeline::Pipeline", "Custom::Namer", TYPE, null)) {
            assertFalse(routed.takesParentPhysicalId(type), String.valueOf(type));
            assertTrue(routed.idPrefixCanNameParent(type), String.valueOf(type));
        }
    }

    /** A provisioner built without its dependencies: only what it says of its types is called on it. */
    private static <T extends CfnResourceProvisioner> T real(Class<T> type) {
        return mock(type, CALLS_REAL_METHODS);
    }

    @Test
    void provisionStandaloneResolvesDynamicReferencesThroughTheService() {
        // Cloud Control desired state can carry {{resolve:...}}; the throwaway engine must reach
        // the same resolver a stack's engine does.
        when(dynamicReferences.resolveDynamicReferences("{{resolve:ssm:/p}}", "us-east-1", false))
                .thenReturn("resolved");
        AtomicReference<String> seen = new AtomicReference<>();
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) -> {
            seen.set(ctx.engine().resolve(mapper.getNodeFactory().textNode("{{resolve:ssm:/p}}")));
            resource.setPhysicalId("thing-1");
        })));

        StackResource resource = dispatcher.provisionStandalone(TYPE, mapper.createObjectNode(),
                "us-east-1", "000000000000");

        assertEquals("resolved", seen.get());
        assertEquals("thing-1", resource.getPhysicalId());
        assertEquals("CREATE_COMPLETE", resource.getStatus());
    }

    @Test
    void provisionSeedsTheResourceAndTheContextWithThePriorIdentity() {
        AtomicReference<ProvisionContext> seenContext = new AtomicReference<>();
        AtomicReference<Map<String, String>> seenAttributes = new AtomicReference<>();
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) -> {
            seenContext.set(ctx);
            seenAttributes.set(Map.copyOf(resource.getAttributes()));
        })));

        StackResource resource = dispatcher.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "thing-prior", Map.of("Arn", "arn-prior"));

        assertEquals("thing-prior", seenContext.get().priorPhysicalId());
        assertEquals("my-stack", seenContext.get().stackName());
        assertEquals(Map.of("Arn", "arn-prior"), seenAttributes.get());
        assertEquals("thing-prior", resource.getPhysicalId(), "an owner that sets nothing keeps the prior id");
        assertEquals("CREATE_COMPLETE", resource.getStatus());
    }

    @Test
    void provisionHandsThePriorPropertiesToTheContext() {
        AtomicReference<ProvisionContext> seenContext = new AtomicReference<>();
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) -> seenContext.set(ctx))));
        JsonNode prior = mapper.createObjectNode().put("Removed", "value");

        dispatcher.provision("Thing", TYPE, mapper.createObjectNode(), null, "us-east-1", "000000000000",
                "my-stack", "thing-prior", Map.of(), event -> { }, prior);

        assertEquals(prior, seenContext.get().priorProperties());
    }

    @Test
    void aProvisionerThatMigratesAStubWithoutWritingAnArnDropsTheStubArn() {
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) ->
                resource.setPhysicalId("dash-real"))));

        StackResource resource = dispatcher.provision("Dash", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "Dash-1a2b3c4d", Map.of("Arn", "arn:aws:stub:::Dash"));

        assertFalse(resource.getAttributes().containsKey("Arn"),
                "the real resource must not read as a stub on its next update");
        assertEquals("dash-real", resource.getPhysicalId());
        assertEquals("CREATE_COMPLETE", resource.getStatus());
    }

    @Test
    void aProvisionerThatMigratesAStubOfADisabledServiceDropsTheDisabledMarker() {
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) -> {
            resource.setPhysicalId("dash-real");
            resource.getAttributes().put("Arn", "arn:aws:ecr:us-east-1:000000000000:repository/dash-real");
        })));

        StackResource resource = dispatcher.provision("Dash", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "Dash-1a2b3c4d",
                Map.of("Arn", "arn:aws:stub:::Dash", CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR, "true"));

        assertFalse(resource.getAttributes().containsKey(CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR),
                "the real resource must not keep refusing its own attributes");
        assertEquals("dash-real", resource.getPhysicalId());
    }

    @Test
    void aProvisionerThatMigratesAStubAndWritesOtherAttributesStillDropsTheStubArn() {
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) ->
                resource.getAttributes().put("FlociThingNameMode", "generated"))));

        StackResource resource = dispatcher.provision("Dash", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "Dash-1a2b3c4d", Map.of("Arn", "arn:aws:stub:::Dash"));

        assertEquals(Map.of("FlociThingNameMode", "generated"), resource.getAttributes());
    }

    @Test
    void aProvisionerThatMigratesAStubKeepsTheArnItWrites() {
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) -> {
            resource.setPhysicalId("dash-real");
            resource.getAttributes().put("Arn", "arn:aws:test:us-east-1:000000000000:thing/dash-real");
        })));

        StackResource resource = dispatcher.provision("Dash", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "Dash-1a2b3c4d", Map.of("Arn", "arn:aws:stub:::Dash"));

        assertEquals("arn:aws:test:us-east-1:000000000000:thing/dash-real", resource.getAttributes().get("Arn"));
        assertEquals("CREATE_COMPLETE", resource.getStatus());
    }

    @Test
    void aProvisionerThatThrowsLeavesTheResourceCreateFailed() {
        when(registry.forType(TYPE)).thenReturn(Optional.of(provisioner((resource, ctx) -> {
            throw new AwsException("ValidationError", "Thing requires Name", 400);
        })));

        StackResource resource = dispatcher.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("Thing requires Name", resource.getStatusReason());
    }

    @Test
    void deleteHandsTheOwnerTheWholeResource() {
        CfnResourceProvisioner owner = mock(CfnResourceProvisioner.class);
        when(registry.forType(TYPE)).thenReturn(Optional.of(owner));
        StackResource resource = new StackResource();
        resource.setResourceType(TYPE);
        resource.setPhysicalId("thing-1");

        dispatcher.delete(resource, "us-east-1");

        verify(owner).delete(resource, "us-east-1");
        verify(owner, never()).delete(anyString(), anyString(), anyString());
    }

    @Test
    void deleteStandaloneCarriesTheRecordedAttributes() {
        CfnResourceProvisioner owner = mock(CfnResourceProvisioner.class);
        when(registry.forType(TYPE)).thenReturn(Optional.of(owner));

        dispatcher.deleteStandalone(TYPE, "thing-1", "us-east-1", Map.of("Role", "r"));

        verify(owner).delete(any(StackResource.class), anyString());
        verify(owner).delete(ArgumentMatchers.argThat((StackResource r) ->
                "thing-1".equals(r.getPhysicalId()) && "r".equals(r.getAttributes().get("Role"))), any());
    }

    /**
     * A provisioner whose service is turned off serves none of its types: the dispatcher stubs
     * the resource instead of provisioning it, so a disabled service is never reached.
     */
    @Test
    void aProvisionerWhoseServiceIsDisabledIsStubbedNotProvisioned() {
        DisabledServiceProvisioner disabled = new DisabledServiceProvisioner(TYPE);
        CfnResourceDispatcher routed = new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(
                List.of(disabled)), dynamicReferences, null);

        StackResource resource = routed.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack");

        assertFalse(disabled.provisioned, "a disabled service must not be provisioned");
        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals("arn:aws:stub:::Thing", resource.getAttributes().get("Arn"));
    }

    @Test
    void deletingAResourceOfADisabledServiceReachesNoProvisioner() {
        DisabledServiceProvisioner disabled = new DisabledServiceProvisioner(TYPE);
        CfnResourceDispatcher routed = new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(
                List.of(disabled)), dynamicReferences, null);

        routed.delete(TYPE, "thing-1", "us-east-1");

        assertFalse(disabled.deleted, "a disabled service must not be deleted from");
    }

    @Test
    void aStubbedResourceOfADisabledServiceIsMarkedSoItsMissingAttributesAreRefused() {
        CfnResourceDispatcher routed = disabledDispatcher(new DisabledServiceProvisioner(TYPE));

        StackResource resource = routed.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack");

        assertEquals("true", resource.getAttributes().get(CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR));
    }

    @Test
    void updatingARealResourceWhileItsServiceIsDisabledFailsAndKeepsItsPhysicalId() {
        DisabledServiceProvisioner disabled = new DisabledServiceProvisioner(TYPE);
        CfnResourceDispatcher routed = disabledDispatcher(disabled);

        StackResource resource = routed.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "real-repo",
                Map.of("Arn", "arn:aws:ecr:us-east-1:000000000000:repository/real-repo"));

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("real-repo", resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey(CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR));
        assertFalse(disabled.provisioned);
    }

    @Test
    void updatingAStubWhileItsServiceIsDisabledStaysStubbed() {
        CfnResourceDispatcher routed = disabledDispatcher(new DisabledServiceProvisioner(TYPE));

        StackResource resource = routed.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack", "Thing-1a2b3c4d",
                Map.of("Arn", "arn:aws:stub:::Thing", CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR, "true"));

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals("arn:aws:stub:::Thing", resource.getAttributes().get("Arn"));
    }

    @Test
    void deletingARealResourceWhileItsServiceIsDisabledFailsInsteadOfDroppingIt() {
        DisabledServiceProvisioner disabled = new DisabledServiceProvisioner(TYPE);
        CfnResourceDispatcher routed = disabledDispatcher(disabled);
        StackResource real = new StackResource();
        real.setResourceType(TYPE);
        real.setPhysicalId("real-repo");
        real.setAttributes(Map.of("Arn", "arn:aws:ecr:us-east-1:000000000000:repository/real-repo"));

        AwsException failure = assertThrows(AwsException.class, () -> routed.delete(real, "us-east-1"));

        assertEquals(400, failure.getHttpStatus());
        assertFalse(disabled.deleted);
    }

    @Test
    void deletingAStubWhileItsServiceIsDisabledSucceeds() {
        CfnResourceDispatcher routed = disabledDispatcher(new DisabledServiceProvisioner(TYPE));
        StackResource stub = new StackResource();
        stub.setResourceType(TYPE);
        stub.setPhysicalId("Thing-1a2b3c4d");
        stub.setAttributes(Map.of("Arn", "arn:aws:stub:::Thing",
                CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR, "true"));

        assertDoesNotThrow(() -> routed.delete(stub, "us-east-1"));
    }

    @Test
    void aStubbedResourceOfADisabledServiceSaysTheServiceIsDisabledAndNamesItsFlag() {
        CfnResourceDispatcher routed = disabledDispatcher(new DisabledServiceProvisioner(TYPE));

        StackResource resource = routed.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack");

        assertEquals("Resource type " + TYPE + " is not provisioned because its Floci service is disabled. "
                + "Set floci.services.thing.enabled=true to provision it. "
                + "It was stubbed and nothing was created for it.", resource.getStatusReason());
    }

    @Test
    void aDisabledServiceFailsWithTheDisabledMessageWhenStubbingIsOff() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudformation().allowStubUnsupportedResourceTypes()).thenReturn(false);
        CfnResourceDispatcher strict = new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(
                List.of(new DisabledServiceProvisioner(TYPE))), dynamicReferences, config);

        StackResource resource = strict.provision("Thing", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("Resource type " + TYPE + " is not provisioned because its Floci service is disabled. "
                + "Set floci.services.thing.enabled=true to provision it.", resource.getStatusReason());
    }

    private CfnResourceDispatcher disabledDispatcher(DisabledServiceProvisioner disabled) {
        return new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(List.of(disabled)),
                dynamicReferences, null);
    }

    /** A provisioner whose service is disabled, recording whether it was ever asked to act. */
    private static final class DisabledServiceProvisioner implements CfnResourceProvisioner {
        private final String type;
        private boolean provisioned;
        private boolean deleted;

        private DisabledServiceProvisioner(String type) {
            this.type = type;
        }

        @Override
        public Set<String> resourceTypes() {
            return Set.of(type);
        }

        @Override
        public boolean serviceDisabled() {
            return true;
        }

        @Override
        public String serviceEnabledFlag() {
            return "floci.services.thing.enabled";
        }

        @Override
        public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
            provisioned = true;
        }

        @Override
        public void delete(String resourceType, String physicalId, String region) {
            deleted = true;
        }
    }

    /** A provisioner that serves {@code type} and does nothing, for routing through a real registry. */
    private static CfnResourceProvisioner serving(String type) {
        return new CfnResourceProvisioner() {
            @Override
            public Set<String> resourceTypes() {
                return Set.of(type);
            }

            @Override
            public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
            }
        };
    }

    private static CfnResourceProvisioner provisioner(Provision body) {
        return new CfnResourceProvisioner() {
            @Override
            public Set<String> resourceTypes() {
                return Set.of(TYPE);
            }

            @Override
            public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
                body.run(resource, ctx);
            }
        };
    }

    @FunctionalInterface
    private interface Provision {
        void run(StackResource resource, ProvisionContext ctx);
    }
}
