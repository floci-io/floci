package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.Instance;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingDetachLoadBalancersIntegrationTest {
    // Exercise management membership through the SDK without launching an EC2 guest.
    @InjectMock
    AutoScalingReconciler reconciler;

    @Test
    void sdkDetachPreservesOtherGroupAndManualClassicRegistrations() throws Exception {
        String name = "detach-" + UUID.randomUUID().toString().substring(0, 12);
        try (AutoScalingClient scaling = AwsTestClients.create(AutoScalingClient.builder(), "000000000000", Region.US_EAST_1);
             ElasticLoadBalancingClient classic = AwsTestClients.create(
                     ElasticLoadBalancingClient.builder(), "000000000000", Region.US_EAST_1)) {
            classic.createLoadBalancer(request -> request.loadBalancerName(name).availabilityZones("us-east-1a")
                    .listeners(Listener.builder().protocol("HTTP").loadBalancerPort(80).instancePort(8080).build()));
            try (AutoCloseable loadBalancer = () -> classic.deleteLoadBalancer(request -> request.loadBalancerName(name))) {
                scaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                        .imageId("ami-12345678").instanceType("t3.micro"));
                try (AutoCloseable launchConfiguration = () -> scaling.deleteLaunchConfiguration(
                        request -> request.launchConfigurationName(name))) {
                    createGroup(scaling, name, name);
                    try (AutoCloseable group = () -> scaling.deleteAutoScalingGroup(
                            request -> request.autoScalingGroupName(name).forceDelete(true))) {
                        String other = name + "-other";
                        createGroup(scaling, other, name);
                        try (AutoCloseable otherGroup = () -> scaling.deleteAutoScalingGroup(
                                request -> request.autoScalingGroupName(other).forceDelete(true))) {
                            scaling.attachInstances(request -> request.autoScalingGroupName(name).instanceIds("i-owned"));
                            scaling.attachInstances(request -> request.autoScalingGroupName(other).instanceIds("i-other"));
                            classic.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name).instances(
                                    Instance.builder().instanceId("i-owned").build(),
                                    Instance.builder().instanceId("i-other").build(),
                                    Instance.builder().instanceId("i-manual").build()));

                            scaling.detachInstances(request -> request.autoScalingGroupName(name)
                                    .instanceIds("i-owned").shouldDecrementDesiredCapacity(true));

                            assertEquals(List.of("i-other", "i-manual"), classic.describeLoadBalancers(
                                    request -> request.loadBalancerNames(name)).loadBalancerDescriptions().getFirst()
                                    .instances().stream().map(Instance::instanceId).toList());
                            assertTrue(scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                                    .autoScalingGroups().getFirst().instances().isEmpty());
                            assertEquals(List.of("i-other"), scaling.describeAutoScalingGroups(
                                    request -> request.autoScalingGroupNames(other)).autoScalingGroups().getFirst()
                                    .instances().stream().map(instance -> instance.instanceId()).toList());
                        }
                    }
                }
            }
            assertTrue(scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name, name + "-other"))
                    .autoScalingGroups().isEmpty());
            assertTrue(scaling.describeLaunchConfigurations(request -> request.launchConfigurationNames(name))
                    .launchConfigurations().isEmpty());
            assertTrue(classic.describeLoadBalancers().loadBalancerDescriptions().stream()
                    .noneMatch(loadBalancer -> name.equals(loadBalancer.loadBalancerName())));
        }
    }

    private static void createGroup(AutoScalingClient scaling, String group, String loadBalancer) {
        scaling.createAutoScalingGroup(request -> request.autoScalingGroupName(group)
                .launchConfigurationName(loadBalancer).minSize(0).maxSize(3).desiredCapacity(0)
                .availabilityZones("us-east-1a").loadBalancerNames(loadBalancer));
    }
}
