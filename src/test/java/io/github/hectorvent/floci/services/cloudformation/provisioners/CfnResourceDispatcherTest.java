package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        verify(owner).delete(org.mockito.ArgumentMatchers.argThat((StackResource r) ->
                "thing-1".equals(r.getPhysicalId()) && "r".equals(r.getAttributes().get("Role"))), any());
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
