package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.floci.aws.services.ses.SesV2Requests.DESTINATION;
import static io.floci.aws.services.ses.SesV2Requests.ENTRY;
import static io.floci.aws.services.ses.SesV2Requests.TO;
import static io.floci.aws.services.ses.SesV2Requests.post;
import static org.hamcrest.Matchers.equalTo;

/**
 * The name rule the v1 and v2 sends apply to the stored template they name. It is worded unlike
 * the template operations' rule, checks the length before the characters, and answers just before
 * the sender verification (probe-confirmed). The sender is never verified, so a name that passes
 * reaches the sender check.
 */
@QuarkusTest
class SesSendTemplateNameIntegrationTest {

    private static final String UNVERIFIED = "sender@unverified-send-template-name.floci.test";
    private static final String DOTTED = "a.b";
    private static final String TOO_LONG = "a.b" + "a".repeat(62);
    private static final String LONGEST = "a".repeat(64);
    private static final String DOTTED_ARN = "arn:aws:ses:us-east-1:000000000000:template/" + DOTTED;

    private static String charactersMessage(String name) {
        return "Template name " + name + " is only allowed to contain alphanumeric characters, underscore, and dash";
    }

    private static String lengthMessage(String name) {
        return "Template name " + name + " exceeds maximum allowed length 64";
    }

    // A member in extra replaces the default one rather than being sent twice.
    private static RequestSpecification v1(String operation, String template, Map<String, String> extra) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Source", UNVERIFIED);
        params.put("Template", template);
        if ("SendTemplatedEmail".equals(operation)) {
            params.put("Destination.ToAddresses.member.1", TO);
            params.put("TemplateData", "{}");
        } else {
            params.put("Destinations.member.1.Destination.ToAddresses.member.1", TO);
            params.put("DefaultTemplateData", "{}");
        }
        params.putAll(extra);
        return SesV1Requests.query(operation).formParams(params);
    }

    private static ValidatableResponse sendV1(String operation, String template, Map<String, String> extra) {
        return v1(operation, template, extra).when().post("/").then();
    }

    private static ValidatableResponse sendV1(String operation, String template) {
        return sendV1(operation, template, Map.of());
    }

    private static void assertV1(ValidatableResponse response, String code, String message) {
        response
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo(code))
            .body("ErrorResponse.Error.Message", equalTo(message));
    }

    private static ValidatableResponse sendV2(String template) {
        return post("/v2/email/outbound-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\","
                + " " + DESTINATION + ","
                + " \"Content\": {\"Template\": {" + template + ", \"TemplateData\": \"{}\"}}}");
    }

    private static ValidatableResponse sendBulkV2(String template, String entry) {
        return post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\","
                + " \"DefaultContent\": {\"Template\": {" + template + ", \"TemplateData\": \"{}\"}},"
                + " \"BulkEmailEntries\": [" + entry + "]}");
    }

    private static void assertV2(ValidatableResponse response, String message) {
        response
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendTemplatedEmail", "SendBulkTemplatedEmail"})
    void v1_nameWithAnotherCharacter_isRejected(String operation) {
        assertV1(sendV1(operation, DOTTED), "InvalidParameterValue", charactersMessage(DOTTED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendTemplatedEmail", "SendBulkTemplatedEmail"})
    void v1_nameOverSixtyFourCharacters_reportsTheLengthFirst(String operation) {
        assertV1(sendV1(operation, TOO_LONG), "InvalidParameterValue", lengthMessage(TOO_LONG));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SendTemplatedEmail", "SendBulkTemplatedEmail"})
    void v1_sixtyFourCharacterName_reachesTheSenderCheck(String operation) {
        sendV1(operation, LONGEST)
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MessageRejected"));
    }

    @Test
    void sendTemplatedEmail_whitespaceName_isRejectedByTheCharacterRule() {
        assertV1(sendV1("SendTemplatedEmail", "  "), "InvalidParameterValue", charactersMessage("  "));
    }

    @Test
    void sendTemplatedEmail_sourceSyntax_isReportedFirst() {
        assertV1(sendV1("SendTemplatedEmail", DOTTED, Map.of("Source", "not-an-address")),
                "InvalidParameterValue", "Missing final '@domain'");
    }

    @Test
    void sendTemplatedEmail_unknownConfigurationSet_isReportedFirst() {
        sendV1("SendTemplatedEmail", DOTTED, Map.of("ConfigurationSetName", "send-template-name-no-such-set"))
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ConfigurationSetDoesNotExist"));
    }

    @Test
    void sendBulkTemplatedEmail_missingDefaultTemplateData_isReportedFirst() {
        SesV1Requests.query("SendBulkTemplatedEmail")
            .formParam("Source", UNVERIFIED)
            .formParam("Template", DOTTED)
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", TO)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("The default template data does not exist"));
    }

    @Test
    void sendBulkTemplatedEmail_noRecipient_answersEveryEntryInstead() {
        SesV1Requests.query("SendBulkTemplatedEmail")
            .formParam("Source", UNVERIFIED)
            .formParam("Template", DOTTED)
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses", "")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member.Error",
                    equalTo("Destination cannot be empty"));
    }

    // An ARN naming such a template answers the template operations' wording instead.
    @ParameterizedTest
    @ValueSource(strings = {"SendTemplatedEmail", "SendBulkTemplatedEmail"})
    void v1_templateArnNamingSuchATemplate_answersTheTemplateOperationsWording(String operation) {
        assertV1(sendV1(operation, DOTTED, Map.of("TemplateArn", DOTTED_ARN)), "InvalidParameterValue",
                "Template name <a.b>: only alphanumeric ASCII characters, '_', and '-' are allowed.");
    }

    @Test
    void v2_sendEmail_appliesTheSameRule() {
        assertV2(sendV2("\"TemplateName\": \"" + DOTTED + "\""), charactersMessage(DOTTED));
        assertV2(sendV2("\"TemplateName\": \"" + TOO_LONG + "\""), lengthMessage(TOO_LONG));
    }

    @Test
    void v2_sendBulkEmail_appliesTheSameRule() {
        assertV2(sendBulkV2("\"TemplateName\": \"" + DOTTED + "\"", ENTRY), charactersMessage(DOTTED));
        assertV2(sendBulkV2("\"TemplateName\": \"" + TOO_LONG + "\"", ENTRY), lengthMessage(TOO_LONG));
    }

    @Test
    void v2_templateArnNamingSuchATemplate_answersTheTemplateOperationsWording() {
        assertV2(sendV2("\"TemplateName\": \"" + DOTTED + "\", \"TemplateArn\": \"" + DOTTED_ARN + "\""),
                "Template name <a.b>: only alphanumeric ASCII characters, '_', and '-' are allowed.");
    }

    @Test
    void v2_sendBulkEmail_noRecipient_answersEveryEntryInstead() {
        post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\","
                + " \"DefaultContent\": {\"Template\": {\"TemplateName\": \"" + DOTTED + "\", \"TemplateData\": \"{}\"}},"
                + " \"BulkEmailEntries\": [{\"Destination\": {\"ToAddresses\": []}}]}")
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", equalTo("Destination cannot be empty"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"TemplateData\": \"{}\"", "\"TemplateName\": \"  \""})
    void v2_sendBulkEmail_withoutATemplateSelector_isRejected(String template) {
        assertV2(post("/v2/email/outbound-bulk-emails", "{\"FromEmailAddress\": \"" + UNVERIFIED + "\","
                + " \"DefaultContent\": {\"Template\": {" + template + "}}, \"BulkEmailEntries\": [" + ENTRY + "]}"),
                "TemplateName and TemplateArn both can not be empty");
    }

    @Test
    void v2_sendBulkEmail_emptyTemplateName_isModelViolation() {
        assertV2(sendBulkV2("\"TemplateName\": \"\"", ENTRY), "1 validation error detected: Value at "
                + "'defaultContent.template.templateName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1");
    }
}
