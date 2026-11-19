package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ses.model.BulkEmailEntry;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.EmailTemplate;
import io.floci.aws.services.ses.model.SendBulkEmailRequest;
import io.floci.aws.services.ses.model.SendEmailRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The shapes of an inline template a send accepts: a subject and a text or html part, each with a
 * non-whitespace character (probe-confirmed on v2 SendEmail and SendBulkEmail).
 */
class SesServiceInlineTemplateTest {

    private static final String REGION = "us-east-1";
    private static final String SENDER = "from@example.com";
    private static final String TO = "to@example.com";
    private static final EmailContent.InlineTemplate SUBJECT_ONLY =
            new EmailContent.InlineTemplate("s", null, null, null, List.of());

    static Stream<Arguments> incompleteTemplates() {
        return Stream.of(
                Arguments.of("nothing", null, null, null),
                Arguments.of("all empty", "", "", ""),
                Arguments.of("subject only", "s", null, null),
                Arguments.of("text only", null, "t", null),
                Arguments.of("html only", null, null, "<p>h</p>"),
                Arguments.of("text and html", null, "t", "<p>h</p>"),
                Arguments.of("empty subject", "", "t", null),
                Arguments.of("space subject", " ", "t", null),
                Arguments.of("ideographic space subject", "　", null, "<p>h</p>"),
                Arguments.of("empty text", "s", "", null),
                Arguments.of("space text", "s", " ", null),
                Arguments.of("newline html", "s", null, "\n"),
                Arguments.of("space text and empty html", "s", " ", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteTemplates")
    void incompleteTemplate_isMissingRequiredFields(String label, String subject, String text, String html) {
        AwsException e = assertThrows(AwsException.class,
                () -> SesService.requireInlineTemplateContent(subject, text, html));

        assertEquals("InvalidTemplate", e.getErrorCode());
        assertEquals("Template is missing required fields.", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }

    static Stream<Arguments> completeTemplates() {
        return Stream.of(
                Arguments.of("subject and text", "s", "t", null),
                Arguments.of("subject and html", "s", null, "<p>h</p>"),
                Arguments.of("subject and both parts", "s", "t", "<p>h</p>"),
                Arguments.of("empty text beside html", "s", "", "<p>h</p>"),
                Arguments.of("blank html beside text", "s", "t", " "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("completeTemplates")
    void completeTemplate_isAccepted(String label, String subject, String text, String html) {
        assertDoesNotThrow(() -> SesService.requireInlineTemplateContent(subject, text, html));
    }

    @Test
    void sendEmail_incompleteInlineTemplate_isReportedBeforeAnUnknownTenant() {
        SesService service = SesServiceTestBuilder.create().build();

        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(SendEmailRequest.builder()
                .source(SENDER)
                .toAddresses(List.of(TO))
                .tenantName("ghost-tenant")
                .region(REGION)
                .content(SUBJECT_ONLY)
                .build()));

        assertEquals("Template is missing required fields.", e.getMessage());
    }

    @Test
    void sendBulkEmail_incompleteInlineTemplate_isReportedBeforeAMissingSource() {
        SesService service = SesServiceTestBuilder.create().build();

        AwsException e = assertThrows(AwsException.class, () -> service.sendBulkEmail(SendBulkEmailRequest.builder()
                .defaultContent(SUBJECT_ONLY)
                .entries(List.of(new BulkEmailEntry(List.of(TO), null, null, null, null, null)))
                .region(REGION)
                .build()));

        assertEquals("Template is missing required fields.", e.getMessage());
    }

    // The rule belongs to inline content: a stored template is checked when it is stored, and a
    // send does not hold one that predates a stricter rule to the inline message.
    @Test
    void sendEmail_storedTemplateWithoutAPart_isNotHeldToTheInlineRule() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        SesService service = builder.build();
        builder.identityService().verifyEmailIdentity(SENDER, REGION);
        builder.templateStore().put("template::" + REGION + "::subject-only",
                new EmailTemplate("subject-only", "s", null, null));

        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source(SENDER)
                .toAddresses(List.of(TO))
                .region(REGION)
                .content(new EmailContent.Template("subject-only", null, List.of()))
                .build());

        assertNotNull(messageId);
    }
}
