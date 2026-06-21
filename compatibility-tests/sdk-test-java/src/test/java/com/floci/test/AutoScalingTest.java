package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateLaunchConfigurationRequest;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Auto Scaling")
class AutoScalingTest {

    private static final String MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE =
            "You must use a valid fully-formed launch template. The request must contain the parameter ImageId";
    private static final String INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE =
            "Valid requests must contain either the InstanceID parameter "
                    + "or both the ImageId and InstanceType parameters.";

    private static AutoScalingClient autoScaling;
    private static Ec2Client ec2;

    @BeforeAll
    static void setup() {
        autoScaling = TestFixtures.autoScalingClient();
        ec2 = TestFixtures.ec2Client();
    }

    @AfterAll
    static void cleanup() {
        if (autoScaling != null) {
            autoScaling.close();
        }
        if (ec2 != null) {
            ec2.close();
        }
    }

    @Test
    @DisplayName("CreateAutoScalingGroup rejects launch templates without ImageId")
    void missingLaunchTemplateImageIdMapsToSdkAutoScalingException() {
        String launchTemplateName = TestFixtures.uniqueName("sdk-missing-image");
        String autoScalingGroupName = TestFixtures.uniqueName("sdk-missing-image-asg");

        ec2.createLaunchTemplate(CreateLaunchTemplateRequest.builder()
                .launchTemplateName(launchTemplateName)
                .launchTemplateData(RequestLaunchTemplateData.builder()
                        .instanceType("t3.micro")
                        .build())
                .build());

        assertThatThrownBy(() -> autoScaling.createAutoScalingGroup(CreateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .launchTemplate(LaunchTemplateSpecification.builder()
                        .launchTemplateName(launchTemplateName)
                        .version("1")
                        .build())
                .minSize(0)
                .maxSize(1)
                .desiredCapacity(1)
                .availabilityZones("us-east-1a")
                .build()))
                .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().serviceName()).isEqualTo("AutoScaling");
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(error.awsErrorDetails().errorMessage())
                            .isEqualTo(MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE);
                    assertThat(error.requestId()).isNotBlank();
                    assertThat(error.getMessage()).contains(
                            MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE,
                            "Service: AutoScaling",
                            "Status Code: 400",
                            "Request ID: ",
                            "SDK Attempt Count: 1");
                });
    }

    @Test
    @DisplayName("CreateLaunchConfiguration rejects missing ImageId or InstanceType")
    void missingLaunchConfigurationImageIdMapsToSdkAutoScalingExceptionAtCreateTime() {
        String suffix = TestFixtures.uniqueName("sdk-lc");

        assertThatThrownBy(() -> autoScaling.createLaunchConfiguration(CreateLaunchConfigurationRequest.builder()
                .launchConfigurationName(suffix + "-missing-image")
                .instanceType("t3.micro")
                .build()))
                .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().serviceName()).isEqualTo("AutoScaling");
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(error.awsErrorDetails().errorMessage())
                            .isEqualTo(INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE);
                    assertThat(error.requestId()).isNotBlank();
                    assertThat(error.getMessage()).contains(
                            INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE,
                            "Service: AutoScaling",
                            "Status Code: 400",
                            "Request ID: ",
                            "SDK Attempt Count: 1");
                });

        assertThatThrownBy(() -> autoScaling.createLaunchConfiguration(CreateLaunchConfigurationRequest.builder()
                .launchConfigurationName(suffix + "-missing-type")
                .imageId("ami-12345678")
                .build()))
                .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().serviceName()).isEqualTo("AutoScaling");
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(error.awsErrorDetails().errorMessage())
                            .isEqualTo(INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE);
                });
    }
}
