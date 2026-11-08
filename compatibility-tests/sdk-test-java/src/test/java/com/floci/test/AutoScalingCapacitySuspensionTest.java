package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.SuspendedProcess;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auto Scaling capacity process suspension")
class AutoScalingCapacitySuspensionTest {
    @Test
    @DisplayName("Launch and Terminate suspension round trips retain desired capacity")
    void suspendAndResumeRoundTrip() {
        String name = TestFixtures.uniqueName("capacity-pause");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient()) {
            asg.createLaunchConfiguration(r -> r.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            try {
                asg.createAutoScalingGroup(r -> r.autoScalingGroupName(name).launchConfigurationName(name)
                        .minSize(0).maxSize(1).desiredCapacity(0).availabilityZones(TestFixtures.availabilityZone()));
                try {
                    for (String process : List.of("Launch", "Terminate")) {
                        asg.suspendProcesses(r -> r.autoScalingGroupName(name).scalingProcesses(process));
                        AutoScalingGroup paused = group(asg, name);
                        assertThat(paused.suspendedProcesses()).extracting(SuspendedProcess::processName)
                                .containsExactly(process);
                        assertThat(paused.desiredCapacity()).isZero();
                        asg.resumeProcesses(r -> r.autoScalingGroupName(name).scalingProcesses(process));
                        AutoScalingGroup resumed = group(asg, name);
                        assertThat(resumed.suspendedProcesses()).isEmpty();
                        assertThat(resumed.desiredCapacity()).isZero();
                    }
                } finally {
                    asg.deleteAutoScalingGroup(r -> r.autoScalingGroupName(name).forceDelete(true));
                }
            } finally {
                asg.deleteLaunchConfiguration(r -> r.launchConfigurationName(name));
            }
        }
    }

    private static AutoScalingGroup group(AutoScalingClient asg, String name) {
        return asg.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().get(0);
    }
}
