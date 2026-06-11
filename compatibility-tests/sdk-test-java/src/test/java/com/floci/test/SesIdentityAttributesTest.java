package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.DeleteIdentityRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityMailFromDomainAttributesRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityMailFromDomainAttributesResponse;
import software.amazon.awssdk.services.ses.model.GetIdentityNotificationAttributesRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityNotificationAttributesResponse;
import software.amazon.awssdk.services.ses.model.IdentityMailFromDomainAttributes;
import software.amazon.awssdk.services.ses.model.IdentityNotificationAttributes;
import software.amazon.awssdk.services.ses.model.SetIdentityFeedbackForwardingEnabledRequest;
import software.amazon.awssdk.services.ses.model.SetIdentityHeadersInNotificationsEnabledRequest;
import software.amazon.awssdk.services.ses.model.SetIdentityMailFromDomainRequest;
import software.amazon.awssdk.services.ses.model.VerifyDomainIdentityRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.PutEmailIdentityMailFromAttributesRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES Identity Attributes (MAIL FROM, DKIM, headers)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityAttributesTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String v1Domain;
    private static String v2Domain;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        v1Domain = suffix + ".v1-attrs.example.com";
        v2Domain = suffix + ".v2-attrs.example.com";
    }

    @AfterAll
    static void cleanup() {
        if (sesV1 != null) {
            try {
                sesV1.deleteIdentity(DeleteIdentityRequest.builder().identity(v1Domain).build());
            } catch (Exception ignored) {}
            sesV1.close();
        }
        if (sesV2 != null) {
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(v2Domain).build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
    }

    // ───────────────────────── V1 ─────────────────────────

    @Test
    @Order(1)
    void v1SetAndGetMailFromDomain() {
        sesV1.verifyDomainIdentity(VerifyDomainIdentityRequest.builder().domain(v1Domain).build());

        sesV1.setIdentityMailFromDomain(SetIdentityMailFromDomainRequest.builder()
                .identity(v1Domain)
                .mailFromDomain("mail." + v1Domain)
                .behaviorOnMXFailure("RejectMessage")
                .build());

        GetIdentityMailFromDomainAttributesResponse response =
                sesV1.getIdentityMailFromDomainAttributes(GetIdentityMailFromDomainAttributesRequest.builder()
                        .identities(v1Domain).build());

        IdentityMailFromDomainAttributes attrs = response.mailFromDomainAttributes().get(v1Domain);
        assertThat(attrs).isNotNull();
        assertThat(attrs.mailFromDomain()).isEqualTo("mail." + v1Domain);
        assertThat(attrs.behaviorOnMXFailureAsString()).isEqualTo("RejectMessage");
    }

    @Test
    @Order(2)
    void v1SetIdentityFeedbackForwardingEnabled() {
        sesV1.setIdentityFeedbackForwardingEnabled(SetIdentityFeedbackForwardingEnabledRequest.builder()
                .identity(v1Domain)
                .forwardingEnabled(false)
                .build());
        // success = no exception
    }

    @Test
    @Order(3)
    void v1SetIdentityHeadersInNotificationsEnabled() {
        sesV1.setIdentityHeadersInNotificationsEnabled(SetIdentityHeadersInNotificationsEnabledRequest.builder()
                .identity(v1Domain)
                .notificationType("Bounce")
                .enabled(true)
                .build());
        // success = no exception
    }

    @Test
    @Order(4)
    void v1GetIdentityNotificationAttributes_reflectsForwardingAndHeaderFlags() {
        // Order(2) disabled forwarding; Order(3) enabled headers-in-Bounce.
        // The Get call should now return those values.
        GetIdentityNotificationAttributesResponse response =
                sesV1.getIdentityNotificationAttributes(GetIdentityNotificationAttributesRequest.builder()
                        .identities(v1Domain).build());
        IdentityNotificationAttributes attrs = response.notificationAttributes().get(v1Domain);
        assertThat(attrs).isNotNull();
        assertThat(attrs.forwardingEnabled()).isFalse();
        assertThat(attrs.headersInBounceNotificationsEnabled()).isTrue();
        assertThat(attrs.headersInComplaintNotificationsEnabled()).isFalse();
        assertThat(attrs.headersInDeliveryNotificationsEnabled()).isFalse();
    }

    // ───────────────────────── V2 ─────────────────────────

    @Test
    @Order(10)
    void v2PutAndGetMailFromAttributes() {
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                .emailIdentity(v2Domain).build());

        sesV2.putEmailIdentityMailFromAttributes(PutEmailIdentityMailFromAttributesRequest.builder()
                .emailIdentity(v2Domain)
                .mailFromDomain("mail." + v2Domain)
                .behaviorOnMxFailure("REJECT_MESSAGE")
                .build());

        GetEmailIdentityResponse response = sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(v2Domain).build());

        assertThat(response.mailFromAttributes()).isNotNull();
        assertThat(response.mailFromAttributes().mailFromDomain()).isEqualTo("mail." + v2Domain);
        assertThat(response.mailFromAttributes().behaviorOnMxFailureAsString())
                .isEqualTo("REJECT_MESSAGE");
        assertThat(response.mailFromAttributes().mailFromDomainStatusAsString())
                .isEqualTo("SUCCESS");
    }

    @Test
    @Order(11)
    void v2PutEmailIdentityMailFromAttributes_unknownIdentity_throwsBadRequest() {
        String missing = "sdk-missing-" + TestFixtures.uniqueName() + ".example.com";
        assertThatThrownBy(() -> sesV2.putEmailIdentityMailFromAttributes(
                PutEmailIdentityMailFromAttributesRequest.builder()
                        .emailIdentity(missing)
                        .mailFromDomain("mail." + missing)
                        .behaviorOnMxFailure("USE_DEFAULT_VALUE")
                        .build()))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(12)
    void v2GetMailFromAttributes_notConfigured() {
        String testDomain = "not-configured-" + TestFixtures.uniqueName() + ".example.com";
        try {
            sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                    .emailIdentity(testDomain).build());

            GetEmailIdentityResponse response = sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                    .emailIdentity(testDomain).build());

            assertThat(response.mailFromAttributes()).isNotNull();
            assertThat(response.mailFromAttributes().behaviorOnMxFailureAsString())
                    .isEqualTo("USE_DEFAULT_VALUE");
            assertThat(response.mailFromAttributes().mailFromDomain()).isNull();
            assertThat(response.mailFromAttributes().mailFromDomainStatus()).isNull();
        } finally {
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(testDomain).build());
            } catch (Exception ignored) {}
        }
    }
}
