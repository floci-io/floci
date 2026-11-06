package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AttachInstancesRequest;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.AvailabilityZoneDistribution;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationTarget;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateLaunchConfigurationRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.DesiredConfiguration;
import software.amazon.awssdk.services.autoscaling.model.DisableMetricsCollectionRequest;
import software.amazon.awssdk.services.autoscaling.model.EnableMetricsCollectionRequest;
import software.amazon.awssdk.services.autoscaling.model.EnabledMetric;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstanceMetadataEndpointState;
import software.amazon.awssdk.services.autoscaling.model.InstanceMetadataHttpTokensState;
import software.amazon.awssdk.services.autoscaling.model.InstanceMetadataOptions;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.StartInstanceRefreshRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateVersionRequest;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("Auto Scaling")
class AutoScalingTest {

    private static final String MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE =
            "You must use a valid fully-formed launch template. The request must contain the parameter ImageId";
    private static final String INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE =
            "Valid requests must contain either the InstanceID parameter "
                    + "or both the ImageId and InstanceType parameters.";
    private static final String ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE =
            "An active instance refresh with a desired configuration exists. All configuration options derived from the desired configuration are not available for update while the instance refresh is active.";

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
                .availabilityZones(TestFixtures.availabilityZone())
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

    @Test
    @DisplayName("Launch configuration metadata options round-trip through the SDK")
    void launchConfigurationMetadataOptionsRoundTripThroughTheSdk() {
        String partial = TestFixtures.uniqueName("sdk-lc-metadata");
        String omitted = TestFixtures.uniqueName("sdk-lc-no-metadata");
        autoScaling.createLaunchConfiguration(CreateLaunchConfigurationRequest.builder()
                .launchConfigurationName(partial)
                .imageId("ami-12345678")
                .instanceType("t3.micro")
                .metadataOptions(InstanceMetadataOptions.builder()
                        .httpTokens(InstanceMetadataHttpTokensState.REQUIRED)
                        .build())
                .build());
        try {
            autoScaling.createLaunchConfiguration(CreateLaunchConfigurationRequest.builder()
                    .launchConfigurationName(omitted)
                    .imageId("ami-12345678")
                    .instanceType("t3.micro")
                    .build());
            try {
                InstanceMetadataOptions described = autoScaling.describeLaunchConfigurations(r -> r
                                .launchConfigurationNames(partial))
                        .launchConfigurations().get(0).metadataOptions();
                assertThat(described.httpTokens()).isEqualTo(InstanceMetadataHttpTokensState.REQUIRED);
                assertThat(described.httpEndpoint()).isEqualTo(InstanceMetadataEndpointState.ENABLED);
                assertThat(described.httpPutResponseHopLimit()).isEqualTo(1);
                assertThat(autoScaling.describeLaunchConfigurations(r -> r.launchConfigurationNames(omitted))
                        .launchConfigurations().get(0).metadataOptions()).isNull();
            } finally {
                autoScaling.deleteLaunchConfiguration(r -> r.launchConfigurationName(omitted));
            }
        } finally {
            autoScaling.deleteLaunchConfiguration(r -> r.launchConfigurationName(partial));
        }
        assertThatThrownBy(() -> autoScaling.createLaunchConfiguration(CreateLaunchConfigurationRequest.builder()
                .launchConfigurationName(TestFixtures.uniqueName("sdk-lc-bad-metadata"))
                .imageId("ami-12345678")
                .instanceType("t3.micro")
                .metadataOptions(InstanceMetadataOptions.builder().httpPutResponseHopLimit(65).build())
                .build()))
                .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                });
    }

    @Test
    @DisplayName("UpdateAutoScalingGroup rejects desired configuration changes during active instance refresh")
    void activeInstanceRefreshDesiredConfigurationConflictMapsToSdkAutoScalingException() {
        String launchTemplateName = TestFixtures.uniqueName("sdk-active-refresh");
        String autoScalingGroupName = TestFixtures.uniqueName("sdk-active-refresh-asg");

        ec2.createLaunchTemplate(CreateLaunchTemplateRequest.builder()
                .launchTemplateName(launchTemplateName)
                .launchTemplateData(RequestLaunchTemplateData.builder()
                        .imageId("ami-12345678")
                        .instanceType("t3.micro")
                        .build())
                .build());
        ec2.createLaunchTemplateVersion(CreateLaunchTemplateVersionRequest.builder()
                .launchTemplateName(launchTemplateName)
                .sourceVersion("1")
                .launchTemplateData(RequestLaunchTemplateData.builder()
                        .imageId("ami-12345678")
                        .instanceType("t3.small")
                        .build())
                .build());
        ec2.createLaunchTemplateVersion(CreateLaunchTemplateVersionRequest.builder()
                .launchTemplateName(launchTemplateName)
                .sourceVersion("1")
                .launchTemplateData(RequestLaunchTemplateData.builder()
                        .imageId("ami-12345678")
                        .instanceType("t3.medium")
                        .build())
                .build());
        autoScaling.createAutoScalingGroup(CreateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .launchTemplate(LaunchTemplateSpecification.builder()
                        .launchTemplateName(launchTemplateName)
                        .version("1")
                        .build())
                .minSize(0)
                .maxSize(1)
                .desiredCapacity(1)
                .availabilityZones(TestFixtures.availabilityZone())
                .build());
        autoScaling.attachInstances(AttachInstancesRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .instanceIds("i-sdk-active-refresh")
                .build());
        autoScaling.startInstanceRefresh(StartInstanceRefreshRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .desiredConfiguration(DesiredConfiguration.builder()
                        .launchTemplate(LaunchTemplateSpecification.builder()
                                .launchTemplateName(launchTemplateName)
                                .version("2")
                                .build())
                        .build())
                .build());

        assertThatThrownBy(() -> autoScaling.updateAutoScalingGroup(UpdateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .launchTemplate(LaunchTemplateSpecification.builder()
                        .launchTemplateName(launchTemplateName)
                        .version("3")
                        .build())
                .build()))
                .isInstanceOfSatisfying(AutoScalingException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().serviceName()).isEqualTo("AutoScaling");
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(error.awsErrorDetails().errorMessage())
                            .isEqualTo(ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE);
                    assertThat(error.requestId()).isNotBlank();
                    assertThat(error.getMessage()).contains(
                            ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE,
                            "Service: AutoScaling",
                            "Status Code: 400",
                            "Request ID: ",
                            "SDK Attempt Count: 1");
                });
    }

    @Test
    @DisplayName("DescribeAutoScalingGroups returns the optional structures and EnabledMetrics a client set")
    void optionalStructuresAndEnabledMetricsRoundTripThroughTheSdk() {
        String launchTemplateName = TestFixtures.uniqueName("sdk-optional-structures");
        String autoScalingGroupName = TestFixtures.uniqueName("sdk-optional-structures-asg");
        String roleArn = "arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/"
                + "AWSServiceRoleForAutoScaling_sdk";

        ec2.createLaunchTemplate(CreateLaunchTemplateRequest.builder()
                .launchTemplateName(launchTemplateName)
                .launchTemplateData(RequestLaunchTemplateData.builder()
                        .imageId("ami-12345678")
                        .instanceType("t3.micro")
                        .build())
                .build());
        autoScaling.createAutoScalingGroup(CreateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(autoScalingGroupName)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder()
                        .launchTemplate(LaunchTemplate.builder()
                                .launchTemplateSpecification(LaunchTemplateSpecification.builder()
                                        .launchTemplateName(launchTemplateName)
                                        .version("1")
                                        .build())
                                .overrides(
                                        LaunchTemplateOverrides.builder()
                                                .instanceType("t3.micro").weightedCapacity("1").build(),
                                        LaunchTemplateOverrides.builder()
                                                .instanceType("t3.large").weightedCapacity("4").build())
                                .build())
                        .build())
                .minSize(0)
                .maxSize(4)
                .desiredCapacity(0)
                .availabilityZones(TestFixtures.availabilityZone())
                .serviceLinkedRoleARN(roleArn)
                .instanceMaintenancePolicy(InstanceMaintenancePolicy.builder()
                        .minHealthyPercentage(90)
                        .maxHealthyPercentage(120)
                        .build())
                .availabilityZoneDistribution(AvailabilityZoneDistribution.builder()
                        .capacityDistributionStrategy("balanced-only")
                        .build())
                .capacityReservationSpecification(CapacityReservationSpecification.builder()
                        .capacityReservationPreference("capacity-reservations-first")
                        .capacityReservationTarget(CapacityReservationTarget.builder()
                                .capacityReservationIds("cr-0123456789abcdef0")
                                .build())
                        .build())
                .build());
        try {
            autoScaling.enableMetricsCollection(EnableMetricsCollectionRequest.builder()
                    .autoScalingGroupName(autoScalingGroupName)
                    .granularity("1Minute")
                    .metrics("GroupMinSize", "GroupMaxSize")
                    .build());

            AutoScalingGroup group = describe(autoScalingGroupName);
            assertThat(group.serviceLinkedRoleARN()).isEqualTo(roleArn);
            assertThat(group.instanceMaintenancePolicy().minHealthyPercentage()).isEqualTo(90);
            assertThat(group.instanceMaintenancePolicy().maxHealthyPercentage()).isEqualTo(120);
            assertThat(group.availabilityZoneDistribution().capacityDistributionStrategyAsString())
                    .isEqualTo("balanced-only");
            assertThat(group.capacityReservationSpecification().capacityReservationPreferenceAsString())
                    .isEqualTo("capacity-reservations-first");
            assertThat(group.capacityReservationSpecification().capacityReservationTarget()
                    .capacityReservationIds()).containsExactly("cr-0123456789abcdef0");
            assertThat(group.mixedInstancesPolicy().launchTemplate().overrides())
                    .extracting(LaunchTemplateOverrides::weightedCapacity)
                    .containsExactly("1", "4");
            assertThat(group.enabledMetrics())
                    .extracting(EnabledMetric::metric, EnabledMetric::granularity)
                    .containsExactly(
                            tuple("GroupMinSize", "1Minute"),
                            tuple("GroupMaxSize", "1Minute"));

            autoScaling.updateAutoScalingGroup(UpdateAutoScalingGroupRequest.builder()
                    .autoScalingGroupName(autoScalingGroupName)
                    .instanceMaintenancePolicy(InstanceMaintenancePolicy.builder()
                            .minHealthyPercentage(-1)
                            .maxHealthyPercentage(-1)
                            .build())
                    .build());
            autoScaling.disableMetricsCollection(DisableMetricsCollectionRequest.builder()
                    .autoScalingGroupName(autoScalingGroupName)
                    .build());

            AutoScalingGroup cleared = describe(autoScalingGroupName);
            assertThat(cleared.instanceMaintenancePolicy()).isNull();
            assertThat(cleared.enabledMetrics()).isEmpty();
            assertThat(cleared.serviceLinkedRoleARN()).isEqualTo(roleArn);
        } finally {
            autoScaling.deleteAutoScalingGroup(DeleteAutoScalingGroupRequest.builder()
                    .autoScalingGroupName(autoScalingGroupName)
                    .forceDelete(true)
                    .build());
        }
    }

    private static AutoScalingGroup describe(String autoScalingGroupName) {
        return autoScaling.describeAutoScalingGroups(r -> r.autoScalingGroupNames(autoScalingGroupName))
                .autoScalingGroups().get(0);
    }
}
