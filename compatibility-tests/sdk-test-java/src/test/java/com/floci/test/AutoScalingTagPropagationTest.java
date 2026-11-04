package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.autoscaling.model.Tag;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auto Scaling omitted tag propagation")
class AutoScalingTagPropagationTest {

    @Test
    @DisplayName("Group creation defaults omitted flags to true on launch and attach")
    void omittedFlagsPropagateOnLaunchAndAttach() throws InterruptedException {
        String name = TestFixtures.uniqueName("tag-propagation");
        String zone = TestFixtures.availabilityZone();
        List<Tag> tags = List.of(
                Tag.builder().key("es").value(name).build(),
                Tag.builder().key("stack").value("test").build(),
                Tag.builder().key("private").value("group-only").propagateAtLaunch(false).build());
        assertThat(tags.get(0).propagateAtLaunch()).isNull();
        assertThat(tags.get(1).propagateAtLaunch()).isNull();
        try (AutoScalingClient autoScaling = TestFixtures.autoScalingClient();
                Ec2Client ec2 = TestFixtures.ec2Client()) {
            autoScaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            boolean groupCreated = false;
            String standalone = null;
            try {
                String attachedId = ec2.runInstances(request -> request.imageId("ami-amazonlinux2023")
                        .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)
                        .placement(placement -> placement.availabilityZone(zone))
                        .tagSpecifications(spec -> spec.resourceType(ResourceType.INSTANCE)
                                .tags(tag -> tag.key("Owner").value("standalone"))))
                        .instances().get(0).instanceId();
                standalone = attachedId;
                autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(2).desiredCapacity(0)
                        .availabilityZones(zone).tags(tags));
                groupCreated = true;
                autoScaling.setDesiredCapacity(request -> request.autoScalingGroupName(name).desiredCapacity(1));
                AtomicReference<String> launched = new AtomicReference<>();
                TestFixtures.await(() -> {
                    List<Instance> members = autoScaling.describeAutoScalingGroups(request ->
                            request.autoScalingGroupNames(name)).autoScalingGroups().get(0).instances();
                    members.stream().findFirst().ifPresent(member -> launched.set(member.instanceId()));
                    return launched.get() != null;
                }, name, "an instance launched by the group", TimeUnit.SECONDS.toNanos(60), 1000);

                assertThat(userTags(ec2, launched.get())).containsExactlyInAnyOrderEntriesOf(
                        Map.of("es", name, "stack", "test"));
                List<String> discovered = ec2.describeInstances(request -> request.filters(
                                filter -> filter.name("tag:es").values(name),
                                filter -> filter.name("tag:stack").values("test")))
                        .reservations().stream().flatMap(reservation -> reservation.instances().stream())
                        .map(instance -> instance.instanceId()).toList();
                assertThat(discovered).containsExactly(launched.get());
                Map<String, Boolean> propagation = autoScaling.describeAutoScalingGroups(request ->
                                request.autoScalingGroupNames(name)).autoScalingGroups().get(0).tags().stream()
                        .collect(Collectors.toMap(tag -> tag.key(), tag -> tag.propagateAtLaunch()));
                assertThat(propagation).containsExactlyInAnyOrderEntriesOf(
                        Map.of("es", true, "stack", true, "private", false));

                assertThat(userTags(ec2, attachedId)).containsExactlyInAnyOrderEntriesOf(
                        Map.of("Owner", "standalone"));
                // Keep scale-in from racing the attachment tag assertions.
                autoScaling.suspendProcesses(request -> request.autoScalingGroupName(name)
                        .scalingProcesses("Terminate"));
                autoScaling.attachInstances(request -> request.autoScalingGroupName(name).instanceIds(attachedId));
                assertThat(userTags(ec2, attachedId)).containsExactlyInAnyOrderEntriesOf(
                        Map.of("Owner", "standalone", "es", name, "stack", "test"));
                assertThat(ec2.describeInstances(request -> request.instanceIds(attachedId)).reservations().get(0)
                        .instances().get(0).tags()).anySatisfy(tag -> {
                            assertThat(tag.key()).isEqualTo("aws:autoscaling:groupName");
                            assertThat(tag.value()).isEqualTo(name);
                        });
            } finally {
                try {
                    if (groupCreated) {
                        autoScaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name)
                                .forceDelete(true));
                    }
                } finally {
                    try {
                        if (standalone != null) {
                            String instanceToTerminate = standalone;
                            ec2.terminateInstances(request -> request.instanceIds(instanceToTerminate));
                        }
                    } finally {
                        autoScaling.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
                    }
                }
            }
        }
    }

    private static Map<String, String> userTags(Ec2Client ec2, String instanceId) {
        Map<String, String> tags = new HashMap<>(ec2.describeInstances(request -> request.instanceIds(instanceId))
                .reservations().get(0).instances().get(0).tags().stream()
                .collect(Collectors.toMap(tag -> tag.key(), tag -> tag.value())));
        tags.keySet().removeIf(key -> key.startsWith("aws:"));
        return tags;
    }
}
