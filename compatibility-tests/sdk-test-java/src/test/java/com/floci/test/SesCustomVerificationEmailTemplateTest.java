package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.CreateCustomVerificationEmailTemplateRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetCustomVerificationEmailTemplateResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES custom-verification-email-template APIs across both protocols
 * against a live Floci instance: v2 Create/Get/List/Update/Delete plus the duplicate/not-found
 * errors, and the shared store (a v1-written template is visible via the v2 Get).
 */
@DisplayName("SES custom verification email templates (v1 + v2)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesCustomVerificationEmailTemplateTest {

    private static final String FROM = "cvet-sdk-sender@floci-cvet.test";
    private static final String NAME = "cvet-sdk-a";

    private static SesClient sesV1;
    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(FROM).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            for (String n : new String[] {NAME, "cvet-sdk-shared"}) {
                try {
                    sesV2.deleteCustomVerificationEmailTemplate(b -> b.templateName(n));
                } catch (Exception ignored) {
                    // Best-effort cleanup: the template may already be gone.
                }
            }
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(FROM).build());
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @Order(1)
    void v2_createGetListUpdate() {
        sesV2.createCustomVerificationEmailTemplate(b -> b
                .templateName(NAME).fromEmailAddress(FROM).templateSubject("Verify")
                .templateContent("<html><body>verify</body></html>")
                .successRedirectionURL("https://example.com/ok")
                .failureRedirectionURL("https://example.com/fail"));

        GetCustomVerificationEmailTemplateResponse got =
                sesV2.getCustomVerificationEmailTemplate(b -> b.templateName(NAME));
        assertThat(got.templateName()).isEqualTo(NAME);
        assertThat(got.fromEmailAddress()).isEqualTo(FROM);
        assertThat(got.templateContent()).contains("verify");

        assertThat(sesV2.listCustomVerificationEmailTemplates(b -> {})
                .customVerificationEmailTemplates())
                .anyMatch(t -> NAME.equals(t.templateName()));

        sesV2.updateCustomVerificationEmailTemplate(b -> b
                .templateName(NAME).fromEmailAddress(FROM).templateSubject("Updated")
                .templateContent("<html><body>verify</body></html>")
                .successRedirectionURL("https://example.com/ok2")
                .failureRedirectionURL("https://example.com/fail"));
        assertThat(sesV2.getCustomVerificationEmailTemplate(b -> b.templateName(NAME)).templateSubject())
                .isEqualTo("Updated");
    }

    @Test
    @Order(2)
    void v2_duplicateAndMissingErrors() {
        assertThatThrownBy(() -> sesV2.createCustomVerificationEmailTemplate(b -> b
                .templateName(NAME).fromEmailAddress(FROM).templateSubject("x")
                .templateContent("c").successRedirectionURL("https://e.com/o")
                .failureRedirectionURL("https://e.com/f")))
                .isInstanceOf(AlreadyExistsException.class);

        assertThatThrownBy(() -> sesV2.getCustomVerificationEmailTemplate(b -> b.templateName("nope-sdk")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(3)
    void v1WrittenTemplate_isVisibleViaV2() {
        sesV1.createCustomVerificationEmailTemplate(CreateCustomVerificationEmailTemplateRequest.builder()
                .templateName("cvet-sdk-shared").fromEmailAddress(FROM).templateSubject("Shared")
                .templateContent("<html><body>verify</body></html>")
                .successRedirectionURL("https://example.com/ok")
                .failureRedirectionURL("https://example.com/fail").build());

        assertThat(sesV2.getCustomVerificationEmailTemplate(b -> b.templateName("cvet-sdk-shared"))
                .templateSubject()).isEqualTo("Shared");
    }
}
