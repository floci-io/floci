package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.SuspendedProcess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Local Auto Scaling suspension reasons")
class AutoScalingSuspensionReasonTest {
    private static final List<String> ALL_PROCESSES = List.of("Launch", "Terminate", "AddToLoadBalancer",
            "AlarmNotification", "AZRebalance", "HealthCheck", "InstanceRefresh", "ReplaceUnhealthy", "ScheduledActions");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Suspension dates remain stable until a process resumes")
    void reasonsFollowSuspensionAndResume(boolean all) {
        assumeFalse(TestFixtures.isRealAws(), "Creates local suspension fixtures");
        String name = TestFixtures.uniqueName("sdk-suspension-reason");
        try (AutoScalingClient client = TestFixtures.autoScalingClient()) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            try {
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(0)
                        .availabilityZones(TestFixtures.availabilityZone()));
                try {
                    assertProcesses(group(client, name), List.of());
                    List<String> names = all ? List.of() : List.of("Launch", "Terminate");
                    client.suspendProcesses(request -> request.autoScalingGroupName(name).scalingProcesses(names));
                    List<SuspendedProcess> original = group(client, name).suspendedProcesses();
                    client.suspendProcesses(request -> request.autoScalingGroupName(name)
                            .scalingProcesses("Launch", "Launch"));
                    assertThat(group(client, name).suspendedProcesses()).isEqualTo(original);
                    List<String> expected = all ? ALL_PROCESSES : List.of("Launch", "Terminate");
                    assertProcesses(group(client, name), expected);
                    client.resumeProcesses(request -> request.autoScalingGroupName(name).scalingProcesses("Launch"));
                    List<String> remaining = new ArrayList<>(expected);
                    remaining.remove("Launch");
                    assertProcesses(group(client, name), remaining);
                    client.resumeProcesses(request -> request.autoScalingGroupName(name));
                    assertProcesses(group(client, name), List.of());
                } finally {
                    client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                }
            } finally {
                client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
            }
        }
    }

    private static AutoScalingGroup group(AutoScalingClient client, String name) {
        List<AutoScalingGroup> groups = client.describeAutoScalingGroups(request -> request
                .autoScalingGroupNames(name)).autoScalingGroups();
        assertThat(groups).hasSize(1);
        return groups.get(0);
    }

    private static void assertProcesses(AutoScalingGroup group, List<String> expected) {
        assertThat(group.instances()).isEmpty();
        assertThat(group.suspendedProcesses()).extracting(SuspendedProcess::processName)
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(group.suspendedProcesses()).allSatisfy(process -> {
            String prefix = "User suspended at ";
            assertThat(process.suspensionReason()).startsWith(prefix);
            Instant date = Instant.parse(process.suspensionReason().substring(prefix.length()));
            assertThat(date.getNano()).isZero();
        });
    }
}
