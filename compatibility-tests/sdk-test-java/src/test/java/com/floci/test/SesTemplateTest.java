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
import software.amazon.awssdk.services.ses.model.BulkEmailDestination;
import software.amazon.awssdk.services.ses.model.CreateTemplateRequest;
import software.amazon.awssdk.services.ses.model.DeleteIdentityRequest;
import software.amazon.awssdk.services.ses.model.DeleteTemplateRequest;
import software.amazon.awssdk.services.ses.model.GetTemplateRequest;
import software.amazon.awssdk.services.ses.model.GetTemplateResponse;
import software.amazon.awssdk.services.ses.model.InvalidRenderingParameterException;
import software.amazon.awssdk.services.ses.model.ListTemplatesRequest;
import software.amazon.awssdk.services.ses.model.ListTemplatesResponse;
import software.amazon.awssdk.services.ses.model.MissingRenderingAttributeException;
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailRequest;
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailResponse;
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailRequest;
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailResponse;
import software.amazon.awssdk.services.ses.model.TemplateDoesNotExistException;
import software.amazon.awssdk.services.ses.model.TestRenderTemplateRequest;
import software.amazon.awssdk.services.ses.model.TestRenderTemplateResponse;
import software.amazon.awssdk.services.ses.model.UpdateTemplateRequest;
import software.amazon.awssdk.services.ses.model.VerifyEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.BulkEmailContent;
import software.amazon.awssdk.services.sesv2.model.BulkEmailEntry;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.EmailTemplateContent;
import software.amazon.awssdk.services.sesv2.model.GetEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailTemplateResponse;
import software.amazon.awssdk.services.sesv2.model.ListEmailTemplatesRequest;
import software.amazon.awssdk.services.sesv2.model.ListEmailTemplatesResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendBulkEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendBulkEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.Template;
import software.amazon.awssdk.services.sesv2.model.TestRenderEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.TestRenderEmailTemplateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SES email template compatibility tests against both the V1 (query) SES API
 * and the V2 (REST JSON) SESv2 API.
 */
@DisplayName("SES Email Templates")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTemplateTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String v1Template;
    private static String v2Template;
    private static String v1Sender;
    private static String v2Sender;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        v1Template = "sdk-v1-" + suffix;
        v2Template = "sdk-v2-" + suffix;
        v1Sender = suffix + "-v1-sender@example.com";
        v2Sender = suffix + "-v2-sender@example.com";
    }

    @AfterAll
    static void cleanup() {
        if (sesV1 != null) {
            safelyDeleteV1Template(v1Template);
            try {
                sesV1.deleteIdentity(DeleteIdentityRequest.builder()
                        .identity(v1Sender).build());
            } catch (Exception ignored) {}
            sesV1.close();
        }
        if (sesV2 != null) {
            safelyDeleteV2Template(v2Template);
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(v2Sender).build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
    }

    // ────────────────────────────── V2 (SESv2) ──────────────────────────────

    @Test
    @Order(1)
    void v2CreateAndGetTemplate() {
        sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                .templateName(v2Template)
                .templateContent(EmailTemplateContent.builder()
                        .subject("Hello {{name}}")
                        .text("Hi {{name}}!")
                        .html("<p>Hi <b>{{name}}</b>!</p>")
                        .build())
                .build());

        GetEmailTemplateResponse response = sesV2.getEmailTemplate(
                GetEmailTemplateRequest.builder().templateName(v2Template).build());
        assertThat(response.templateName()).isEqualTo(v2Template);
        assertThat(response.templateContent().subject()).isEqualTo("Hello {{name}}");
        assertThat(response.templateContent().text()).isEqualTo("Hi {{name}}!");
        assertThat(response.templateContent().html()).contains("{{name}}");
    }

    @Test
    @Order(2)
    void v2CreateDuplicateRejectedWith400() {
        assertThatThrownBy(() -> sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                .templateName(v2Template)
                .templateContent(EmailTemplateContent.builder()
                        .subject("dup")
                        .text("dup")
                        .build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(3)
    void v2GetNonExistentReturns404() {
        assertThatThrownBy(() -> sesV2.getEmailTemplate(GetEmailTemplateRequest.builder()
                .templateName("sdk-v2-missing-" + System.currentTimeMillis())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(4)
    void v2UpdateTemplate() {
        sesV2.updateEmailTemplate(builder -> builder
                .templateName(v2Template)
                .templateContent(EmailTemplateContent.builder()
                        .subject("Welcome {{name}}!")
                        .text("Hello {{name}}, from {{team}}")
                        .html("<p>Welcome {{name}}</p>")
                        .build()));

        GetEmailTemplateResponse response = sesV2.getEmailTemplate(
                GetEmailTemplateRequest.builder().templateName(v2Template).build());
        assertThat(response.templateContent().subject()).isEqualTo("Welcome {{name}}!");
        assertThat(response.templateContent().text()).contains("{{team}}");
    }

    @Test
    @Order(5)
    void v2ListTemplatesIncludesCreated() {
        ListEmailTemplatesResponse response = sesV2.listEmailTemplates(
                ListEmailTemplatesRequest.builder().build());
        assertThat(response.templatesMetadata())
                .anyMatch(meta -> v2Template.equals(meta.templateName()));
    }

    @Test
    @Order(6)
    void v2SendEmailWithTemplateSubstitutesVariables() {
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                .emailIdentity(v2Sender).build());

        SendEmailResponse response = sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(v2Sender)
                .destination(Destination.builder()
                        .toAddresses("recipient@example.com")
                        .build())
                .content(EmailContent.builder()
                        .template(Template.builder()
                                .templateName(v2Template)
                                .templateData("{\"name\":\"Alice\",\"team\":\"floci\"}")
                                .build())
                        .build())
                .build());
        assertThat(response.messageId()).isNotBlank();
    }

    @Test
    @Order(20)
    void v2SendEmailWithInlineTemplateSubstitutesVariables() {
        SendEmailResponse response = sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(v2Sender)
                .destination(Destination.builder()
                        .toAddresses("recipient@example.com")
                        .build())
                .content(EmailContent.builder()
                        .template(Template.builder()
                                .templateContent(EmailTemplateContent.builder()
                                        .subject("Inline {{name}}")
                                        .text("Hello inline {{name}}")
                                        .html("<p>Hello inline <b>{{name}}</b></p>")
                                        .build())
                                .templateData("{\"name\":\"Alice\"}")
                                .build())
                        .build())
                .build());
        assertThat(response.messageId()).isNotBlank();
    }

    @Test
    @Order(22)
    void v2SendEmailWithTemplateArnResolvesStoredTemplate() {
        String name = "sdk-v2-arn-" + TestFixtures.uniqueName();
        try {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder()
                            .subject("Hi {{name}}")
                            .text("Hello {{name}}")
                            .build())
                    .build());

            SendEmailResponse response = sesV2.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(v2Sender)
                    .destination(Destination.builder()
                            .toAddresses("recipient@example.com")
                            .build())
                    .content(EmailContent.builder()
                            .template(Template.builder()
                                    .templateArn("arn:aws:ses:us-east-1:000000000000:template/" + name)
                                    .templateData("{\"name\":\"Alice\"}")
                                    .build())
                            .build())
                    .build());
            assertThat(response.messageId()).isNotBlank();
        } finally {
            safelyDeleteV2Template(name);
        }
    }

    @Test
    @Order(21)
    void v2SendEmailWithBothNameAndInlineReturns400() {
        assertThatThrownBy(() -> sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(v2Sender)
                .destination(Destination.builder()
                        .toAddresses("recipient@example.com")
                        .build())
                .content(EmailContent.builder()
                        .template(Template.builder()
                                .templateName(v2Template)
                                .templateContent(EmailTemplateContent.builder()
                                        .subject("s")
                                        .text("t")
                                        .build())
                                .templateData("{}")
                                .build())
                        .build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(7)
    void v2SendEmailWithUnknownTemplateReturns404() {
        assertThatThrownBy(() -> sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(v2Sender)
                .destination(Destination.builder()
                        .toAddresses("recipient@example.com")
                        .build())
                .content(EmailContent.builder()
                        .template(Template.builder()
                                .templateName("sdk-v2-missing-" + System.currentTimeMillis())
                                .templateData("{}")
                                .build())
                        .build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(8)
    void v2DeleteTemplate() {
        sesV2.deleteEmailTemplate(DeleteEmailTemplateRequest.builder()
                .templateName(v2Template).build());

        assertThatThrownBy(() -> sesV2.getEmailTemplate(GetEmailTemplateRequest.builder()
                .templateName(v2Template).build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    // ────────────────────────────── V1 (SES) ──────────────────────────────

    @Test
    @Order(10)
    void v1CreateAndGetTemplate() {
        sesV1.createTemplate(CreateTemplateRequest.builder()
                .template(software.amazon.awssdk.services.ses.model.Template.builder()
                        .templateName(v1Template)
                        .subjectPart("Hello {{name}}")
                        .textPart("Hi {{name}}!")
                        .htmlPart("<p>Hi <b>{{name}}</b>!</p>")
                        .build())
                .build());

        GetTemplateResponse response = sesV1.getTemplate(GetTemplateRequest.builder()
                .templateName(v1Template).build());
        software.amazon.awssdk.services.ses.model.Template template = response.template();
        assertThat(template.templateName()).isEqualTo(v1Template);
        assertThat(template.subjectPart()).isEqualTo("Hello {{name}}");
        assertThat(template.textPart()).isEqualTo("Hi {{name}}!");
        assertThat(template.htmlPart()).contains("{{name}}");
    }

    @Test
    @Order(11)
    void v1CreateDuplicateRejected() {
        assertThatThrownBy(() -> sesV1.createTemplate(CreateTemplateRequest.builder()
                .template(software.amazon.awssdk.services.ses.model.Template.builder()
                        .templateName(v1Template)
                        .subjectPart("dup")
                        .textPart("dup")
                        .build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(12)
    void v1GetNonExistentRaises() {
        assertThatThrownBy(() -> sesV1.getTemplate(GetTemplateRequest.builder()
                .templateName("sdk-v1-missing-" + System.currentTimeMillis())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(13)
    void v1UpdateTemplate() {
        sesV1.updateTemplate(UpdateTemplateRequest.builder()
                .template(software.amazon.awssdk.services.ses.model.Template.builder()
                        .templateName(v1Template)
                        .subjectPart("Welcome {{name}}!")
                        .textPart("Hello {{name}}, from {{team}}")
                        .build())
                .build());

        GetTemplateResponse response = sesV1.getTemplate(GetTemplateRequest.builder()
                .templateName(v1Template).build());
        assertThat(response.template().subjectPart()).isEqualTo("Welcome {{name}}!");
        assertThat(response.template().textPart()).contains("{{team}}");
    }

    @Test
    @Order(14)
    void v1ListTemplatesIncludesCreated() {
        ListTemplatesResponse response = sesV1.listTemplates(ListTemplatesRequest.builder().build());
        assertThat(response.templatesMetadata())
                .anyMatch(meta -> v1Template.equals(meta.name()));
    }

    @Test
    @Order(15)
    void v1SendTemplatedEmail() {
        sesV1.verifyEmailIdentity(VerifyEmailIdentityRequest.builder()
                .emailAddress(v1Sender).build());

        SendTemplatedEmailResponse response = sesV1.sendTemplatedEmail(
                SendTemplatedEmailRequest.builder()
                        .source(v1Sender)
                        .destination(d -> d.toAddresses("recipient@example.com"))
                        .template(v1Template)
                        .templateData("{\"name\":\"Alice\",\"team\":\"floci\"}")
                        .build());
        assertThat(response.messageId()).isNotBlank();
    }

    @Test
    @Order(16)
    void v1SendTemplatedEmailUnknownTemplateRaises() {
        assertThatThrownBy(() -> sesV1.sendTemplatedEmail(SendTemplatedEmailRequest.builder()
                .source(v1Sender)
                .destination(d -> d.toAddresses("recipient@example.com"))
                .template("sdk-v1-missing-" + System.currentTimeMillis())
                .templateData("{}")
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(17)
    void v1DeleteTemplate() {
        sesV1.deleteTemplate(DeleteTemplateRequest.builder()
                .templateName(v1Template).build());

        assertThatThrownBy(() -> sesV1.getTemplate(GetTemplateRequest.builder()
                .templateName(v1Template).build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(30)
    void v1SendTemplatedEmailWithTemplateArnAndName() {
        // boto3 and AWS Java SDK v2 both require the V1 Template (name) field on
        // SendTemplatedEmail — TemplateArn is supplementary for cross-account
        // addressing on real AWS. Floci accepts both and resolves via the name.
        String name = "sdk-v1-arn-" + TestFixtures.uniqueName();
        try {
            sesV1.createTemplate(CreateTemplateRequest.builder()
                    .template(software.amazon.awssdk.services.ses.model.Template.builder()
                            .templateName(name)
                            .subjectPart("Hi {{name}}")
                            .textPart("Hello {{name}}")
                            .build())
                    .build());

            SendTemplatedEmailResponse response = sesV1.sendTemplatedEmail(
                    SendTemplatedEmailRequest.builder()
                            .source(v1Sender)
                            .destination(d -> d.toAddresses("recipient@example.com"))
                            .template(name)
                            .templateArn("arn:aws:ses:us-east-1:000000000000:template/" + name)
                            .templateData("{\"name\":\"Alice\"}")
                            .build());
            assertThat(response.messageId()).isNotBlank();
        } finally {
            safelyDeleteV1Template(name);
        }
    }

    // ───────────────────────── Bulk send (V2 / V1) ─────────────────────────

    @Test
    @Order(25)
    void v2SendBulkEmailWithStoredTemplateAndPerEntryReplacement() {
        String name = "sdk-v2-bulk-" + TestFixtures.uniqueName();
        try {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder()
                            .subject("Hello {{name}}")
                            .text("Hi {{name}}, team {{team}}!")
                            .build())
                    .build());

            SendBulkEmailResponse response = sesV2.sendBulkEmail(SendBulkEmailRequest.builder()
                    .fromEmailAddress(v2Sender)
                    .defaultContent(BulkEmailContent.builder()
                            .template(Template.builder()
                                    .templateName(name)
                                    .templateData("{\"team\":\"floci\"}")
                                    .build())
                            .build())
                    .bulkEmailEntries(
                            BulkEmailEntry.builder()
                                    .destination(Destination.builder()
                                            .toAddresses("alice@example.com")
                                            .build())
                                    .replacementEmailContent(rec -> rec
                                            .replacementTemplate(rt -> rt
                                                    .replacementTemplateData("{\"name\":\"Alice\"}")))
                                    .build(),
                            BulkEmailEntry.builder()
                                    .destination(Destination.builder()
                                            .toAddresses("bob@example.com")
                                            .build())
                                    .replacementEmailContent(rec -> rec
                                            .replacementTemplate(rt -> rt
                                                    .replacementTemplateData(
                                                            "{\"name\":\"Bob\",\"team\":\"override\"}")))
                                    .build())
                    .build());

            assertThat(response.bulkEmailEntryResults()).hasSize(2);
            assertThat(response.bulkEmailEntryResults().get(0).statusAsString()).isEqualTo("SUCCESS");
            assertThat(response.bulkEmailEntryResults().get(0).messageId()).isNotBlank();
            assertThat(response.bulkEmailEntryResults().get(1).statusAsString()).isEqualTo("SUCCESS");
            assertThat(response.bulkEmailEntryResults().get(1).messageId()).isNotBlank();
            assertThat(response.bulkEmailEntryResults().get(0).messageId())
                    .isNotEqualTo(response.bulkEmailEntryResults().get(1).messageId());
        } finally {
            safelyDeleteV2Template(name);
        }
    }

    @Test
    @Order(26)
    void v2SendBulkEmailWithUnknownTemplateReturns404() {
        assertThatThrownBy(() -> sesV2.sendBulkEmail(SendBulkEmailRequest.builder()
                .fromEmailAddress(v2Sender)
                .defaultContent(BulkEmailContent.builder()
                        .template(Template.builder()
                                .templateName("sdk-v2-bulk-missing-" + System.currentTimeMillis())
                                .build())
                        .build())
                .bulkEmailEntries(BulkEmailEntry.builder()
                        .destination(Destination.builder()
                                .toAddresses("alice@example.com")
                                .build())
                        .build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(35)
    void v1SendBulkTemplatedEmailWithPerEntryReplacement() {
        String name = "sdk-v1-bulk-" + TestFixtures.uniqueName();
        try {
            sesV1.createTemplate(CreateTemplateRequest.builder()
                    .template(software.amazon.awssdk.services.ses.model.Template.builder()
                            .templateName(name)
                            .subjectPart("Hello {{name}}")
                            .textPart("Hi {{name}}, team {{team}}!")
                            .build())
                    .build());

            SendBulkTemplatedEmailResponse response = sesV1.sendBulkTemplatedEmail(
                    SendBulkTemplatedEmailRequest.builder()
                            .source(v1Sender)
                            .template(name)
                            .defaultTemplateData("{\"team\":\"floci\"}")
                            .destinations(
                                    BulkEmailDestination.builder()
                                            .destination(d -> d.toAddresses("alice@example.com"))
                                            .replacementTemplateData("{\"name\":\"Alice\"}")
                                            .build(),
                                    BulkEmailDestination.builder()
                                            .destination(d -> d.toAddresses("bob@example.com"))
                                            .replacementTemplateData(
                                                    "{\"name\":\"Bob\",\"team\":\"override\"}")
                                            .build())
                            .build());

            assertThat(response.status()).hasSize(2);
            assertThat(response.status().get(0).statusAsString()).isEqualTo("Success");
            assertThat(response.status().get(0).messageId()).isNotBlank();
            assertThat(response.status().get(1).statusAsString()).isEqualTo("Success");
            assertThat(response.status().get(1).messageId()).isNotBlank();
            assertThat(response.status().get(0).messageId())
                    .isNotEqualTo(response.status().get(1).messageId());
        } finally {
            safelyDeleteV1Template(name);
        }
    }

    @Test
    @Order(36)
    void v1SendBulkTemplatedEmailWithUnknownTemplateRaises() {
        assertThatThrownBy(() -> sesV1.sendBulkTemplatedEmail(SendBulkTemplatedEmailRequest.builder()
                .source(v1Sender)
                .template("sdk-v1-bulk-missing-" + System.currentTimeMillis())
                .destinations(BulkEmailDestination.builder()
                        .destination(d -> d.toAddresses("alice@example.com"))
                        .build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    // ───────────────────── TestRender (V2 / V1) ─────────────────────

    @Test
    @Order(40)
    void v2TestRenderEmailTemplateSubstitutesVariables() {
        String name = "sdk-v2-render-" + TestFixtures.uniqueName();
        try {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder()
                            .subject("Hello {{name}}")
                            .text("Hi {{name}}, team {{team}}!")
                            .html("<p>Hi <b>{{name}}</b></p>")
                            .build())
                    .build());

            TestRenderEmailTemplateResponse response = sesV2.testRenderEmailTemplate(
                    TestRenderEmailTemplateRequest.builder()
                            .templateName(name)
                            .templateData("{\"name\":\"Alice\",\"team\":\"floci\"}")
                            .build());
            assertThat(response.renderedTemplate())
                    .contains("Subject: Hello Alice")
                    .contains("Hi Alice, team floci!")
                    .contains("multipart/alternative");
        } finally {
            safelyDeleteV2Template(name);
        }
    }

    @Test
    @Order(41)
    void v2TestRenderEmailTemplateUnknownTemplateReturns404() {
        assertThatThrownBy(() -> sesV2.testRenderEmailTemplate(
                TestRenderEmailTemplateRequest.builder()
                        .templateName("sdk-v2-render-missing-" + System.currentTimeMillis())
                        .templateData("{}")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(42)
    void v2TestRenderEmailTemplateMissingVariableReturns400() {
        String name = "sdk-v2-render-miss-" + TestFixtures.uniqueName();
        try {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder()
                            .subject("Hello {{name}}")
                            .text("Hi {{name}}, team {{team}}!")
                            .build())
                    .build());

            assertThatThrownBy(() -> sesV2.testRenderEmailTemplate(
                    TestRenderEmailTemplateRequest.builder()
                            .templateName(name)
                            .templateData("{\"name\":\"Alice\"}")
                            .build()))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((AwsServiceException) e).statusCode())
                    .isEqualTo(400);
        } finally {
            safelyDeleteV2Template(name);
        }
    }

    @Test
    @Order(43)
    void v2TestRenderEmailTemplateInvalidJsonReturns400() {
        String name = "sdk-v2-render-bad-" + TestFixtures.uniqueName();
        try {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder()
                            .subject("Hello")
                            .text("Hi")
                            .build())
                    .build());

            assertThatThrownBy(() -> sesV2.testRenderEmailTemplate(
                    TestRenderEmailTemplateRequest.builder()
                            .templateName(name)
                            .templateData("{not json")
                            .build()))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((AwsServiceException) e).statusCode())
                    .isEqualTo(400);
        } finally {
            safelyDeleteV2Template(name);
        }
    }

    @Test
    @Order(45)
    void v1TestRenderTemplateSubstitutesVariables() {
        String name = "sdk-v1-render-" + TestFixtures.uniqueName();
        try {
            sesV1.createTemplate(CreateTemplateRequest.builder()
                    .template(software.amazon.awssdk.services.ses.model.Template.builder()
                            .templateName(name)
                            .subjectPart("Hello {{name}}")
                            .textPart("Hi {{name}}, team {{team}}!")
                            .htmlPart("<p>Hi <b>{{name}}</b></p>")
                            .build())
                    .build());

            TestRenderTemplateResponse response = sesV1.testRenderTemplate(
                    TestRenderTemplateRequest.builder()
                            .templateName(name)
                            .templateData("{\"name\":\"Alice\",\"team\":\"floci\"}")
                            .build());
            assertThat(response.renderedTemplate())
                    .contains("Subject: Hello Alice")
                    .contains("Hi Alice, team floci!")
                    .contains("multipart/alternative");
        } finally {
            safelyDeleteV1Template(name);
        }
    }

    @Test
    @Order(46)
    void v1TestRenderTemplateUnknownTemplateRaises() {
        assertThatThrownBy(() -> sesV1.testRenderTemplate(
                TestRenderTemplateRequest.builder()
                        .templateName("sdk-v1-render-missing-" + System.currentTimeMillis())
                        .templateData("{}")
                        .build()))
                .isInstanceOf(TemplateDoesNotExistException.class);
    }

    @Test
    @Order(47)
    void v1TestRenderTemplateMissingVariableRaises() {
        String name = "sdk-v1-render-miss-" + TestFixtures.uniqueName();
        try {
            sesV1.createTemplate(CreateTemplateRequest.builder()
                    .template(software.amazon.awssdk.services.ses.model.Template.builder()
                            .templateName(name)
                            .subjectPart("Hello {{name}}")
                            .textPart("Hi {{name}}, team {{team}}!")
                            .build())
                    .build());

            assertThatThrownBy(() -> sesV1.testRenderTemplate(
                    TestRenderTemplateRequest.builder()
                            .templateName(name)
                            .templateData("{\"name\":\"Alice\"}")
                            .build()))
                    .isInstanceOf(MissingRenderingAttributeException.class);
        } finally {
            safelyDeleteV1Template(name);
        }
    }

    @Test
    @Order(48)
    void v1TestRenderTemplateInvalidJsonRaises() {
        String name = "sdk-v1-render-bad-" + TestFixtures.uniqueName();
        try {
            sesV1.createTemplate(CreateTemplateRequest.builder()
                    .template(software.amazon.awssdk.services.ses.model.Template.builder()
                            .templateName(name)
                            .subjectPart("Hello")
                            .textPart("Hi")
                            .build())
                    .build());

            assertThatThrownBy(() -> sesV1.testRenderTemplate(
                    TestRenderTemplateRequest.builder()
                            .templateName(name)
                            .templateData("{not json")
                            .build()))
                    .isInstanceOf(InvalidRenderingParameterException.class);
        } finally {
            safelyDeleteV1Template(name);
        }
    }

    // ───── Strict missing-attribute on Send paths (regression coverage) ─────

    @Test
    @Order(50)
    void v2SendEmailWithTemplateMissingVariableReturns400() {
        String name = "sdk-v2-send-miss-" + TestFixtures.uniqueName();
        try {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder()
                            .subject("Hello {{name}}")
                            .text("Hi {{name}}, team {{team}}!")
                            .build())
                    .build());

            assertThatThrownBy(() -> sesV2.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(v2Sender)
                    .destination(Destination.builder()
                            .toAddresses("recipient@example.com")
                            .build())
                    .content(EmailContent.builder()
                            .template(Template.builder()
                                    .templateName(name)
                                    .templateData("{\"name\":\"Alice\"}")
                                    .build())
                            .build())
                    .build()))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((AwsServiceException) e).statusCode())
                    .isEqualTo(400);
        } finally {
            safelyDeleteV2Template(name);
        }
    }

    @Test
    @Order(51)
    void v1SendTemplatedEmailMissingVariableRaises() {
        String name = "sdk-v1-send-miss-" + TestFixtures.uniqueName();
        try {
            sesV1.createTemplate(CreateTemplateRequest.builder()
                    .template(software.amazon.awssdk.services.ses.model.Template.builder()
                            .templateName(name)
                            .subjectPart("Hello {{name}}")
                            .textPart("Hi {{name}}, team {{team}}!")
                            .build())
                    .build());

            assertThatThrownBy(() -> sesV1.sendTemplatedEmail(SendTemplatedEmailRequest.builder()
                    .source(v1Sender)
                    .destination(d -> d.toAddresses("recipient@example.com"))
                    .template(name)
                    .templateData("{\"name\":\"Alice\"}")
                    .build()))
                    .isInstanceOf(MissingRenderingAttributeException.class);
        } finally {
            safelyDeleteV1Template(name);
        }
    }

    // ─────────────────────────────── Helpers ───────────────────────────────

    private static void safelyDeleteV1Template(String name) {
        try {
            sesV1.deleteTemplate(DeleteTemplateRequest.builder()
                    .templateName(name).build());
        } catch (Exception ignored) {}
    }

    private static void safelyDeleteV2Template(String name) {
        try {
            sesV2.deleteEmailTemplate(DeleteEmailTemplateRequest.builder()
                    .templateName(name).build());
        } catch (Exception ignored) {}
    }
}
