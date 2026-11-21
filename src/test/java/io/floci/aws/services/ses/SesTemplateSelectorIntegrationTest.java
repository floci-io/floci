package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * How a v2 SendEmail or SendBulkEmail answers a template that names its source more than once:
 * a stored template beside TemplateContent, or a TemplateName beside a TemplateArn. The messages
 * and their place among the other checks are probe-confirmed and differ by operation.
 */
@QuarkusTest
class SesTemplateSelectorIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String SEND = "/v2/email/outbound-emails";
    private static final String BULK = "/v2/email/outbound-bulk-emails";
    private static final String UNVERIFIED = "sender@unverified-template-selector.floci.test";
    private static final String FROM = "\"FromEmailAddress\": \"" + UNVERIFIED + "\"";
    private static final String DESTINATION =
            "\"Destination\": {\"ToAddresses\": [\"success@simulator.amazonses.com\"]}";
    private static final String ENTRY = "{" + DESTINATION + "}";
    private static final String ENTRIES = "\"BulkEmailEntries\": [" + ENTRY + "]";
    private static final String BAD_DESTINATION = "\"Destination\": {\"ToAddresses\": [\"not an address\"]}";
    private static final String NAME = "\"TemplateName\": \"selector-ghost\"";
    private static final String ARN =
            "\"TemplateArn\": \"arn:aws:ses:us-east-1:000000000000:template/selector-ghost\"";
    private static final String OTHER_ARN_VALUE = "arn:aws:ses:us-east-1:000000000000:template/selector-other";
    private static final String CONTENT = "\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"t\"}";
    private static final String DATA = "\"TemplateData\": \"{}\"";
    private static final String NAME_BESIDE_CONTENT = template(NAME, CONTENT, DATA);
    private static final String SEND_COMBINATION =
            "TemplateName and TemplateArn cannot be provided when TemplateContent is provided.";
    private static final String BULK_COMBINATION =
            "TemplateName or TemplateArn cannot be provided with EmailTemplateContent";
    private static final String BULK_COUNT =
            "You must specify at least 1 destination, and no more than 50 destinations";

    private static ValidatableResponse post(String path, String body) {
        return given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post(path)
        .then();
    }

    private static String template(String... members) {
        return "{\"Template\": {" + String.join(", ", members) + "}}";
    }

    private static void assertBadRequest(ValidatableResponse response, String message) {
        response
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    static Stream<Arguments> sendEmailsWithAnotherError() {
        String content = "\"Content\": " + NAME_BESIDE_CONTENT;
        return Stream.of(
                Arguments.of("no sender", "{" + DESTINATION + ", " + content + "}"),
                Arguments.of("malformed sender",
                        "{\"FromEmailAddress\": \"not an address\", " + DESTINATION + ", " + content + "}"),
                Arguments.of("no destination", "{" + FROM + ", " + content + "}"),
                Arguments.of("malformed recipient", "{" + FROM + ", " + BAD_DESTINATION + ", " + content + "}"),
                Arguments.of("unknown configuration set", "{" + FROM + ", " + DESTINATION
                        + ", \"ConfigurationSetName\": \"selector-ghost\", " + content + "}"),
                Arguments.of("unknown tenant", "{" + FROM + ", " + DESTINATION
                        + ", \"TenantName\": \"selector-ghost\", " + content + "}"),
                Arguments.of("template data that is not JSON", "{" + FROM + ", " + DESTINATION
                        + ", \"Content\": " + template(NAME, CONTENT, "\"TemplateData\": \"not json\"") + "}"),
                Arguments.of("invalid tag name", "{" + FROM + ", " + DESTINATION
                        + ", \"EmailTags\": [{\"Name\": \"bad name!\", \"Value\": \"v\"}], " + content + "}"),
                Arguments.of("unknown contact list", "{" + FROM + ", " + DESTINATION
                        + ", \"ListManagementOptions\": {\"ContactListName\": \"selector-ghost\"}, " + content + "}"),
                Arguments.of("malformed feedback address", "{" + FROM + ", " + DESTINATION
                        + ", \"FeedbackForwardingEmailAddress\": \"not an address\", " + content + "}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sendEmailsWithAnotherError")
    void sendEmail_storedTemplateBesideContent_winsOverEveryOtherCheck(String label, String body) {
        assertBadRequest(post(SEND, body), SEND_COMBINATION);
    }

    static Stream<Arguments> storedTemplateSelectors() {
        return Stream.of(Arguments.of("name", NAME), Arguments.of("arn", ARN));
    }

    // SendEmail reports the combination even when the inline content is incomplete.
    @ParameterizedTest(name = "{0}")
    @MethodSource("storedTemplateSelectors")
    void sendEmail_storedTemplateBesideContent_isRejectedWhateverTheContent(String label, String selector) {
        for (String content : new String[] {CONTENT, "\"TemplateContent\": {}",
            "\"TemplateContent\": {\"Subject\": \"s\"}"}) {
            assertBadRequest(post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": "
                    + template(selector, content, DATA) + "}"), SEND_COMBINATION);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedTemplateSelectors")
    void sendBulkEmail_storedTemplateBesideCompleteContent_isRejected(String label, String selector) {
        assertBadRequest(post(BULK, "{" + FROM + ", \"DefaultContent\": " + template(selector, CONTENT, DATA)
                + ", " + ENTRIES + "}"), BULK_COMBINATION);
    }

    static Stream<Arguments> bulkSendsWithAnEarlierError() {
        String content = "\"DefaultContent\": " + NAME_BESIDE_CONTENT;
        String tooMany = "\"BulkEmailEntries\": [" + String.join(", ", Collections.nCopies(51, ENTRY)) + "]";
        return Stream.of(
                Arguments.of("no sender", "{" + content + ", " + ENTRIES + "}", "Email address not specified."),
                Arguments.of("malformed sender",
                        "{\"FromEmailAddress\": \"not an address\", " + content + ", " + ENTRIES + "}",
                        "Invalid email address<not an address>."),
                Arguments.of("malformed feedback address", "{" + FROM + ", " + content
                        + ", \"FeedbackForwardingEmailAddress\": \"not an address\", " + ENTRIES + "}",
                        "Invalid email address<not an address>."),
                Arguments.of("no entries", "{" + FROM + ", " + content + ", \"BulkEmailEntries\": []}", BULK_COUNT),
                Arguments.of("51 entries", "{" + FROM + ", " + content + ", " + tooMany + "}", BULK_COUNT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bulkSendsWithAnEarlierError")
    void sendBulkEmail_storedTemplateBesideContent_losesToSenderAndCountChecks(String label, String body,
            String message) {
        assertBadRequest(post(BULK, body), message);
    }

    static Stream<Arguments> bulkSendsWithALaterError() {
        String content = "\"DefaultContent\": " + NAME_BESIDE_CONTENT;
        return Stream.of(
                Arguments.of("malformed entry recipient", "{" + FROM + ", " + content
                        + ", \"BulkEmailEntries\": [{" + BAD_DESTINATION + "}]}"),
                Arguments.of("unknown configuration set", "{" + FROM + ", " + content
                        + ", \"ConfigurationSetName\": \"selector-ghost\", " + ENTRIES + "}"),
                Arguments.of("unknown tenant", "{" + FROM + ", " + content
                        + ", \"TenantName\": \"selector-ghost\", " + ENTRIES + "}"),
                Arguments.of("template data that is not JSON", "{" + FROM + ", \"DefaultContent\": "
                        + template(NAME, CONTENT, "\"TemplateData\": \"not json\"") + ", " + ENTRIES + "}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bulkSendsWithALaterError")
    void sendBulkEmail_storedTemplateBesideContent_winsOverLaterChecks(String label, String body) {
        assertBadRequest(post(BULK, body), BULK_COMBINATION);
    }

    // The two selectors may name the same template: the send then gets as far as verifying the
    // sender, which is unverified here.
    @Test
    void nameBesideArnOfTheSameTemplate_isAccepted() {
        String template = template(NAME, ARN, DATA);
        post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": " + template + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
        post(BULK, "{" + FROM + ", \"DefaultContent\": " + template + ", " + ENTRIES + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
    }

    @Test
    void nameBesideArnOfAnotherTemplate_doesNotAlign() {
        String template = template(NAME, "\"TemplateArn\": \"" + OTHER_ARN_VALUE + "\"", DATA);
        String message = "The SES resource 'selector-ghost' does not align with the given ARN '"
                + OTHER_ARN_VALUE + "'.";
        assertBadRequest(post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": " + template + "}"), message);
        assertBadRequest(post(BULK, "{" + FROM + ", \"DefaultContent\": " + template + ", " + ENTRIES + "}"),
                message);
    }

    @Test
    void nameAndArnBesideContent_isTheCombinationError() {
        String template = template(NAME, ARN, CONTENT, DATA);
        assertBadRequest(post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": " + template + "}"),
                SEND_COMBINATION);
        assertBadRequest(post(BULK, "{" + FROM + ", \"DefaultContent\": " + template + ", " + ENTRIES + "}"),
                BULK_COMBINATION);
    }

    private static String arn(String partition, String service, String region, String resource) {
        return "arn:" + partition + ":" + service + ":" + region + ":000000000000:" + resource;
    }

    static Stream<Arguments> invalidTemplateArns() {
        String otherRegion = arn("aws", "ses", "eu-west-1", "template/selector-ghost");
        String otherPartition = arn("aws-cn", "ses", "us-east-1", "template/selector-ghost");
        String otherService = arn("aws", "s3", "us-east-1", "template/selector-ghost");
        String otherResource = arn("aws", "ses", "us-east-1", "configuration-set/selector-ghost");
        return Stream.of(
                Arguments.of("other region", otherRegion,
                        "Invalid template ARN <" + otherRegion + ">. Template must be in region <us-east-1>"),
                Arguments.of("other partition", otherPartition,
                        "Invalid template ARN <" + otherPartition + ">. Partition should be <aws>"),
                Arguments.of("other service", otherService,
                        "Invalid template ARN <" + otherService + ">. Vendor should be <ses>"),
                Arguments.of("other resource type", otherResource,
                        "Invalid template ARN <" + otherResource + ">. Relative ID should start with <template/>"),
                Arguments.of("not an ARN", "not-an-arn", "ARNs must start with 'arn:': not-an-arn"),
                Arguments.of("empty template name", arn("aws", "ses", "us-east-1", "template/"),
                        "The template name must be specified."));
    }

    // The ARN is checked on its own terms before it is compared with the name, so the answer is
    // the same with and without a TemplateName.
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTemplateArns")
    void invalidTemplateArn_isRejectedWithOrWithoutAName(String label, String arn, String message) {
        String arnMember = "\"TemplateArn\": \"" + arn + "\"";
        assertBadRequest(post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": "
                + template(NAME, arnMember, DATA) + "}"), message);
        assertBadRequest(post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": "
                + template(arnMember, DATA) + "}"), message);
        assertBadRequest(post(BULK, "{" + FROM + ", \"DefaultContent\": " + template(NAME, arnMember, DATA)
                + ", " + ENTRIES + "}"), message);
    }

    // SES does not check the account of a template ARN.
    @Test
    void templateArnOfAnotherAccount_isAccepted() {
        String template = template(NAME,
                "\"TemplateArn\": \"arn:aws:ses:us-east-1:999999999999:template/selector-ghost\"", DATA);
        post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": " + template + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
    }

    @Test
    void nullTemplateContent_countsAsAbsent() {
        String nullContent = "\"TemplateContent\": null";
        post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": " + template(NAME, nullContent, DATA) + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
        post(BULK, "{" + FROM + ", \"DefaultContent\": " + template(NAME, nullContent, DATA) + ", " + ENTRIES + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
        post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": "
                + template("\"TemplateName\": null", CONTENT, DATA) + "}")
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
        assertBadRequest(post(SEND, "{" + FROM + ", " + DESTINATION + ", \"Content\": "
                + template(nullContent, DATA) + "}"), "TemplateName and TemplateArn cannot be both empty");
    }

    @Test
    void sendEmail_whitespaceOnlySender_isAnInvalidAddress() {
        assertBadRequest(post(SEND, "{\"FromEmailAddress\": \" \", " + DESTINATION + ", \"Content\": "
                + template(CONTENT, DATA) + "}"), "Invalid email address  .");
        assertBadRequest(post(SEND, "{\"FromEmailAddress\": \"\", " + DESTINATION + ", \"Content\": "
                + template(CONTENT, DATA) + "}"), "Source cannot be empty");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankSenders")
    void sendBulkEmail_blankSender_isNotSpecified(String label, String sender) {
        assertBadRequest(post(BULK, "{\"FromEmailAddress\": \"" + sender + "\", \"DefaultContent\": "
                + template(CONTENT, DATA) + ", " + ENTRIES + "}"), "Email address not specified.");
    }

    static Stream<Arguments> blankSenders() {
        return Stream.of(Arguments.of("empty", ""), Arguments.of("space", " "));
    }
}
