package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.EmailAddressInsightsConfidenceVerdict;
import software.amazon.awssdk.services.sesv2.model.EmailAddressInsightsMailboxEvaluations;
import software.amazon.awssdk.services.sesv2.model.GetEmailAddressInsightsRequest;
import software.amazon.awssdk.services.sesv2.model.MailboxValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for SES v2 GetEmailAddressInsights: the response deserializes into the
 * SDK's verdict model, and an empty address is a BadRequestException.
 */
@DisplayName("SES v2 Email Address Insights")
class SesEmailAddressInsightsTest {

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            sesV2.close();
        }
    }

    @Test
    @DisplayName("GetEmailAddressInsights returns every verdict")
    void returnsEveryVerdict() {
        MailboxValidation validation = sesV2.getEmailAddressInsights(GetEmailAddressInsightsRequest.builder()
                .emailAddress("support@simulator.amazonses.com").build()).mailboxValidation();
        EmailAddressInsightsMailboxEvaluations evaluations = validation.evaluations();

        assertThat(validation.isValid().confidenceVerdict()).isEqualTo(EmailAddressInsightsConfidenceVerdict.HIGH);
        assertThat(evaluations.hasValidSyntax().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.HIGH);
        assertThat(evaluations.hasValidDnsRecords().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.HIGH);
        assertThat(evaluations.mailboxExists().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.MEDIUM);
        assertThat(evaluations.isRoleAddress().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.HIGH);
        assertThat(evaluations.isDisposable().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.LOW);
        assertThat(evaluations.isRandomInput().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.LOW);
    }

    @Test
    @DisplayName("GetEmailAddressInsights answers a malformed address with LOW verdicts")
    void malformedAddressIsLow() {
        MailboxValidation validation = sesV2.getEmailAddressInsights(GetEmailAddressInsightsRequest.builder()
                .emailAddress("no-at-sign").build()).mailboxValidation();

        assertThat(validation.isValid().confidenceVerdict()).isEqualTo(EmailAddressInsightsConfidenceVerdict.LOW);
        assertThat(validation.evaluations().hasValidSyntax().confidenceVerdict())
                .isEqualTo(EmailAddressInsightsConfidenceVerdict.LOW);
    }

    @Test
    @DisplayName("GetEmailAddressInsights rejects an empty address")
    void emptyAddressIsRejected() {
        assertThatThrownBy(() -> sesV2.getEmailAddressInsights(GetEmailAddressInsightsRequest.builder()
                .emailAddress("").build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Email address must not be null nor empty");
    }
}
