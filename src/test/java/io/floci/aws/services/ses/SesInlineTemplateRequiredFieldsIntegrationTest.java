package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.floci.aws.services.ses.SesV2Requests.DESTINATION;
import static io.floci.aws.services.ses.SesV2Requests.ENTRY;
import static io.floci.aws.services.ses.SesV2Requests.post;
import static org.hamcrest.Matchers.equalTo;

/**
 * A v2 SendEmail or SendBulkEmail with inline TemplateContent needs a subject and a text or html
 * part. AWS answers every incomplete shape with the same message, ahead of the sender, the
 * destinations, the template data, the tags, the list management options, the feedback address and
 * the configuration set and tenant lookups (probe-confirmed).
 */
@QuarkusTest
class SesInlineTemplateRequiredFieldsIntegrationTest {

    private static final String UNVERIFIED = "sender@unverified-inline-template.floci.test";
    private static final String SUBJECT_ONLY = "{\"Subject\": \"s\"}";
    private static final String MESSAGE = "Template is missing required fields.";

    private static String template(String templateContent) {
        return "{\"Template\": {\"TemplateContent\": " + templateContent + ", \"TemplateData\": \"{}\"}}";
    }

    private static void assertMissingRequiredFields(ValidatableResponse response) {
        response
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(MESSAGE));
    }

    static Stream<Arguments> incompleteContents() {
        return Stream.of(
                Arguments.of("empty", "{}"),
                Arguments.of("subject only", SUBJECT_ONLY),
                Arguments.of("text only", "{\"Text\": \"t\"}"),
                Arguments.of("html only", "{\"Html\": \"<p>h</p>\"}"),
                Arguments.of("blank subject beside a part", "{\"Subject\": \" \", \"Text\": \"t\"}"),
                Arguments.of("subject beside blank parts", "{\"Subject\": \"s\", \"Text\": \" \", \"Html\": \"\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteContents")
    void sendEmail_incompleteInlineTemplate_isMissingRequiredFields(String label, String templateContent) {
        assertMissingRequiredFields(post("/v2/email/outbound-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED
                + "\", " + DESTINATION + ", \"Content\": " + template(templateContent) + "}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteContents")
    void sendBulkEmail_incompleteInlineTemplate_isMissingRequiredFields(String label, String templateContent) {
        assertMissingRequiredFields(post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED
                + "\", \"DefaultContent\": " + template(templateContent) + ", \"BulkEmailEntries\": [" + ENTRY
                + "]}"));
    }

    // The check sees the content before the sender is verified: a complete inline template from
    // the same unverified sender gets as far as the verification.
    @ParameterizedTest(name = "{0}")
    @MethodSource("completeContents")
    void completeInlineTemplate_reachesSenderVerification(String label, String templateContent) {
        post("/v2/email/outbound-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\", " + DESTINATION
                + ", \"Content\": " + template(templateContent) + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
        post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED
                + "\", \"DefaultContent\": " + template(templateContent) + ", \"BulkEmailEntries\": [" + ENTRY + "]}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
    }

    static Stream<Arguments> completeContents() {
        return Stream.of(
                Arguments.of("subject and text", "{\"Subject\": \"s\", \"Text\": \"t\"}"),
                Arguments.of("subject, empty text and html", "{\"Subject\": \"s\", \"Text\": \"\", \"Html\": \"<p>h</p>\"}"));
    }

    static Stream<Arguments> sendEmailsWithAnotherError() {
        String content = "\"Content\": " + template(SUBJECT_ONLY);
        String from = "\"FromEmailAddress\": \"" + UNVERIFIED + "\"";
        return Stream.of(
                Arguments.of("no sender", "{" + DESTINATION + ", " + content + "}"),
                Arguments.of("malformed sender",
                        "{\"FromEmailAddress\": \"not an address\", " + DESTINATION + ", " + content + "}"),
                Arguments.of("no destination", "{" + from + ", " + content + "}"),
                Arguments.of("malformed recipient",
                        "{" + from + ", \"Destination\": {\"ToAddresses\": [\"not an address\"]}, " + content + "}"),
                Arguments.of("unknown configuration set", "{" + from + ", " + DESTINATION
                        + ", \"ConfigurationSetName\": \"inline-template-ghost\", " + content + "}"),
                Arguments.of("unknown tenant", "{" + from + ", " + DESTINATION
                        + ", \"TenantName\": \"inline-template-ghost\", " + content + "}"),
                Arguments.of("invalid tag name", "{" + from + ", " + DESTINATION
                        + ", \"EmailTags\": [{\"Name\": \"bad name!\", \"Value\": \"v\"}], " + content + "}"),
                Arguments.of("duplicate tag name", "{" + from + ", " + DESTINATION
                        + ", \"EmailTags\": [{\"Name\": \"dup\", \"Value\": \"a\"}, "
                        + "{\"Name\": \"dup\", \"Value\": \"b\"}], " + content + "}"),
                Arguments.of("unknown contact list", "{" + from + ", " + DESTINATION
                        + ", \"ListManagementOptions\": {\"ContactListName\": \"inline-template-ghost\"}, "
                        + content + "}"),
                Arguments.of("malformed feedback address", "{" + from + ", " + DESTINATION
                        + ", \"FeedbackForwardingEmailAddress\": \"not an address\", " + content + "}"),
                Arguments.of("template data that is not JSON", "{" + from + ", " + DESTINATION
                        + ", \"Content\": {\"Template\": {\"TemplateContent\": " + SUBJECT_ONLY
                        + ", \"TemplateData\": \"not json\"}}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sendEmailsWithAnotherError")
    void sendEmail_incompleteInlineTemplate_winsOverLaterChecks(String label, String body) {
        assertMissingRequiredFields(post("/v2/email/outbound-emails", body));
    }

    static Stream<Arguments> bulkSendsWithAnotherError() {
        String content = "\"DefaultContent\": " + template(SUBJECT_ONLY);
        String from = "\"FromEmailAddress\": \"" + UNVERIFIED + "\"";
        String entries = "\"BulkEmailEntries\": [" + ENTRY + "]";
        return Stream.of(
                Arguments.of("no sender", "{" + content + ", " + entries + "}"),
                Arguments.of("malformed sender",
                        "{\"FromEmailAddress\": \"not an address\", " + content + ", " + entries + "}"),
                Arguments.of("no entries", "{" + from + ", " + content + ", \"BulkEmailEntries\": []}"),
                Arguments.of("malformed entry recipient", "{" + from + ", " + content
                        + ", \"BulkEmailEntries\": [{\"Destination\": {\"ToAddresses\": [\"not an address\"]}}]}"),
                Arguments.of("unknown configuration set", "{" + from + ", " + content
                        + ", \"ConfigurationSetName\": \"inline-template-ghost\", " + entries + "}"),
                Arguments.of("unknown tenant", "{" + from + ", " + content
                        + ", \"TenantName\": \"inline-template-ghost\", " + entries + "}"),
                Arguments.of("malformed feedback address", "{" + from + ", " + content
                        + ", \"FeedbackForwardingEmailAddress\": \"not an address\", " + entries + "}"),
                Arguments.of("stored template named beside the content", "{" + from
                        + ", \"DefaultContent\": {\"Template\": {\"TemplateName\": \"inline-template-ghost\", "
                        + "\"TemplateContent\": " + SUBJECT_ONLY + ", \"TemplateData\": \"{}\"}}, " + entries + "}"),
                Arguments.of("template data that is not JSON", "{" + from
                        + ", \"DefaultContent\": {\"Template\": {\"TemplateContent\": " + SUBJECT_ONLY
                        + ", \"TemplateData\": \"not json\"}}, " + entries + "}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bulkSendsWithAnotherError")
    void sendBulkEmail_incompleteInlineTemplate_winsOverLaterChecks(String label, String body) {
        assertMissingRequiredFields(post("/v2/email/outbound-bulk-emails", body));
    }
}
