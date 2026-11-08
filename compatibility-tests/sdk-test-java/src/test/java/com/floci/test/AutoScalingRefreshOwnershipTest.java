package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceRefresh;
import software.amazon.awssdk.services.autoscaling.model.ResumeProcessesRequest;
import software.amazon.awssdk.services.autoscaling.model.SuspendProcessesRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoScalingRefreshOwnershipTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("A paused refresh exposes its selected work and resumes through the SDK")
    void pausedRefreshMetadataAndResumeRoundTrip(boolean allProcesses) {
        String name = TestFixtures.uniqueName("refresh-ownership");
        try (AutoScalingClient autoScaling = TestFixtures.autoScalingClient();
                Ec2Client ec2 = TestFixtures.ec2Client()) {
            autoScaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            try {
                String instanceId = ec2.runInstances(request -> request.imageId("ami-amazonlinux2023")
                        .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1))
                        .instances().get(0).instanceId();
                try {
                    autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                            .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(0)
                            .availabilityZones("us-east-1a"));
                    try {
                        SuspendProcessesRequest.Builder suspend = SuspendProcessesRequest.builder()
                                .autoScalingGroupName(name);
                        if (!allProcesses) {
                            suspend.scalingProcesses("InstanceRefresh");
                        }
                        autoScaling.suspendProcesses(suspend.build());
                        autoScaling.attachInstances(request -> request.autoScalingGroupName(name)
                                .instanceIds(instanceId));
                        String refreshId = autoScaling.startInstanceRefresh(request ->
                                request.autoScalingGroupName(name)).instanceRefreshId();
                        assertThat(refreshId).isNotBlank();
                        InstanceRefresh refresh = autoScaling.describeInstanceRefreshes(request ->
                                request.autoScalingGroupName(name).instanceRefreshIds(refreshId))
                                .instanceRefreshes().get(0);
                        assertThat(refresh.instanceRefreshId()).isEqualTo(refreshId);
                        assertThat(refresh.autoScalingGroupName()).isEqualTo(name);
                        assertThat(refresh.statusAsString()).isEqualTo("InProgress");
                        assertThat(refresh.startTime()).isNotNull();
                        assertThat(refresh.instancesToUpdate()).isEqualTo(1);
                        assertThat(refresh.percentageComplete()).isZero();
                        AutoScalingGroup group = autoScaling.describeAutoScalingGroups(request ->
                                request.autoScalingGroupNames(name)).autoScalingGroups().get(0);
                        assertThat(group.desiredCapacity()).isEqualTo(1);
                        assertThat(group.instances()).hasSize(1);
                        assertThat(group.instances().get(0).instanceId()).isEqualTo(instanceId);
                        assertThat(group.suspendedProcesses()).extracting(process -> process.processName())
                                .contains("InstanceRefresh");
                        assertThatThrownBy(() -> autoScaling.startInstanceRefresh(request ->
                                request.autoScalingGroupName(name)))
                                .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                                    assertThat(error.statusCode()).isEqualTo(400);
                                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InstanceRefreshInProgress");
                                });
                        assertThat(autoScaling.describeInstanceRefreshes(request ->
                                request.autoScalingGroupName(name)).instanceRefreshes())
                                .extracting(InstanceRefresh::instanceRefreshId).containsExactly(refreshId);
                        ResumeProcessesRequest.Builder resume = ResumeProcessesRequest.builder()
                                .autoScalingGroupName(name);
                        if (!allProcesses) {
                            resume.scalingProcesses("InstanceRefresh");
                        }
                        autoScaling.resumeProcesses(resume.build());
                        assertThat(autoScaling.describeAutoScalingGroups(request ->
                                request.autoScalingGroupNames(name)).autoScalingGroups().get(0).suspendedProcesses())
                                .isEmpty();
                    } finally {
                        autoScaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name)
                                .forceDelete(true));
                    }
                } finally {
                    ec2.terminateInstances(request -> request.instanceIds(instanceId));
                }
            } finally {
                autoScaling.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
            }
        }
    }
}
