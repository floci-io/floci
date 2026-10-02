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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
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

    private static final Pattern MESSAGE_ID_PATTERN =
            Pattern.compile("<MessageId>([^<]+)</MessageId>");

    @Test
    @Order(1)
    void createTemplate() {
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
            .formParam("Source", "bulk@example.com")
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
            .formParam("Source", "bulk@example.com")
            .formParam("Template", "ghost")
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
            .formParam("Source", "bulk@example.com")
            .formParam("Template", "v1-bulk-welcome")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(5)
    void sendBulkTemplatedEmail_missingTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "bulk@example.com")
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(6)
    void sendBulkTemplatedEmail_perEntryMissingDestination_mapsToInvalidParameterValue() {
        // An entry with only ReplacementTemplateData (no recipient) reaches the per-entry send,
        // which throws AwsException("InvalidParameterValue", ...). Expected per-entry
        // Status string in v1 is "InvalidParameterValue", not "Failed" or "InvalidParameter".
        // DefaultTemplateData supplies team so rendering succeeds before the recipient check.
        String body = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "bulk@example.com")
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", "{\"team\":\"floci\"}")
            .formParam("Destinations.member.1.ReplacementTemplateData", "{\"name\":\"Ghost\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertEquals(1L, body.split("<Status>InvalidParameterValue</Status>", -1).length - 1L,
                "expected per-entry Status=InvalidParameterValue");
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
                .formParam("Source", "bulk@example.com")
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

    @Test
    @Order(8)
    void sendBulkTemplatedEmail_destinationsExceeds50_returnsMessageRejected() {
        RequestSpecification spec = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "bulk@example.com")
            .formParam("Template", "v1-bulk-welcome");
        for (int i = 1; i <= 51; i++) {
            spec = spec.formParam(
                    "Destinations.member." + i + ".Destination.ToAddresses.member.1",
                    "user" + i + "@example.com");
        }
        spec
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MessageRejected</Code>"));
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
            .formParam("Source", "bulk@example.com")
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonObjectBulkTemplateDataPayloads")
    @Order(10)
    void sendBulkTemplatedEmail_nonObjectTemplateData_returnsInvalidParameterValue(
            String label, String defaultTemplateData, String replacementTemplateData) {
        RequestSpecification spec = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "bulk@example.com")
            .formParam("Template", "v1-bulk-welcome")
            .formParam("DefaultTemplateData", defaultTemplateData)
            .formParam("Destinations.member.1.Destination.ToAddresses.member.1", "alice@example.com");
        if (replacementTemplateData != null) {
            spec = spec.formParam("Destinations.member.1.ReplacementTemplateData", replacementTemplateData);
        }
        spec
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    static Stream<Arguments> nonObjectBulkTemplateDataPayloads() {
        return Stream.of(
                Arguments.of("array DefaultTemplateData", "[1,2,3]", null),
                Arguments.of("scalar ReplacementTemplateData", "{\"team\":\"floci\"}", "42")
        );
    }

    @Test
    @Order(9)
    void sendBulkTemplatedEmail_recipientsExceeds50_returnsMessageRejected() {
        RequestSpecification spec = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendBulkTemplatedEmail")
            .formParam("Source", "bulk@example.com")
            .formParam("Template", "v1-bulk-welcome");
        for (int i = 1; i <= 51; i++) {
            spec = spec.formParam(
                    "Destinations.member.1.Destination.ToAddresses.member." + i,
                    "user" + i + "@example.com");
        }
        spec
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MessageRejected</Code>"));
    }
}
