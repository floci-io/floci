package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The ECS capacity CFN provisioner in isolation: one mocked service, no Quarkus boot.
 */
class EcsCapacityCfnProvisionerTest {

    private static final String PROVIDER_TYPE = "AWS::ECS::CapacityProvider";
    private static final String ASSOCIATIONS_TYPE = "AWS::ECS::ClusterCapacityProviderAssociations";
    private static final String ASG_ARN =
            "arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x";

    private final EcsService ecs = mock(EcsService.class);
    private final EcsCapacityCfnProvisioner provisioner = new EcsCapacityCfnProvisioner(ecs);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalars only: resolve returns the node's text and resolveNode is structure-preserving,
        // which is what the engine does for templates without intrinsics.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void capacityProviderUsesDeclaredName() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        props.putObject("AutoScalingGroupProvider").put("AutoScalingGroupArn", ASG_ARN);

        provisioner.provision(r, props, ctx());

        assertEquals("the-provider", r.getPhysicalId());
        verify(ecs).createCapacityProvider(eq("the-provider"), any(), any(), eq("us-east-1"));
    }

    @Test
    void capacityProviderWithoutNameGeneratesPhysicalName() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertEquals("my-stack-Provider", r.getPhysicalId().replaceAll("-[0-9a-f]{12}$", ""));
    }

    private CapacityProvider stored(String name, String asgArn, Map<String, String> tags) {
        CapacityProvider cp = new CapacityProvider();
        cp.setName(name);
        cp.setCapacityProviderArn("arn:aws:ecs:us-east-1:000000000000:capacity-provider/" + name);
        Map<String, Object> asg = new HashMap<>();
        asg.put("autoScalingGroupArn", asgArn);
        cp.setAutoScalingGroupProvider(asg);
        cp.setTags(new HashMap<>(tags));
        return cp;
    }

    @Test
    void updateReusesTheExistingProviderInsteadOfCreatingIt() {
        // UpdateStack re-executes the resource with the physical id it already has, and
        // createCapacityProvider rejects an existing name, so this used to fail the whole update.
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        r.setPhysicalId("the-provider");
        when(ecs.describeCapacityProviders(List.of("the-provider")))
                .thenReturn(List.of(stored("the-provider", ASG_ARN, Map.of())));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        ObjectNode asg = props.putObject("AutoScalingGroupProvider");
        asg.put("AutoScalingGroupArn", ASG_ARN);
        asg.put("ManagedDraining", "ENABLED");

        provisioner.provision(r, props, ctx());

        assertEquals("the-provider", r.getPhysicalId());
        verify(ecs, never()).createCapacityProvider(anyString(), any(), any(), anyString());
        ArgumentCaptor<Map<String, Object>> asgCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ecs).updateCapacityProvider(eq("the-provider"), asgCaptor.capture());
        assertEquals("ENABLED", asgCaptor.getValue().get("managedDraining"));
    }

    @Test
    void anUnnamedProviderKeepsItsGeneratedNameAcrossUpdates() {
        // A fresh generated name on every pass created a second provider and orphaned the first.
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        r.setPhysicalId("my-stack-Provider-abcdef123456");
        when(ecs.describeCapacityProviders(List.of("my-stack-Provider-abcdef123456")))
                .thenReturn(List.of(stored("my-stack-Provider-abcdef123456", null, Map.of())));

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertEquals("my-stack-Provider-abcdef123456", r.getPhysicalId());
        verify(ecs, never()).createCapacityProvider(anyString(), any(), any(), anyString());
        verify(ecs).updateCapacityProvider(eq("my-stack-Provider-abcdef123456"), any());
    }

    @Test
    void aProviderRemovedOutOfBandIsRecreated() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        r.setPhysicalId("the-provider");
        when(ecs.describeCapacityProviders(List.of("the-provider"))).thenReturn(List.of());
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        props.putObject("AutoScalingGroupProvider").put("AutoScalingGroupArn", ASG_ARN);

        provisioner.provision(r, props, ctx());

        verify(ecs).createCapacityProvider(eq("the-provider"), any(), any(), eq("us-east-1"));
        verify(ecs, never()).updateCapacityProvider(anyString(), any());
    }

    @Test
    void changingNameIsRejectedAsAReplacement() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        r.setPhysicalId("the-provider");
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "a-different-provider");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ecs, never()).createCapacityProvider(anyString(), any(), any(), anyString());
        verify(ecs, never()).updateCapacityProvider(anyString(), any());
    }

    @Test
    void changingAutoScalingGroupArnIsRejectedAsAReplacement() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        r.setPhysicalId("the-provider");
        when(ecs.describeCapacityProviders(List.of("the-provider")))
                .thenReturn(List.of(stored("the-provider", ASG_ARN, Map.of())));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        props.putObject("AutoScalingGroupProvider")
                .put("AutoScalingGroupArn", ASG_ARN.replace("asg-x", "asg-y"));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ecs, never()).updateCapacityProvider(anyString(), any());
    }

    @Test
    void updateAppliesTagChangesAndRemovesTheOnesTheTemplateDropped() {
        // updateCapacityProvider carries only the Auto Scaling settings, so a dropped tag would
        // otherwise survive the update.
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        r.setPhysicalId("the-provider");
        String arn = "arn:aws:ecs:us-east-1:000000000000:capacity-provider/the-provider";
        when(ecs.describeCapacityProviders(List.of("the-provider")))
                .thenReturn(List.of(stored("the-provider", ASG_ARN, Map.of("keep", "1", "drop", "2"))));
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        props.putObject("AutoScalingGroupProvider").put("AutoScalingGroupArn", ASG_ARN);
        var tags = props.putArray("Tags");
        tags.addObject().put("Key", "keep").put("Value", "1");

        provisioner.provision(r, props, ctx());

        verify(ecs).untagResource(arn, List.of("drop"));
        verify(ecs).tagResource(arn, Map.of("keep", "1"));
    }

    /** Greptile P1: ManagedScaling / ManagedTerminationProtection / ManagedDraining were dropped. */
    @Test
    void capacityProviderCarriesFullAutoScalingGroupProvider() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        ObjectNode asg = props.putObject("AutoScalingGroupProvider");
        asg.put("AutoScalingGroupArn", ASG_ARN);
        asg.put("ManagedTerminationProtection", "ENABLED");
        asg.put("ManagedDraining", "ENABLED");
        ObjectNode scaling = asg.putObject("ManagedScaling");
        scaling.put("Status", "ENABLED");
        scaling.put("TargetCapacity", 80);
        scaling.put("MinimumScalingStepSize", 1);
        scaling.put("MaximumScalingStepSize", 10);
        scaling.put("InstanceWarmupPeriod", 300);

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ecs).createCapacityProvider(eq("the-provider"), captor.capture(), any(), eq("us-east-1"));
        Map<String, Object> asgProvider = captor.getValue();

        // Stored in the ECS JSON API's camelCase shape, the same one CreateCapacityProvider writes.
        assertEquals(ASG_ARN, asgProvider.get("autoScalingGroupArn"));
        assertEquals("ENABLED", asgProvider.get("managedTerminationProtection"));
        assertEquals("ENABLED", asgProvider.get("managedDraining"));
        @SuppressWarnings("unchecked")
        Map<String, Object> managedScaling = (Map<String, Object>) asgProvider.get("managedScaling");
        assertEquals("ENABLED", managedScaling.get("status"));
        assertEquals(80, managedScaling.get("targetCapacity"));
        assertEquals(1, managedScaling.get("minimumScalingStepSize"));
        assertEquals(10, managedScaling.get("maximumScalingStepSize"));
        assertEquals(300, managedScaling.get("instanceWarmupPeriod"));
    }

    /** Greptile P1: Tags were replaced with an empty map. */
    @Test
    void capacityProviderCarriesTags() {
        StackResource r = resource(PROVIDER_TYPE, "Provider");
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", "the-provider");
        var tags = props.putArray("Tags");
        tags.addObject().put("Key", "env").put("Value", "prod");
        tags.addObject().put("Key", "team").put("Value", "platform");

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ecs).createCapacityProvider(eq("the-provider"), any(), captor.capture(), eq("us-east-1"));
        assertEquals(Map.of("env", "prod", "team", "platform"), captor.getValue());
    }

    @Test
    void associationsPutProvidersAndStrategy() {
        StackResource r = resource(ASSOCIATIONS_TYPE, "Assoc");
        ObjectNode props = mapper.createObjectNode();
        props.put("Cluster", "the-cluster");
        var providers = props.putArray("CapacityProviders");
        providers.add("the-provider");
        providers.add("FARGATE");
        var strategy = props.putArray("DefaultCapacityProviderStrategy");
        strategy.addObject().put("CapacityProvider", "the-provider").put("Weight", 2).put("Base", 1);

        provisioner.provision(r, props, ctx());

        assertEquals("the-cluster", r.getPhysicalId());
        verify(ecs).putClusterCapacityProviders("the-cluster",
                List.of("the-provider", "FARGATE"),
                List.of(Map.of("capacityProvider", "the-provider", "weight", 2, "base", 1)),
                "us-east-1");
    }

    @Test
    void unhandledTypeThrows() {
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        assertThrows(IllegalStateException.class,
                () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }

    /** Greptile P1: stack delete and rollback left the capacity provider registered. */
    @Test
    void deleteCapacityProviderDelegatesToService() {
        CapacityProvider existing = new CapacityProvider();
        existing.setName("the-provider");
        when(ecs.describeCapacityProviders(List.of("the-provider"))).thenReturn(List.of(existing));

        provisioner.delete(PROVIDER_TYPE, "the-provider", "us-east-1");

        verify(ecs).deleteCapacityProvider("the-provider");
    }

    @Test
    void deleteCapacityProviderAlreadyGoneIsDeleteComplete() {
        when(ecs.describeCapacityProviders(anyList())).thenReturn(List.of());

        provisioner.delete(PROVIDER_TYPE, "the-provider", "us-east-1");

        verify(ecs, never()).deleteCapacityProvider(anyString());
    }

    /** Greptile P1: the cluster kept its associations after the stack was deleted. */
    @Test
    void deleteAssociationsDetachesProvidersFromCluster() {
        provisioner.delete(ASSOCIATIONS_TYPE, "the-cluster", "us-east-1");

        verify(ecs).putClusterCapacityProviders("the-cluster", List.of(), List.of(), "us-east-1");
    }

    @Test
    void deleteAssociationsToleratesAMissingCluster() {
        when(ecs.putClusterCapacityProviders(anyString(), anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ClusterNotFoundException", "Cluster not found: gone", 400));

        provisioner.delete(ASSOCIATIONS_TYPE, "gone", "us-east-1");
    }

    @Test
    void deleteAssociationsPropagatesOtherFailures() {
        when(ecs.putClusterCapacityProviders(anyString(), anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ServerException", "boom", 500));

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.delete(ASSOCIATIONS_TYPE, "the-cluster", "us-east-1"));
        assertEquals("ServerException", thrown.getErrorCode());
    }

    @Test
    void deleteWithoutAPhysicalIdIsANoOp() {
        provisioner.delete(PROVIDER_TYPE, null, "us-east-1");
        provisioner.delete(ASSOCIATIONS_TYPE, "  ", "us-east-1");
        verifyNoInteractions(ecs);
    }

    @Test
    void resourceTypesCoverBothCapacityTypes() {
        assertTrue(provisioner.resourceTypes().containsAll(List.of(PROVIDER_TYPE, ASSOCIATIONS_TYPE)));
        assertEquals(2, provisioner.resourceTypes().size());
    }
}
