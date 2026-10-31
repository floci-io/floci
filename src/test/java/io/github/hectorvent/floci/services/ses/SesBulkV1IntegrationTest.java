package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.core.common.SmithyValidationErrors.notNullWithoutValue;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration tests for SES V1 Query-protocol SendBulkTemplatedEmail.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesBulkV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    // A sender of its own: an identity under example.com would outlive this class and reach the
    // other SES classes that share the application.
    private static final String SENDER = "bulk-v1@floci.test";

    private static final String MISSING_DESTINATION_2 = "1 validation error detected: Value at "
            + "'destinations.2.member.destination' failed to satisfy constraint: Member must not be null";

    private static final String MEMBERS = "SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member";

    private static final String FULL_TEMPLATE_DATA = "{\"name\":\"X\",\"team\":\"floci\"}";

    private static final Pattern MESSAGE_ID_PATTERN =
            Pattern.compile("<MessageId>([^<]+)</MessageId>");

    @Test
    @Order(1)
    void createTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", SENDER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "v1-bulk-welcome")
            .formParam("Template.SubjectPart", "Hello {{name}}")
            .formParam("Template.TextPart", "Hi {{name}}, team {{team}}!")
            .formParam("Template.HtmlPart", "<p>Hi <b>{{name}}</b> ({{team}})</p>")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void sendBulkTemplatedEmail_perDestinationReplacement() {
        String body = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"team\":\"floci\"}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.1.ReplacementTemplateData", "{\"name\":\"Alice\"}")
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
            .formParam("Destinations.member.2.ReplacementTemplateData", "{\"name\":\"Bob\",\"team\":\"override\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SendBulkTemplatedEmailResponse"))
            .extract().body().asString();

        long successCount = body.split("<Status>Success</Status>", -1).length - 1L;
        assertEquals(2L, successCount, "expected two Success entries");

        List<String> messageIds = new ArrayList<>();
        Matcher m = MESSAGE_ID_PATTERN.matcher(body);
        while (m.find()) {
            messageIds.add(m.group(1));
        }
        assertEquals(2, messageIds.size(), "expected two MessageIds");
        assertNotEquals(messageIds.get(0), messageIds.get(1), "MessageIds must be unique");

        // First entry inherits team=floci from defaults; second overrides.
        given()
            .queryParam("id", messageIds.get(0))
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Hello Alice"))
            .body("messages[0].Body.text_part", equalTo("Hi Alice, team floci!"));

        given()
            .queryParam("id", messageIds.get(1))
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Hello Bob"))
            .body("messages[0].Body.text_part", equalTo("Hi Bob, team override!"));
    }

    @Test
    @Order(3)
    void sendBulkTemplatedEmail_unknownTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "ghost")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>TemplateDoesNotExist</Code>"));
    }

    @Test
    @Order(4)
    void sendBulkTemplatedEmail_missingDestinations() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value at 'destinations' "
                    + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(5)
    void sendBulkTemplatedEmail_missingTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value at 'template' "
                    + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(6)
    void sendBulkTemplatedEmail_entryWithoutDestination_isAValidationError() {
        // An entry with only ReplacementTemplateData has a null Destination, which the model
        // validation refuses for the whole request (probe-confirmed).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"team\":\"floci\"}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.2.ReplacementTemplateData", "{\"name\":\"Ghost\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo(MISSING_DESTINATION_2));
    }

    @Test
    @Order(7)
    void sendBulkTemplatedEmail_accountSendingPaused_returnsTopLevelError() {
        // Disable account sending via the v2 endpoint, then expect v1 SendBulkTemplatedEmail
        // to fail with AccountSendingPausedException without sending any mail.
        try {
            given()
                .contentType("application/json")
                .body("{\"SendingEnabled\":false}")
            .when()
                .put("/v2/email/account/sending")
            .then()
                .statusCode(200);

            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", "SendBulkTemplatedEmail")
                .formParam("Source", SENDER)
                .formParam("Template", "v1-bulk-welcome")
                .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>AccountSendingPausedException</Code>"));
        } finally {
            given()
                .contentType("application/json")
                .body("{\"SendingEnabled\":true}")
            .when()
                .put("/v2/email/account/sending")
            .then()
                .statusCode(200);
        }
    }

    // AWS counts the destinations before it looks the template up (probed 2026-10-24).
    @Test
    @Order(8)
    void sendBulkTemplatedEmail_destinationsExceed50_isCountedBeforeTheTemplateLookup() {
        destinations(given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "ghost"), 51)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"))
            .body(containsString(
                    "<Message>You must specify at least 1 destination, and no more than 50 destinations</Message>"));
    }

    @Test
    @Order(12)
    void sendBulkTemplatedEmail_perEntryMissingVariable_mapsToInvalidParameterValue() {
        // Per-entry rendering failure (missing {{name}}) surfaces as
        // <Status>InvalidParameterValue</Status> in the bulk Query response,
        // not as Failed.
        String body = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"team\":\"floci\"}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.1.ReplacementTemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertEquals(1L, body.split("<Status>InvalidParameterValue</Status>", -1).length - 1L,
                "expected per-entry Status=InvalidParameterValue for missing rendering variable");
    }

    // Each of these fails its own entry with a 200 for the call (probed 2026-10-25).
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReplacementTemplateData")
    @Order(10)
    void sendBulkTemplatedEmail_invalidReplacementTemplateData_failsOnlyThatEntry(String label, String data) {
        bulkSpec(FULL_TEMPLATE_DATA)
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.1.ReplacementTemplateData", data)
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MEMBERS + ".size()", equalTo(2))
            .body(MEMBERS + "[0].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[0].Error", equalTo("The template data is invalid"))
            .body(MEMBERS + "[1].Status", equalTo("Success"));
    }

    static Stream<Arguments> invalidReplacementTemplateData() {
        return Stream.of(
                Arguments.of("malformed JSON", "{not json"),
                Arguments.of("scalar", "42"));
    }

    @Test
    @Order(11)
    void sendBulkTemplatedEmail_invalidDefaultTemplateData_failsEveryEntry() {
        bulkSpec("[1,2,3]")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.1.ReplacementTemplateData", FULL_TEMPLATE_DATA)
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MEMBERS + ".size()", equalTo(2))
            .body(MEMBERS + "[0].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[0].Error", equalTo("The template data is invalid"))
            .body(MEMBERS + "[1].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[1].Error", equalTo("The template data is invalid"));
    }

    @Test
    @Order(9)
    void sendBulkTemplatedEmail_recipientsExceed50_failsOnlyThatEntry() {
        RequestSpecification spec = bulkSpec(FULL_TEMPLATE_DATA);
        for (int i = 1; i <= 51; i++) {
            spec = spec.formParam(
                    "Destinations.member.1.Destination.ToAddresses.member." + i,
                    "user" + i + "@example.com");
        }
        spec
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MEMBERS + ".size()", equalTo(2))
            .body(MEMBERS + "[0].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[0].Error", equalTo("Recipient count exceeds 50."))
            .body(MEMBERS + "[1].Status", equalTo("Success"));
    }

    @Test
    @Order(18)
    void sendBulkTemplatedEmail_emptyReplacementTagName_failsOnlyThatEntry() {
        bulkSpec(FULL_TEMPLATE_DATA)
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.1.ReplacementTags.member.1.Name", "")
            .formParam("Destinations.member.1.ReplacementTags.member.1.Value", "v")
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MEMBERS + ".size()", equalTo(2))
            .body(MEMBERS + "[0].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[0].Error", equalTo("The tag name must be specified."))
            .body(MEMBERS + "[1].Status", equalTo("Success"));
    }

    @Test
    @Order(19)
    void sendBulkTemplatedEmail_emptyDefaultTagName_failsEveryEntry() {
        bulkSpec(FULL_TEMPLATE_DATA)
            .formParam("DefaultTags.member.1.Name", "")
            .formParam("DefaultTags.member.1.Value", "v")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MEMBERS + ".size()", equalTo(2))
            .body(MEMBERS + "[0].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[0].Error", equalTo("The tag name must be specified."))
            .body(MEMBERS + "[1].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[1].Error", equalTo("The tag name must be specified."));
    }

    // Answered before the template lookup and the sender verification (probed 2026-10-27).
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingDefaultTemplateData")
    @Order(20)
    void sendBulkTemplatedEmail_missingDefaultTemplateData_rejectsTheCall(String label, String data, String source,
                                                                          String template) {
        RequestSpecification spec = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", source)
            .formParam("Template", template)
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com");
        if (data != null) {
            spec = spec.formParam("DefaultTemplateData", data);
        }
        spec
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("The default template data does not exist"));
    }

    static Stream<Arguments> missingDefaultTemplateData() {
        return Stream.of(
                Arguments.of("absent", null, SENDER, "v1-bulk-welcome"),
                Arguments.of("empty", "", SENDER, "v1-bulk-welcome"),
                Arguments.of("absent, unknown template", null, SENDER, "v1-bulk-missing"),
                Arguments.of("absent, unverified Source", null, "bulk-v1-unverified@floci.test", "v1-bulk-welcome"));
    }

    // An address syntax error wins over a blank tag name, which wins over invalid template data, which
    // wins over the recipient count (probed 2026-10-26 and 2026-10-27).
    @ParameterizedTest(name = "{0}")
    @MethodSource("entriesWithSeveralFailures")
    @Order(21)
    void sendBulkTemplatedEmail_entryWithSeveralFailures_answersTheFirst(String label, Map<String, String> entry,
                                                                          String error) {
        bulkSpec(FULL_TEMPLATE_DATA)
            .formParams(entry)
            .formParam("Destinations.member.2.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(MEMBERS + ".size()", equalTo(2))
            .body(MEMBERS + "[0].Status", equalTo("InvalidParameterValue"))
            .body(MEMBERS + "[0].Error", equalTo(error))
            .body(MEMBERS + "[1].Status", equalTo("Success"));
    }

    static Stream<Arguments> entriesWithSeveralFailures() {
        return Stream.of(
                Arguments.of("51 recipients, data 42 and an empty tag name",
                        with(recipients(51, null),
                                "Destinations.member.1.ReplacementTemplateData", "42",
                                "Destinations.member.1.ReplacementTags.member.1.Name", "",
                                "Destinations.member.1.ReplacementTags.member.1.Value", "v"),
                        "The tag name must be specified."),
                Arguments.of("51 recipients and data 42",
                        with(recipients(51, null), "Destinations.member.1.ReplacementTemplateData", "42"),
                        "The template data is invalid"),
                Arguments.of("51 recipients, one malformed", recipients(51, "not-an-address"),
                        "Missing final '@domain'"),
                Arguments.of("data 42 and a malformed recipient",
                        with(recipients(1, "not-an-address"), "Destinations.member.1.ReplacementTemplateData", "42"),
                        "Missing final '@domain'"));
    }

    // The first entry's To list of count addresses, the last one replaced by last when given.
    private static Map<String, String> recipients(int count, String last) {
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i <= count; i++) {
            params.put("Destinations.member.1.Destination.ToAddresses.member." + i,
                    i == count && last != null ? last : "user" + i + "@example.com");
        }
        return params;
    }

    private static RequestSpecification bulkSpec(String defaultTemplateData) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", defaultTemplateData);
    }

    @Test
    @Order(13)
    void sendBulkTemplatedEmail_fiftyDestinations_sendsEachDestination() {
        String body = destinations(given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"name\":\"X\",\"team\":\"floci\"}"), 50)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertEquals(50L, body.split("<Status>Success</Status>", -1).length - 1L);
    }

    @Test
    @Order(14)
    void sendBulkTemplatedEmail_emptyToAddresses_answersTheEntryBeforeSenderAndTemplate() {
        // boto3 serializes ToAddresses=[] as a bare empty-valued key. AWS answers the entry even
        // for an unverified Source and a missing template (probe-confirmed).
        String body = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "bulk-v1-unverified@floci.test")
            .formParam("Template", "ghost")
            .formParam("DefaultTemplateData", "{}")
            .formParam("Destinations.member.1.Destination.ToAddresses", "")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Status>InvalidParameterValue</Status>"))
            .body(containsString("<Error>Destination cannot be empty</Error>"))
            .body(not(containsString("<MessageId>")))
            .extract().body().asString();

        assertEquals(1L, body.split("<member>", -1).length - 1L);
    }

    @Test
    @Order(15)
    void sendBulkTemplatedEmail_skippedIndex_isAValidationError() {
        // boto3 sends nothing for Destination={}, which leaves a gap in the member indexes.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"name\":\"X\",\"team\":\"floci\"}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.3.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo(MISSING_DESTINATION_2));
    }

    @Test
    @Order(16)
    void sendBulkTemplatedEmail_emptyEntryBesideRecipients_failsOnlyThatEntry() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", SENDER)
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"name\":\"X\",\"team\":\"floci\"}")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
            .formParam("Destinations.member.2.Destination.ToAddresses", "")
            .formParam("Destinations.member.3.Destination.ToAddresses.member.1", "bob@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member.size()", equalTo(3))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[0].Status",
                    equalTo("Success"))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[1].Status",
                    equalTo("InvalidParameterValue"))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[1].Error",
                    equalTo("Missing required header 'To'."))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[1].MessageId.size()",
                    equalTo(0))
            .body("SendBulkTemplatedEmailResponse.SendBulkTemplatedEmailResult.Status.member[2].Status",
                    equalTo("Success"));
    }

    // The model validation answers ahead of the address syntax, the template selector and the
    // destination count (probed 2026-10-25).
    @ParameterizedTest(name = "{0}")
    @MethodSource("modelViolationParams")
    @Order(17)
    void sendBulkTemplatedEmail_modelValidationPrecedesOtherChecks(String label, Map<String, String> params,
                                                                   String message) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParams(params)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", equalTo(message));
    }

    static Stream<Arguments> modelViolationParams() {
        Map<String, String> withoutDestination2 = Map.of(
                "Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com",
                "Destinations.member.2.ReplacementTemplateData", "{}");
        Map<String, String> oneRecipient = Map.of(
                "Source", SENDER,
                "Template", "v1-bulk-welcome",
                "DefaultTemplateData", FULL_TEMPLATE_DATA,
                "Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com");
        Map<String, String> gapAt2 = new HashMap<>();
        for (int i = 1; i <= 51; i++) {
            if (i != 2) {
                gapAt2.put("Destinations.member." + i + ".Destination.ToAddresses.member.1",
                        "user" + i + "@example.com");
            }
        }
        return Stream.of(
                Arguments.of("malformed Source",
                        with(withoutDestination2, "Source", "not-an-address", "Template", "v1-bulk-welcome"),
                        MISSING_DESTINATION_2),
                Arguments.of("no Template", with(withoutDestination2, "Source", SENDER),
                        "2 validation errors detected: Value at 'template' failed to satisfy constraint: "
                                + "Member must not be null; Value at 'destinations.2.member.destination' "
                                + "failed to satisfy constraint: Member must not be null"),
                Arguments.of("51 indexes with a gap", with(gapAt2, "Source", SENDER, "Template", "v1-bulk-welcome"),
                        MISSING_DESTINATION_2),
                Arguments.of("replacement tag without Name",
                        with(oneRecipient, "Destinations.member.1.ReplacementTags.member.1.Value", "v"),
                        "1 validation error detected: "
                                + notNullWithoutValue("destinations.1.member.replacementTags.1.member.name")),
                // Probed 2026-10-26 from here on.
                Arguments.of("replacement tag without Value",
                        with(oneRecipient, "Destinations.member.1.ReplacementTags.member.1.Name", "a"),
                        "1 validation error detected: "
                                + notNullWithoutValue("destinations.1.member.replacementTags.1.member.value")),
                Arguments.of("default tag without Name", with(oneRecipient, "DefaultTags.member.1.Value", "v"),
                        "1 validation error detected: " + notNullWithoutValue("defaultTags.1.member.name")),
                // 'template' comes first although DefaultTags precedes Template in the model.
                Arguments.of("no Template and a default tag without Name", Map.of(
                                "Source", SENDER,
                                "Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com",
                                "DefaultTags.member.1.Value", "v"),
                        "2 validation errors detected: " + notNullWithoutValue("template") + "; "
                                + notNullWithoutValue("defaultTags.1.member.name")),
                Arguments.of("replacement tag at index 2 only", with(oneRecipient,
                                "Destinations.member.1.ReplacementTags.member.2.Name", "a",
                                "Destinations.member.1.ReplacementTags.member.2.Value", "v"),
                        "2 validation errors detected: "
                                + notNullWithoutValue("destinations.1.member.replacementTags.1.member.name") + "; "
                                + notNullWithoutValue("destinations.1.member.replacementTags.1.member.value")),
                Arguments.of("default tag at index 2 only", with(oneRecipient,
                                "DefaultTags.member.2.Name", "a", "DefaultTags.member.2.Value", "v"),
                        "2 validation errors detected: " + notNullWithoutValue("defaultTags.1.member.name") + "; "
                                + notNullWithoutValue("defaultTags.1.member.value")));
    }

    private static Map<String, String> with(Map<String, String> params, String... namesAndValues) {
        Map<String, String> merged = new HashMap<>(params);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            merged.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return merged;
    }

    private static RequestSpecification destinations(RequestSpecification spec, int count) {
        for (int i = 1; i <= count; i++) {
            spec = spec.formParam("Destinations.member." + i + ".Destination.ToAddresses.member.1",
                    "user" + i + "@example.com");
        }
        return spec;
    }
}
