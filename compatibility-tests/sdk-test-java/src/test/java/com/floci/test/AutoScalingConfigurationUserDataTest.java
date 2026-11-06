package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.LaunchConfiguration;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Auto Scaling launch-configuration user data")
class AutoScalingConfigurationUserDataTest {
    static Stream<Arguments> userData() {
        return Stream.of(false, true).flatMap(fromInstance -> Stream.of(null, "", "YQ", "/w",
                "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=")
                .map(encoded -> Arguments.of(fromInstance, encoded)));
    }

    static Stream<String> rejectedData() {
        return Stream.of("not base64!", "H4sI", Base64.getEncoder().encodeToString(new byte[16385]));
    }

    @ParameterizedTest
    @MethodSource("rejectedData")
    void rejectedDataLeavesConfigurationNameReusable(String encoded) {
        String name = TestFixtures.uniqueName("asg-invalid-data");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient()) {
            AutoScalingException error = assertThrows(AutoScalingException.class, () ->
                    asg.createLaunchConfiguration(r -> r.launchConfigurationName(name)
                            .imageId("ami-amazonlinux2023").instanceType("t3.micro").userData(encoded)));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
            assertThat(asg.describeLaunchConfigurations(r -> r.launchConfigurationNames(name))
                    .launchConfigurations()).isEmpty();
            asg.createLaunchConfiguration(r -> r.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro").userData("YQ=="));
            asg.deleteLaunchConfiguration(r -> r.launchConfigurationName(name));
        }
    }

    @ParameterizedTest
    @MethodSource("userData")
    @DisplayName("Direct input and source instances retain their encoded data")
    void configurationRetainsOriginalEncoding(boolean fromInstance, String encoded) {
        String name = TestFixtures.uniqueName("asg-config-data");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            String instanceId = fromInstance ? ec2.runInstances(r -> r.imageId("ami-amazonlinux2023")
                    .instanceType("t3.micro").minCount(1).maxCount(1).userData(encoded))
                    .instances().get(0).instanceId() : null;
            try {
                asg.createLaunchConfiguration(r -> r.launchConfigurationName(name).instanceId(instanceId)
                        .imageId(fromInstance ? null : "ami-amazonlinux2023")
                        .instanceType(fromInstance ? null : "t3.micro").userData(fromInstance ? null : encoded));
                try {
                    LaunchConfiguration configuration = asg.describeLaunchConfigurations(r -> r
                            .launchConfigurationNames(name)).launchConfigurations().get(0);
                    assertThat(configuration.launchConfigurationName()).isEqualTo(name);
                    assertThat(configuration.imageId()).isEqualTo("ami-amazonlinux2023");
                    if (encoded == null || encoded.isEmpty()) {
                        assertThat(configuration.userData()).isNullOrEmpty();
                    } else {
                        assertThat(configuration.userData()).isEqualTo(encoded);
                    }
                } finally {
                    asg.deleteLaunchConfiguration(r -> r.launchConfigurationName(name));
                }
            } finally {
                if (instanceId != null) {
                    ec2.terminateInstances(r -> r.instanceIds(instanceId));
                }
            }
        }
    }
}
