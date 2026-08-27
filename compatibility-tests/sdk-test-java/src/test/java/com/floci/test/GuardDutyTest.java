package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.guardduty.GuardDutyClient;
import software.amazon.awssdk.services.guardduty.model.AdminStatus;
import software.amazon.awssdk.services.guardduty.model.AutoEnableMembers;
import software.amazon.awssdk.services.guardduty.model.BadRequestException;
import software.amazon.awssdk.services.guardduty.model.CreateDetectorResponse;
import software.amazon.awssdk.services.guardduty.model.DescribeOrganizationConfigurationResponse;
import software.amazon.awssdk.services.guardduty.model.DetectorFeature;
import software.amazon.awssdk.services.guardduty.model.DetectorFeatureConfiguration;
import software.amazon.awssdk.services.guardduty.model.DetectorAdditionalConfiguration;
import software.amazon.awssdk.services.guardduty.model.DetectorStatus;
import software.amazon.awssdk.services.guardduty.model.FeatureAdditionalConfiguration;
import software.amazon.awssdk.services.guardduty.model.FeatureStatus;
import software.amazon.awssdk.services.guardduty.model.FindingPublishingFrequency;
import software.amazon.awssdk.services.guardduty.model.GetDetectorResponse;
import software.amazon.awssdk.services.guardduty.model.ListOrganizationAdminAccountsResponse;
import software.amazon.awssdk.services.guardduty.model.OrgFeature;
import software.amazon.awssdk.services.guardduty.model.OrgFeatureAdditionalConfiguration;
import software.amazon.awssdk.services.guardduty.model.OrgFeatureStatus;
import software.amazon.awssdk.services.guardduty.model.OrganizationAdditionalConfiguration;
import software.amazon.awssdk.services.guardduty.model.OrganizationFeatureConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuardDuty")
class GuardDutyTest {

    @Test
    void detectorLifecycleUsesAwsSdkWireShapes() throws Exception {
        try (GuardDutyClient guardduty = TestFixtures.guardDutyClient()) {
            CreateDetectorResponse created = guardduty.createDetector(request -> request
                    .enable(true)
                    .findingPublishingFrequency(FindingPublishingFrequency.SIX_HOURS)
                    .features(List.of(
                            DetectorFeatureConfiguration.builder()
                                    .name(DetectorFeature.RUNTIME_MONITORING)
                                    .status(FeatureStatus.ENABLED)
                                    .additionalConfiguration(List.of(
                                            DetectorAdditionalConfiguration.builder()
                                                    .name(FeatureAdditionalConfiguration
                                                            .ECS_FARGATE_AGENT_MANAGEMENT)
                                                    .status(FeatureStatus.ENABLED)
                                                    .build(),
                                            DetectorAdditionalConfiguration.builder()
                                                    .name(FeatureAdditionalConfiguration.EC2_AGENT_MANAGEMENT)
                                                    .status(FeatureStatus.ENABLED)
                                                    .build(),
                                            DetectorAdditionalConfiguration.builder()
                                                    .name(FeatureAdditionalConfiguration.EKS_ADDON_MANAGEMENT)
                                                    .status(FeatureStatus.DISABLED)
                                                    .build()))
                                    .build(),
                            DetectorFeatureConfiguration.builder()
                                    .name(DetectorFeature.S3_DATA_EVENTS)
                                    .status(FeatureStatus.ENABLED)
                                    .build()))
                    .tags(Map.of("env", "compat")));
            String detectorId = created.detectorId();
            assertThat(detectorId).hasSize(32);

            try (AutoCloseable deleteDetector = () ->
                    guardduty.deleteDetector(request -> request.detectorId(detectorId))) {
                GetDetectorResponse detector =
                        guardduty.getDetector(request -> request.detectorId(detectorId));
                assertThat(detector.status()).isEqualTo(DetectorStatus.ENABLED);
                assertThat(detector.findingPublishingFrequency())
                        .isEqualTo(FindingPublishingFrequency.SIX_HOURS);
                assertThat(detector.serviceRole()).contains(":role/aws-service-role/guardduty");
                assertThat(detector.tags()).containsEntry("env", "compat");
                assertThat(detector.features())
                        .extracting(feature -> feature.name().toString())
                        .containsExactly("RUNTIME_MONITORING", "S3_DATA_EVENTS");
                assertThat(detector.features().get(0).additionalConfiguration())
                        .extracting(configuration -> configuration.name().toString())
                        .containsExactly(
                                "ECS_FARGATE_AGENT_MANAGEMENT",
                                "EC2_AGENT_MANAGEMENT",
                                "EKS_ADDON_MANAGEMENT");

                assertThat(guardduty.listDetectors(request -> {
                }).detectorIds()).contains(detectorId);

                guardduty.updateDetector(request -> request
                        .detectorId(detectorId)
                        .enable(false)
                        .findingPublishingFrequency(FindingPublishingFrequency.ONE_HOUR));
                GetDetectorResponse updated =
                        guardduty.getDetector(request -> request.detectorId(detectorId));
                assertThat(updated.status()).isEqualTo(DetectorStatus.DISABLED);
                assertThat(updated.findingPublishingFrequency())
                        .isEqualTo(FindingPublishingFrequency.ONE_HOUR);

                String detectorArn =
                        "arn:aws:guardduty:us-east-1:000000000000:detector/" + detectorId;
                guardduty.tagResource(request -> request
                        .resourceArn(detectorArn)
                        .tags(Map.of("team", "security")));
                assertThat(guardduty.listTagsForResource(request -> request.resourceArn(detectorArn))
                        .tags()).containsEntry("team", "security").containsEntry("env", "compat");
                guardduty.untagResource(request -> request
                        .resourceArn(detectorArn)
                        .tagKeys(List.of("env")));
                assertThat(guardduty.listTagsForResource(request -> request.resourceArn(detectorArn))
                        .tags()).containsOnlyKeys("team");
            }

            assertThatThrownBy(() -> guardduty.getDetector(request -> request.detectorId(detectorId)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("is not owned by the current account");
        }
    }

    @Test
    void organizationConfigurationRoundTripsThroughTheSdk() throws Exception {
        try (GuardDutyClient guardduty = TestFixtures.guardDutyClient()) {
            String detectorId = guardduty.createDetector(request -> request.enable(true)).detectorId();

            try (AutoCloseable deleteDetector = () ->
                    guardduty.deleteDetector(request -> request.detectorId(detectorId))) {
                guardduty.updateOrganizationConfiguration(request -> request
                        .detectorId(detectorId)
                        .autoEnableOrganizationMembers(AutoEnableMembers.ALL)
                        .features(List.of(OrganizationFeatureConfiguration.builder()
                                .name(OrgFeature.RUNTIME_MONITORING)
                                .autoEnable(OrgFeatureStatus.ALL)
                                .additionalConfiguration(List.of(
                                        OrganizationAdditionalConfiguration.builder()
                                                .name(OrgFeatureAdditionalConfiguration
                                                        .ECS_FARGATE_AGENT_MANAGEMENT)
                                                .autoEnable(OrgFeatureStatus.ALL)
                                                .build(),
                                        OrganizationAdditionalConfiguration.builder()
                                                .name(OrgFeatureAdditionalConfiguration.EC2_AGENT_MANAGEMENT)
                                                .autoEnable(OrgFeatureStatus.ALL)
                                                .build(),
                                        OrganizationAdditionalConfiguration.builder()
                                                .name(OrgFeatureAdditionalConfiguration.EKS_ADDON_MANAGEMENT)
                                                .autoEnable(OrgFeatureStatus.NONE)
                                                .build()))
                                .build())));

                DescribeOrganizationConfigurationResponse configuration =
                        guardduty.describeOrganizationConfiguration(request ->
                                request.detectorId(detectorId));
                assertThat(configuration.autoEnableOrganizationMembers()).isEqualTo(AutoEnableMembers.ALL);
                assertThat(configuration.memberAccountLimitReached()).isFalse();
                assertThat(configuration.features())
                        .extracting(feature -> feature.name().toString())
                        .containsExactly("RUNTIME_MONITORING");
                assertThat(configuration.features().get(0).additionalConfiguration())
                        .extracting(feature -> feature.name().toString())
                        .containsExactly(
                                "ECS_FARGATE_AGENT_MANAGEMENT",
                                "EC2_AGENT_MANAGEMENT",
                                "EKS_ADDON_MANAGEMENT");
            }
        }
    }

    @Test
    void organizationAdminAccountLifecycle() {
        try (GuardDutyClient guardduty = TestFixtures.guardDutyClient()) {
            guardduty.enableOrganizationAdminAccount(request ->
                    request.adminAccountId("111111111111"));
            try {
                ListOrganizationAdminAccountsResponse accounts =
                        guardduty.listOrganizationAdminAccounts(request -> {
                        });
                assertThat(accounts.adminAccounts())
                        .anySatisfy(account -> {
                            assertThat(account.adminAccountId()).isEqualTo("111111111111");
                            assertThat(account.adminStatus()).isEqualTo(AdminStatus.ENABLED);
                        });
            } finally {
                guardduty.disableOrganizationAdminAccount(request ->
                        request.adminAccountId("111111111111"));
            }

            assertThatThrownBy(() -> guardduty.disableOrganizationAdminAccount(request ->
                    request.adminAccountId("111111111111")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already been disabled");
        }
    }
}
