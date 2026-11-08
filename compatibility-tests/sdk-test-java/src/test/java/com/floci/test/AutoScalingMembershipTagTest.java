package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.Instance;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code aws:autoscaling:groupName} tag through a member's lifecycle, as the EC2 Auto Scaling
 * user guide's instance tagging lifecycle describes it.
 */
@DisplayName("Auto Scaling membership tag")
class AutoScalingMembershipTagTest {

    private static final String GROUP_NAME_TAG = "aws:autoscaling:groupName";

    @Test
    @DisplayName("aws:autoscaling:groupName is added on launch and attach and removed on detach")
    void groupNameTagFollowsLaunchAttachAndDetach() throws InterruptedException {
        String name = TestFixtures.uniqueName("membership-tag");
        String other = name + "-other";
        String zone = TestFixtures.availabilityZone();
        try (AutoScalingClient autoScaling = TestFixtures.autoScalingClient();
                Ec2Client ec2 = TestFixtures.ec2Client()) {
            ec2.createLaunchTemplate(request -> request.launchTemplateName(name).launchTemplateData(data -> data
                    .imageId("ami-amazonlinux2023").instanceType(InstanceType.T3_MICRO)
                    .tagSpecifications(spec -> spec.resourceType(ResourceType.INSTANCE).tags(
                            tag -> tag.key("Source").value("template"),
                            tag -> tag.key("Shared").value("template")))));
            String standalone = ec2.runInstances(request -> request.imageId("ami-amazonlinux2023")
                    .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)
                    .placement(placement -> placement.availabilityZone(zone))
                    .tagSpecifications(spec -> spec.resourceType(ResourceType.INSTANCE).tags(
                            tag -> tag.key("Shared").value("mine"), tag -> tag.key("Owner").value("me"))))
                    .instances().get(0).instanceId();
            try {
                autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchTemplate(template -> template.launchTemplateName(name).version("1"))
                        .minSize(0).maxSize(2).desiredCapacity(1).availabilityZones(zone)
                        .tags(tag -> tag.key("Shared").value("group").propagateAtLaunch(true),
                                tag -> tag.key("Private").value("group-only").propagateAtLaunch(false)));
                autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(other)
                        .launchTemplate(template -> template.launchTemplateName(name).version("1"))
                        .minSize(0).maxSize(2).desiredCapacity(0).availabilityZones(zone));

                AtomicReference<String> launched = new AtomicReference<>();
                TestFixtures.await(() -> {
                    List<Instance> members = autoScaling.describeAutoScalingGroups(request ->
                            request.autoScalingGroupNames(name)).autoScalingGroups().get(0).instances();
                    members.stream().findFirst().ifPresent(member -> launched.set(member.instanceId()));
                    return launched.get() != null;
                }, name, "an instance launched by the group", TimeUnit.SECONDS.toNanos(60), 1000);
                assertThat(userTags(ec2, launched.get())).containsExactlyInAnyOrderEntriesOf(
                        Map.of("Source", "template", "Shared", "group"));
                assertThat(tags(ec2, launched.get())).containsEntry(GROUP_NAME_TAG, name);
                assertThat(instancesTaggedWith(ec2, name)).containsExactly(launched.get());

                autoScaling.attachInstances(request -> request.autoScalingGroupName(name).instanceIds(standalone));
                assertThat(userTags(ec2, standalone)).containsExactlyInAnyOrderEntriesOf(
                        Map.of("Owner", "me", "Shared", "group"));
                assertThat(tags(ec2, standalone)).containsEntry(GROUP_NAME_TAG, name);

                String alreadyMember = "The Instance: " + standalone + " is already part of AutoScalingGroup:" + name;
                for (String group : List.of(name, other)) {
                    assertThatThrownBy(() -> autoScaling.attachInstances(request ->
                            request.autoScalingGroupName(group).instanceIds(standalone)))
                            .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                                assertThat(error.statusCode()).isEqualTo(400);
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                                assertThat(error.awsErrorDetails().errorMessage()).contains(alreadyMember);
                            });
                }
                assertThat(tags(ec2, standalone)).containsEntry(GROUP_NAME_TAG, name);

                autoScaling.detachInstances(request -> request.autoScalingGroupName(name).instanceIds(standalone)
                        .shouldDecrementDesiredCapacity(true));
                assertThat(tags(ec2, standalone)).doesNotContainKey(GROUP_NAME_TAG);
                assertThat(userTags(ec2, standalone)).containsExactlyInAnyOrderEntriesOf(
                        Map.of("Owner", "me", "Shared", "group"));
                assertThat(instancesTaggedWith(ec2, name)).containsExactly(launched.get());
            } finally {
                for (String group : List.of(name, other)) {
                    try {
                        autoScaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(group)
                                .forceDelete(true));
                    } catch (AutoScalingException ignored) {
                        // A group that was never created has nothing to clean up.
                    }
                }
                ec2.terminateInstances(request -> request.instanceIds(standalone));
                ec2.deleteLaunchTemplate(request -> request.launchTemplateName(name));
            }
        }
    }

    private static Map<String, String> tags(Ec2Client ec2, String instanceId) {
        return ec2.describeInstances(request -> request.instanceIds(instanceId)).reservations().get(0)
                .instances().get(0).tags().stream()
                .collect(Collectors.toMap(tag -> tag.key(), tag -> tag.value()));
    }

    /** The instance's tags without the {@code aws:} ones AWS adds on its own, such as launch template ids. */
    private static Map<String, String> userTags(Ec2Client ec2, String instanceId) {
        Map<String, String> tags = new HashMap<>(tags(ec2, instanceId));
        tags.keySet().removeIf(key -> key.startsWith("aws:"));
        return tags;
    }

    private static List<String> instancesTaggedWith(Ec2Client ec2, String group) {
        return ec2.describeInstances(request -> request.filters(filter -> filter.name("tag:" + GROUP_NAME_TAG)
                        .values(group))).reservations().stream()
                .flatMap(reservation -> reservation.instances().stream())
                .map(instance -> instance.instanceId())
                .toList();
    }
}
