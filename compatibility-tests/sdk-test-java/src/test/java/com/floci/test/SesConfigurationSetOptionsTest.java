package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.ArchivingOptions;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.DashboardOptions;
import software.amazon.awssdk.services.sesv2.model.GuardianOptions;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.CreateDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeliveryOptions;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetArchivingOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetDeliveryOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetReputationOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetTrackingOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.ReputationOptions;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetVdmOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.TrackingOptions;
import software.amazon.awssdk.services.sesv2.model.VdmOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the per-configuration-set option groups added to
 * the SES v2 surface. Verifies the AWS Java SDK v2 marshalling of:
 *   - {@code putConfigurationSetReputationOptions} / {@code ReputationOptions}
 *   - {@code putConfigurationSetTrackingOptions}   / {@code TrackingOptions}
 *   - {@code putConfigurationSetDeliveryOptions}   / {@code DeliveryOptions}
 *   - {@code putConfigurationSetArchivingOptions}  / {@code ArchivingOptions}
 * and the {@code GetConfigurationSet} response carrying each block, plus the
 * server-side validation errors surfaced as {@link BadRequestException} /
 * {@link NotFoundException}.
 */
@DisplayName("SES v2 Configuration-Set Option Groups")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetOptionsTest {

    private static final String CS_NAME = "compat-cs-options";
    private static final String CREATE_CS = "compat-cs-options-create";
    private static final String CREATE_CS_BAD = "compat-cs-options-create-bad";
    private static final String TRACK_DOMAIN = "compat-track.example.com";
    private static final String ARCHIVE_ARN =
            "arn:aws:ses:us-east-1:123456789012:mailmanager-archive/a-abcdefghijklmnopqrstuvwx";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        deleteConfigSetQuietly(CS_NAME);
        deleteConfigSetQuietly(CREATE_CS);
        deleteConfigSetQuietly(CREATE_CS_BAD);
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(CS_NAME).build());
        // CustomRedirectDomain must be a verified domain identity.
        try {
            sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                    .emailIdentity(TRACK_DOMAIN).build());
        } catch (Exception ignored) {}
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            deleteConfigSetQuietly(CS_NAME);
            deleteConfigSetQuietly(CREATE_CS);
            deleteConfigSetQuietly(CREATE_CS_BAD);
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(TRACK_DOMAIN).build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
    }

    private static void deleteConfigSetQuietly(String name) {
        try {
            sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                    .configurationSetName(name).build());
        } catch (Exception ignored) {}
    }

    private static GetConfigurationSetResponse getConfigSet() {
        return sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(CS_NAME).build());
    }

    @Test
    @Order(1)
    void getConfigurationSet_reputationDefaultsToEnabled() {
        assertThat(getConfigSet().reputationOptions()).isNotNull();
        assertThat(getConfigSet().reputationOptions().reputationMetricsEnabled()).isTrue();
    }

    @Test
    @Order(2)
    void putReputationOptions_disables() {
        sesV2.putConfigurationSetReputationOptions(PutConfigurationSetReputationOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .reputationMetricsEnabled(false)
                .build());
        assertThat(getConfigSet().reputationOptions().reputationMetricsEnabled()).isFalse();
    }

    @Test
    @Order(3)
    void putDeliveryOptions_roundTrips() {
        sesV2.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .tlsPolicy("REQUIRE")
                .maxDeliverySeconds(600L)
                .build());
        GetConfigurationSetResponse response = getConfigSet();
        assertThat(response.deliveryOptions()).isNotNull();
        assertThat(response.deliveryOptions().tlsPolicyAsString()).isEqualTo("REQUIRE");
        assertThat(response.deliveryOptions().maxDeliverySeconds()).isEqualTo(600L);
    }

    @Test
    @Order(4)
    void putDeliveryOptions_nonExistentSendingPool_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.putConfigurationSetDeliveryOptions(
                PutConfigurationSetDeliveryOptionsRequest.builder()
                        .configurationSetName(CS_NAME)
                        .sendingPoolName("compat-ghost-pool")
                        .build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @Order(5)
    void putTrackingOptions_verifiedDomain_roundTrips() {
        sesV2.putConfigurationSetTrackingOptions(PutConfigurationSetTrackingOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .customRedirectDomain(TRACK_DOMAIN)
                .httpsPolicy("REQUIRE")
                .build());
        GetConfigurationSetResponse response = getConfigSet();
        assertThat(response.trackingOptions()).isNotNull();
        assertThat(response.trackingOptions().customRedirectDomain()).isEqualTo(TRACK_DOMAIN);
        assertThat(response.trackingOptions().httpsPolicyAsString()).isEqualTo("REQUIRE");
    }

    @Test
    @Order(6)
    void putTrackingOptions_unverifiedDomain_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.putConfigurationSetTrackingOptions(
                PutConfigurationSetTrackingOptionsRequest.builder()
                        .configurationSetName(CS_NAME)
                        .customRedirectDomain("compat-unverified.example.com")
                        .httpsPolicy("REQUIRE")
                        .build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @Order(7)
    void putArchivingOptions_roundTrips() {
        sesV2.putConfigurationSetArchivingOptions(PutConfigurationSetArchivingOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .archiveArn(ARCHIVE_ARN)
                .build());
        GetConfigurationSetResponse response = getConfigSet();
        assertThat(response.archivingOptions()).isNotNull();
        assertThat(response.archivingOptions().archiveArn()).isEqualTo(ARCHIVE_ARN);
    }

    @Test
    @Order(8)
    void putReputationOptions_unknownConfigurationSet_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.putConfigurationSetReputationOptions(
                PutConfigurationSetReputationOptionsRequest.builder()
                        .configurationSetName("compat-cs-options-missing")
                        .reputationMetricsEnabled(false)
                        .build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(9)
    void createConfigurationSet_withInlineOptions_roundTrips() {
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(CREATE_CS)
                .reputationOptions(ReputationOptions.builder().reputationMetricsEnabled(false).build())
                .trackingOptions(TrackingOptions.builder()
                        .customRedirectDomain(TRACK_DOMAIN).httpsPolicy("REQUIRE").build())
                .deliveryOptions(DeliveryOptions.builder()
                        .tlsPolicy("REQUIRE").maxDeliverySeconds(900L).build())
                .archivingOptions(ArchivingOptions.builder().archiveArn(ARCHIVE_ARN).build())
                .build());

        GetConfigurationSetResponse response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CREATE_CS).build());
        assertThat(response.reputationOptions().reputationMetricsEnabled()).isFalse();
        assertThat(response.trackingOptions().customRedirectDomain()).isEqualTo(TRACK_DOMAIN);
        assertThat(response.trackingOptions().httpsPolicyAsString()).isEqualTo("REQUIRE");
        assertThat(response.deliveryOptions().tlsPolicyAsString()).isEqualTo("REQUIRE");
        assertThat(response.deliveryOptions().maxDeliverySeconds()).isEqualTo(900L);
        assertThat(response.archivingOptions().archiveArn()).isEqualTo(ARCHIVE_ARN);
    }

    @Test
    @Order(10)
    void createConfigurationSet_withInvalidInlineTracking_throwsAndDoesNotCreate() {
        assertThatThrownBy(() -> sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(CREATE_CS_BAD)
                .trackingOptions(TrackingOptions.builder()
                        .customRedirectDomain("compat-create-unverified.example.com")
                        .httpsPolicy("REQUIRE")
                        .build())
                .build()))
                .isInstanceOf(BadRequestException.class);

        // Validation runs before the store write, so the set must not exist.
        assertThatThrownBy(() -> sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CREATE_CS_BAD).build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(11)
    void putVdmOptions_roundTrips() {
        sesV2.putConfigurationSetVdmOptions(PutConfigurationSetVdmOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .vdmOptions(VdmOptions.builder()
                        .dashboardOptions(DashboardOptions.builder().engagementMetrics("ENABLED").build())
                        .guardianOptions(GuardianOptions.builder().optimizedSharedDelivery("DISABLED").build())
                        .build())
                .build());
        GetConfigurationSetResponse response = getConfigSet();
        assertThat(response.vdmOptions()).isNotNull();
        assertThat(response.vdmOptions().dashboardOptions().engagementMetricsAsString()).isEqualTo("ENABLED");
        assertThat(response.vdmOptions().guardianOptions().optimizedSharedDeliveryAsString()).isEqualTo("DISABLED");
    }

    @Test
    @Order(12)
    void putDeliveryOptions_existingSendingPool_roundTrips() {
        String pool = "compat-cs-options-pool";
        sesV2.createDedicatedIpPool(CreateDedicatedIpPoolRequest.builder().poolName(pool).build());
        try {
            sesV2.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                    .configurationSetName(CS_NAME)
                    .sendingPoolName(pool)
                    .build());
            assertThat(getConfigSet().deliveryOptions().sendingPoolName()).isEqualTo(pool);
        } finally {
            sesV2.deleteDedicatedIpPool(DeleteDedicatedIpPoolRequest.builder().poolName(pool).build());
        }
    }
}
