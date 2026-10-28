package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingInstanceDetails;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.autoscaling.model.LifecycleState;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DescribeAutoScalingInstances} names the group of each instance, a required member of
 * {@code AutoScalingInstanceDetails}, and spells its health in capitals, while the instance list of
 * {@code DescribeAutoScalingGroups} keeps {@code Healthy} and {@code Unhealthy}.
 */
@DisplayName("Auto Scaling instance details")
class AutoScalingInstanceDetailsTest {

    @Test
    @DisplayName("DescribeAutoScalingInstances names the group and reports HEALTHY and UNHEALTHY")
    void describeAutoScalingInstancesNamesTheGroupAndCapitalizesHealth() throws InterruptedException {
        String name = TestFixtures.uniqueName("instance-details");
        String zone = TestFixtures.availabilityZone();
        try (AutoScalingClient autoScaling = TestFixtures.autoScalingClient();
                Ec2Client ec2 = TestFixtures.ec2Client()) {
            ec2.createLaunchTemplate(request -> request.launchTemplateName(name).launchTemplateData(data -> data
                    .imageId("ami-amazonlinux2023").instanceType(InstanceType.T3_MICRO)));
            try {
                autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchTemplate(template -> template.launchTemplateName(name).version("1"))
                        .minSize(0).maxSize(2).desiredCapacity(2).availabilityZones(zone));

                AtomicReference<List<Instance>> members = new AtomicReference<>(List.of());
                TestFixtures.await(() -> {
                    members.set(autoScaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                            .autoScalingGroups().get(0).instances());
                    // Floci overwrites health with Healthy when it promotes a Pending member, so a
                    // member is marked unhealthy only once both are in service.
                    return members.get().size() == 2 && members.get().stream()
                            .allMatch(member -> member.lifecycleState() == LifecycleState.IN_SERVICE);
                }, name, "two in-service instances", TimeUnit.SECONDS.toNanos(120), 1000);
                String healthy = members.get().get(0).instanceId();
                String unhealthy = members.get().get(1).instanceId();
                // AWS replaces an unhealthy member; the group keeps it here for the assertions.
                autoScaling.suspendProcesses(request -> request.autoScalingGroupName(name)
                        .scalingProcesses("ReplaceUnhealthy"));
                autoScaling.setInstanceHealth(request -> request.instanceId(unhealthy).healthStatus("Unhealthy")
                        .shouldRespectGracePeriod(false));

                List<AutoScalingInstanceDetails> details = autoScaling.describeAutoScalingInstances(request ->
                        request.instanceIds(healthy, unhealthy)).autoScalingInstances();
                assertThat(details).extracting(AutoScalingInstanceDetails::autoScalingGroupName)
                        .containsExactly(name, name);
                assertThat(details).filteredOn(detail -> detail.instanceId().equals(healthy))
                        .extracting(AutoScalingInstanceDetails::healthStatus).containsExactly("HEALTHY");
                assertThat(details).filteredOn(detail -> detail.instanceId().equals(unhealthy))
                        .extracting(AutoScalingInstanceDetails::healthStatus).containsExactly("UNHEALTHY");

                List<Instance> group = autoScaling.describeAutoScalingGroups(request ->
                        request.autoScalingGroupNames(name)).autoScalingGroups().get(0).instances();
                assertThat(group).filteredOn(instance -> instance.instanceId().equals(healthy))
                        .extracting(Instance::healthStatus).containsExactly("Healthy");
                assertThat(group).filteredOn(instance -> instance.instanceId().equals(unhealthy))
                        .extracting(Instance::healthStatus).containsExactly("Unhealthy");
            } finally {
                try {
                    autoScaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                } catch (AutoScalingException ignored) {
                    // A group that was never created has nothing to clean up.
                }
                ec2.deleteLaunchTemplate(request -> request.launchTemplateName(name));
            }
        }
    }
}
