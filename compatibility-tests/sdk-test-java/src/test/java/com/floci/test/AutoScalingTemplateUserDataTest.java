package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceAttributeName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auto Scaling launch-template user data")
class AutoScalingTemplateUserDataTest {
    @Test
    @DisplayName("Launch templates preserve original encoded instance user data")
    void originalEncodingReachesLaunchedInstance() throws InterruptedException {
        String encoded = "/w";
        String name = TestFixtures.uniqueName("asg-template-data");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            ec2.createLaunchTemplate(r -> r.launchTemplateName(name).launchTemplateData(d -> d
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro").userData(encoded)));
            try {
                LaunchTemplateSpecification template = LaunchTemplateSpecification.builder()
                        .launchTemplateName(name).version("1").build();
                CreateAutoScalingGroupRequest.Builder create = CreateAutoScalingGroupRequest.builder()
                        .autoScalingGroupName(name).minSize(0).maxSize(1).desiredCapacity(1)
                        .availabilityZones(TestFixtures.availabilityZone()).launchTemplate(template);
                asg.createAutoScalingGroup(create.build());
                try {
                    TestFixtures.await(() -> !describe(asg, name).instances().isEmpty(),
                            name, "one launched instance", Duration.ofSeconds(30).toNanos(), 250);
                    AutoScalingGroup group = describe(asg, name);
                    assertThat(group.instances()).hasSize(1);
                    String id = group.instances().get(0).instanceId();
                    assertThat(ec2.describeInstanceAttribute(r -> r.instanceId(id)
                            .attribute(InstanceAttributeName.USER_DATA)).userData().value()).isEqualTo(encoded);
                } finally {
                    asg.deleteAutoScalingGroup(r -> r.autoScalingGroupName(name).forceDelete(true));
                }
            } finally {
                ec2.deleteLaunchTemplate(r -> r.launchTemplateName(name));
            }
        }
    }

    private static AutoScalingGroup describe(AutoScalingClient asg, String name) {
        return asg.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().get(0);
    }
}
