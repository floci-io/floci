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
import software.amazon.awssdk.services.ses.model.GetIdentityDkimAttributesRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityMailFromDomainAttributesRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityMailFromDomainAttributesResponse;
import software.amazon.awssdk.services.ses.model.GetIdentityNotificationAttributesRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityNotificationAttributesResponse;
import software.amazon.awssdk.services.ses.model.IdentityDkimAttributes;
import software.amazon.awssdk.services.ses.model.IdentityMailFromDomainAttributes;
import software.amazon.awssdk.services.ses.model.IdentityNotificationAttributes;
import software.amazon.awssdk.services.ses.model.SetIdentityDkimEnabledRequest;
import software.amazon.awssdk.services.ses.model.SetIdentityFeedbackForwardingEnabledRequest;
import software.amazon.awssdk.services.ses.model.SetIdentityHeadersInNotificationsEnabledRequest;
import software.amazon.awssdk.services.ses.model.SetIdentityMailFromDomainRequest;
import software.amazon.awssdk.services.ses.model.VerifyDomainDkimRequest;
import software.amazon.awssdk.services.ses.model.VerifyDomainIdentityRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DkimAttributes;
import software.amazon.awssdk.services.sesv2.model.DkimSigningAttributes;
import software.amazon.awssdk.services.sesv2.model.DkimStatus;
import software.amazon.awssdk.services.sesv2.model.DkimSigningAttributesOrigin;
import software.amazon.awssdk.services.sesv2.model.DkimSigningKeyLength;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.IdentityInfo;
import software.amazon.awssdk.services.sesv2.model.ListEmailIdentitiesRequest;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutEmailIdentityDkimSigningAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.PutEmailIdentityDkimSigningAttributesResponse;
import software.amazon.awssdk.services.sesv2.model.PutEmailIdentityMailFromAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.VerificationStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES Identity Attributes (MAIL FROM, DKIM, headers)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityAttributesTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String v1Domain;
    private static String v2Domain;
    // DKIM tests use their own identities so their token regeneration / status resets don't interfere
    // with the ordered MAIL FROM / notification tests above.
    private static String dkimDomain;
    // A separate domain that is DKIM-verified (Success) via Route53, plus an email under it, to check
    // that an email inherits its parent domain's *verified* DKIM.
    private static String verifiedDkimDomain;
    private static String verifiedDkimEmail;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        v1Domain = suffix + ".v1-attrs.example.com";
        v2Domain = suffix + ".v2-attrs.example.com";
        dkimDomain = suffix + ".dkim-attrs.example.com";
        verifiedDkimDomain = suffix + ".dkim-verified.example.com";
        verifiedDkimEmail = "user@" + verifiedDkimDomain;
    }

    @AfterAll
    static void cleanup() {
        if (sesV1 != null) {
            try {
                sesV1.deleteIdentity(DeleteIdentityRequest.builder().identity(v1Domain).build());
            } catch (Exception ignored) {}
            try {
                sesV1.deleteIdentity(DeleteIdentityRequest.builder().identity(dkimDomain).build());
            } catch (Exception ignored) {}
            sesV1.close();
        }
        if (sesV2 != null) {
            for (String id : new String[] {v2Domain, verifiedDkimEmail, verifiedDkimDomain}) {
                try {
                    sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(id).build());
                } catch (Exception ignored) {}
            }
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
    void listEmailIdentities_populatesVerificationStatus() {
        String email = TestFixtures.uniqueName("lei") + "@example.com";
        String domain = TestFixtures.uniqueName("lei") + ".example.com";
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(email).build());
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(domain).build());
        try {
            List<IdentityInfo> identities = sesV2.listEmailIdentities(
                    ListEmailIdentitiesRequest.builder().build()).emailIdentities();
            IdentityInfo emailIdentity = identities.stream()
                    .filter(i -> email.equals(i.identityName())).findFirst().orElseThrow();
            IdentityInfo domainIdentity = identities.stream()
                    .filter(i -> domain.equals(i.identityName())).findFirst().orElseThrow();
            // Regression: VerificationStatus used to be null in the list response.
            assertThat(emailIdentity.verificationStatus()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(domainIdentity.verificationStatus()).isEqualTo(VerificationStatus.PENDING);
            // SendingEnabled tracks verification status on AWS: SUCCESS -> true, PENDING -> false.
            assertThat(emailIdentity.sendingEnabled()).isTrue();
            assertThat(domainIdentity.sendingEnabled()).isFalse();
        } finally {
            sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(email).build());
            sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(domain).build());
        }
    }

    // ───────────────────────── DKIM ─────────────────────────

    @Test
    @Order(20)
    void verifyDomainDkim_returnsStableTokens() {
        sesV1.verifyDomainIdentity(VerifyDomainIdentityRequest.builder().domain(dkimDomain).build());

        List<String> first = sesV1.verifyDomainDkim(
                VerifyDomainDkimRequest.builder().domain(dkimDomain).build()).dkimTokens();
        assertThat(first).hasSize(3);

        List<String> second = sesV1.verifyDomainDkim(
                VerifyDomainDkimRequest.builder().domain(dkimDomain).build()).dkimTokens();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @Order(21)
    void setIdentityDkimEnabled_togglesFlag() {
        sesV1.setIdentityDkimEnabled(SetIdentityDkimEnabledRequest.builder()
                .identity(dkimDomain).dkimEnabled(false).build());

        IdentityDkimAttributes attrs = sesV1.getIdentityDkimAttributes(
                GetIdentityDkimAttributesRequest.builder().identities(dkimDomain).build())
                .dkimAttributes().get(dkimDomain);
        assertThat(attrs.dkimEnabled()).isFalse();

        sesV1.setIdentityDkimEnabled(SetIdentityDkimEnabledRequest.builder()
                .identity(dkimDomain).dkimEnabled(true).build());
    }

    @Test
    @Order(22)
    void setIdentityDkimEnabled_unknownIdentity_throws() {
        assertThatThrownBy(() -> sesV1.setIdentityDkimEnabled(SetIdentityDkimEnabledRequest.builder()
                .identity("unknown." + dkimDomain).dkimEnabled(true).build()))
                .isInstanceOf(AwsServiceException.class);
    }

    @Test
    @Order(23)
    void putEmailIdentityDkimSigningAttributes_regeneratesTokensOnKeyLengthChange() {
        List<String> before = sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(dkimDomain).build()).dkimAttributes().tokens();

        PutEmailIdentityDkimSigningAttributesResponse resp = sesV2.putEmailIdentityDkimSigningAttributes(
                PutEmailIdentityDkimSigningAttributesRequest.builder()
                        .emailIdentity(dkimDomain)
                        .signingAttributesOrigin(DkimSigningAttributesOrigin.AWS_SES)
                        .signingAttributes(DkimSigningAttributes.builder()
                                .nextSigningKeyLength(DkimSigningKeyLength.RSA_1024_BIT).build())
                        .build());
        assertThat(resp.dkimTokens()).hasSize(3);
        assertThat(resp.dkimTokens()).isNotEqualTo(before);
    }

    @Test
    @Order(24)
    void putEmailIdentityDkimSigningAttributes_unknownIdentity_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.putEmailIdentityDkimSigningAttributes(
                PutEmailIdentityDkimSigningAttributesRequest.builder()
                        .emailIdentity("ghost." + dkimDomain)
                        .signingAttributesOrigin(DkimSigningAttributesOrigin.AWS_SES)
                        .build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(25)
    void emailIdentity_inheritsVerifiedParentDomainDkim() {
        // Bring the parent domain to DKIM Success via Route53 so the email inherits SUCCESS (not Pending).
        TestFixtures.verifySesDomainIdentityViaRoute53(sesV2, verifiedDkimDomain);
        DkimAttributes domainDkim = sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(verifiedDkimDomain).build()).dkimAttributes();
        assertThat(domainDkim.status()).isEqualTo(DkimStatus.SUCCESS);

        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                .emailIdentity(verifiedDkimEmail).build());
        DkimAttributes emailDkim = sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(verifiedDkimEmail).build()).dkimAttributes();

        // The email carries no DKIM of its own; it mirrors the verified domain's status and tokens.
        assertThat(emailDkim.signingEnabled()).isTrue();
        assertThat(emailDkim.status()).isEqualTo(DkimStatus.SUCCESS);
        assertThat(emailDkim.tokens()).isEqualTo(domainDkim.tokens());
    }
}
