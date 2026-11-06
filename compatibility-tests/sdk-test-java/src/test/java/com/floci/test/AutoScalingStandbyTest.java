package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.autoscaling.model.LifecycleState;
import software.amazon.awssdk.services.autoscaling.model.ScalingActivityStatusCode;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EnterStandby and ExitStandby through the AWS SDK for Java: the instance's activity comes back
 * {@code InProgress} at 50% and then {@code PreInService} at 30%, as in the AWS API reference, the
 * instance reaches {@code Standby} and then {@code InService} again, and desired capacity follows it.
 */
@DisplayName("Auto Scaling standby")
class AutoScalingStandbyTest {

    @Test
    @DisplayName("EnterStandby and ExitStandby take an instance out of service and back")
    void enterAndExitStandby() throws InterruptedException {
        String name = TestFixtures.uniqueName("standby");
        try (AutoScalingClient autoScaling = TestFixtures.autoScalingClient();
                Ec2Client ec2 = TestFixtures.ec2Client()) {
            ec2.createLaunchTemplate(request -> request.launchTemplateName(name).launchTemplateData(data -> data
                    .imageId("ami-amazonlinux2023").instanceType(InstanceType.T3_MICRO)));
            try {
                autoScaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchTemplate(template -> template.launchTemplateName(name).version("1"))
                        .minSize(0).maxSize(2).desiredCapacity(2).availabilityZones(TestFixtures.availabilityZone()));
                List<Instance> members = awaitMembers(autoScaling, name, LifecycleState.IN_SERVICE, LifecycleState.IN_SERVICE);
                String standby = members.get(0).instanceId();
                String serving = members.get(1).instanceId();

                Activity entered = autoScaling.enterStandby(request -> request.autoScalingGroupName(name)
                        .instanceIds(standby).shouldDecrementDesiredCapacity(true)).activities().get(0);
                assertThat(entered.description()).isEqualTo("Moving EC2 instance to Standby: " + standby);
                assertThat(entered.cause()).endsWith("instance " + standby + " was moved to standby in response"
                        + " to a user request, shrinking the capacity from 2 to 1.");
                assertThat(entered.statusCode()).isEqualTo(ScalingActivityStatusCode.IN_PROGRESS);
                assertThat(entered.progress()).isEqualTo(50);
                awaitLifecycle(autoScaling, name, standby, LifecycleState.STANDBY);
                assertThat(group(autoScaling, name).desiredCapacity()).isEqualTo(1);

                assertThatThrownBy(() -> autoScaling.exitStandby(request -> request.autoScalingGroupName(name)
                        .instanceIds(serving)))
                        .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                            assertThat(error.awsErrorDetails().errorMessage())
                                    .isEqualTo("The instance " + serving + " is not in Standby.");
                        });

                Activity exited = autoScaling.exitStandby(request -> request.autoScalingGroupName(name)
                        .instanceIds(standby)).activities().get(0);
                assertThat(exited.description()).isEqualTo("Moving EC2 instance out of Standby: " + standby);
                assertThat(exited.cause()).endsWith("instance " + standby + " was moved out of standby in"
                        + " response to a user request, increasing the capacity from 1 to 2.");
                assertThat(exited.statusCode()).isEqualTo(ScalingActivityStatusCode.PRE_IN_SERVICE);
                assertThat(exited.progress()).isEqualTo(30);
                awaitLifecycle(autoScaling, name, standby, LifecycleState.IN_SERVICE);
                assertThat(group(autoScaling, name).desiredCapacity()).isEqualTo(2);

                TestFixtures.await(() -> autoScaling.describeScalingActivities(request -> request
                                .autoScalingGroupName(name)).activities().stream()
                                .filter(activity -> activity.activityId().equals(exited.activityId()))
                                .anyMatch(activity -> activity.statusCode() == ScalingActivityStatusCode.SUCCESSFUL),
                        name, "a successful ExitStandby activity", TimeUnit.SECONDS.toNanos(60), 1000);
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

    private static List<Instance> awaitMembers(AutoScalingClient autoScaling, String name,
                                               LifecycleState... states) throws InterruptedException {
        AtomicReference<List<Instance>> members = new AtomicReference<>(List.of());
        TestFixtures.await(() -> {
            members.set(group(autoScaling, name).instances());
            return members.get().size() == states.length
                    && members.get().stream().map(Instance::lifecycleState).toList().equals(List.of(states));
        }, name, List.of(states) + " instances", TimeUnit.SECONDS.toNanos(120), 1000);
        return members.get();
    }

    private static void awaitLifecycle(AutoScalingClient autoScaling, String name, String instanceId,
                                       LifecycleState state) throws InterruptedException {
        TestFixtures.await(() -> autoScaling.describeAutoScalingInstances(request -> request.instanceIds(instanceId))
                        .autoScalingInstances().stream()
                        .anyMatch(details -> state.toString().equals(details.lifecycleState())),
                name, instanceId + " in " + state, TimeUnit.SECONDS.toNanos(60), 1000);
    }

    private static AutoScalingGroup group(AutoScalingClient autoScaling, String name) {
        return autoScaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().get(0);
    }
}
