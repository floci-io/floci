package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ConfigurationSet;
import software.amazon.awssdk.services.ses.model.ConfigurationSetAttribute;
import software.amazon.awssdk.services.ses.model.ConfigurationSetDoesNotExistException;
import software.amazon.awssdk.services.ses.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.CreateConfigurationSetTrackingOptionsRequest;
import software.amazon.awssdk.services.ses.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DeleteConfigurationSetTrackingOptionsRequest;
import software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse;
import software.amazon.awssdk.services.ses.model.InvalidTrackingOptionsException;
import software.amazon.awssdk.services.ses.model.TrackingOptions;
import software.amazon.awssdk.services.ses.model.TrackingOptionsAlreadyExistsException;
import software.amazon.awssdk.services.ses.model.TrackingOptionsDoesNotExistException;
import software.amazon.awssdk.services.ses.model.UpdateConfigurationSetReputationMetricsEnabledRequest;
import software.amazon.awssdk.services.ses.model.UpdateConfigurationSetTrackingOptionsRequest;
import software.amazon.awssdk.services.ses.model.VerifyDomainIdentityRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v1 (Query) ConfigurationSet option APIs:
 * {@code Create/Update/DeleteConfigurationSetTrackingOptions} and
 * {@code UpdateConfigurationSetReputationMetricsEnabled}. Verifies AWS Java SDK v2
 * marshalling of the SES v1 (Query) API, the modeled error types (verified domain
 * requirement, lifecycle
 * conflicts, unknown configuration set), and cross-API visibility of the
 * reputation-metrics flag through the v2 GetConfigurationSet response.
 */
@DisplayName("SES v1 ConfigurationSet Tracking & Reputation Options")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetTrackingOptionsTest {

    private static final String CS = "compat-cs-v1-tracking";
    private static final String DOMAIN = "track.compat.floci.test";
    private static final String DOMAIN_2 = "track2.compat.floci.test";

    private static SesClient sesV1;
    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        deleteCsQuietly();
        sesV1.verifyDomainIdentity(VerifyDomainIdentityRequest.builder().domain(DOMAIN).build());
        sesV1.verifyDomainIdentity(VerifyDomainIdentityRequest.builder().domain(DOMAIN_2).build());
        sesV1.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSet(ConfigurationSet.builder().name(CS).build()).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV1 != null) {
            deleteCsQuietly();
            sesV1.close();
        }
        if (sesV2 != null) {
            sesV2.close();
        }
    }

    private static void deleteCsQuietly() {
        try {
            sesV1.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                    .configurationSetName(CS).build());
        } catch (Exception ignored) {}
    }

    private static DescribeConfigurationSetResponse describe(ConfigurationSetAttribute attr) {
        return sesV1.describeConfigurationSet(DescribeConfigurationSetRequest.builder()
                .configurationSetName(CS)
                .configurationSetAttributeNames(attr)
                .build());
    }

    @Test
    @Order(1)
    void v1CreatedSet_reputationMetricsDefaultsDisabled() {
        assertThat(describe(ConfigurationSetAttribute.REPUTATION_OPTIONS)
                .reputationOptions().reputationMetricsEnabled()).isFalse();
    }

    @Test
    @Order(2)
    void createTrackingOptions_unverifiedDomain_throwsInvalidTrackingOptions() {
        assertThatThrownBy(() -> sesV1.createConfigurationSetTrackingOptions(
                CreateConfigurationSetTrackingOptionsRequest.builder()
                        .configurationSetName(CS)
                        .trackingOptions(TrackingOptions.builder()
                                .customRedirectDomain("never-verified.example.com").build())
                        .build()))
                .isInstanceOf(InvalidTrackingOptionsException.class);
    }

    @Test
    @Order(3)
    void createTrackingOptions_verifiedDomain_stored() {
        sesV1.createConfigurationSetTrackingOptions(CreateConfigurationSetTrackingOptionsRequest.builder()
                .configurationSetName(CS)
                .trackingOptions(TrackingOptions.builder().customRedirectDomain(DOMAIN).build())
                .build());

        assertThat(describe(ConfigurationSetAttribute.TRACKING_OPTIONS)
                .trackingOptions().customRedirectDomain()).isEqualTo(DOMAIN);
    }

    @Test
    @Order(4)
    void createTrackingOptions_alreadyExists_throws() {
        assertThatThrownBy(() -> sesV1.createConfigurationSetTrackingOptions(
                CreateConfigurationSetTrackingOptionsRequest.builder()
                        .configurationSetName(CS)
                        .trackingOptions(TrackingOptions.builder().customRedirectDomain(DOMAIN).build())
                        .build()))
                .isInstanceOf(TrackingOptionsAlreadyExistsException.class);
    }

    @Test
    @Order(5)
    void updateTrackingOptions_changesDomain() {
        sesV1.updateConfigurationSetTrackingOptions(UpdateConfigurationSetTrackingOptionsRequest.builder()
                .configurationSetName(CS)
                .trackingOptions(TrackingOptions.builder().customRedirectDomain(DOMAIN_2).build())
                .build());

        assertThat(describe(ConfigurationSetAttribute.TRACKING_OPTIONS)
                .trackingOptions().customRedirectDomain()).isEqualTo(DOMAIN_2);
    }

    @Test
    @Order(6)
    void deleteTrackingOptions_removesThem() {
        sesV1.deleteConfigurationSetTrackingOptions(DeleteConfigurationSetTrackingOptionsRequest.builder()
                .configurationSetName(CS).build());

        TrackingOptions options = describe(ConfigurationSetAttribute.TRACKING_OPTIONS).trackingOptions();
        assertThat(options == null || options.customRedirectDomain() == null).isTrue();
    }

    @Test
    @Order(7)
    void deleteTrackingOptions_whenNoneSet_throws() {
        assertThatThrownBy(() -> sesV1.deleteConfigurationSetTrackingOptions(
                DeleteConfigurationSetTrackingOptionsRequest.builder().configurationSetName(CS).build()))
                .isInstanceOf(TrackingOptionsDoesNotExistException.class);
    }

    @Test
    @Order(8)
    void updateReputationMetricsEnabled_visibleViaV1AndV2() {
        sesV1.updateConfigurationSetReputationMetricsEnabled(
                UpdateConfigurationSetReputationMetricsEnabledRequest.builder()
                        .configurationSetName(CS).enabled(true).build());

        assertThat(describe(ConfigurationSetAttribute.REPUTATION_OPTIONS)
                .reputationOptions().reputationMetricsEnabled()).isTrue();

        GetConfigurationSetResponse v2 = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CS).build());
        assertThat(v2.reputationOptions().reputationMetricsEnabled()).isTrue();
    }

    @Test
    @Order(9)
    void reputationMetrics_unknownConfigSet_throwsConfigurationSetDoesNotExist() {
        assertThatThrownBy(() -> sesV1.updateConfigurationSetReputationMetricsEnabled(
                UpdateConfigurationSetReputationMetricsEnabledRequest.builder()
                        .configurationSetName("compat-cs-ghost").enabled(true).build()))
                .isInstanceOf(ConfigurationSetDoesNotExistException.class);
    }

    @Test
    @Order(10)
    void createTrackingOptions_unknownConfigSet_throwsConfigurationSetDoesNotExist() {
        assertThatThrownBy(() -> sesV1.createConfigurationSetTrackingOptions(
                CreateConfigurationSetTrackingOptionsRequest.builder()
                        .configurationSetName("compat-cs-ghost")
                        .trackingOptions(TrackingOptions.builder().customRedirectDomain(DOMAIN).build())
                        .build()))
                .isInstanceOf(ConfigurationSetDoesNotExistException.class);
    }
}
