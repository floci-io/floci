package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for SES V1 Query-protocol template actions.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTemplateV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";

    @Test
    @Order(1)
    void createTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "v1-welcome")
            .formParam("Template.SubjectPart", "Hello {{name}}")
            .formParam("Template.TextPart", "Hi {{name}}!")
            .formParam("Template.HtmlPart", "<p>Hi <b>{{name}}</b>!</p>")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CreateTemplateResponse"));
    }

    @Test
    @Order(2)
    void createTemplate_duplicateRejected() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "v1-welcome")
            .formParam("Template.SubjectPart", "dup")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AlreadyExists</Code>"));
    }

    @Test
    @Order(3)
    void getTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", "v1-welcome")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TemplateName>v1-welcome</TemplateName>"))
            .body(containsString("<SubjectPart>Hello {{name}}</SubjectPart>"))
            .body(containsString("<TextPart>Hi {{name}}!</TextPart>"));
    }

    @Test
    @Order(4)
    void getTemplate_notFound() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", "missing-template")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>TemplateDoesNotExist</Code>"));
    }

    @Test
    @Order(5)
    void updateTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "UpdateTemplate")
            .formParam("Template.TemplateName", "v1-welcome")
            .formParam("Template.SubjectPart", "Welcome {{name}}!")
            .formParam("Template.TextPart", "Hello {{name}}, from {{team}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("UpdateTemplateResponse"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", "v1-welcome")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubjectPart>Welcome {{name}}!</SubjectPart>"))
            .body(containsString("{{team}}"));
    }

    @Test
    @Order(6)
    void listTemplates_includesCreated() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "ListTemplates")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TemplatesMetadata>"))
            .body(containsString("<Name>v1-welcome</Name>"));
    }

    @Test
    @Order(7)
    void sendTemplatedEmail_substitutesVariables() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", "v1-sender@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String body = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "v1-sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Template", "v1-welcome")
            .formParam("TemplateData", "{\"name\":\"Alice\",\"team\":\"floci\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("SendTemplatedEmailResponse"))
            .body(containsString("<MessageId>"))
            .extract().body().asString();

        String messageId = body.replaceAll("(?s).*<MessageId>([^<]+)</MessageId>.*", "$1");

        given()
            .queryParam("id", messageId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Welcome Alice!"))
            .body("messages[0].Body.text_part", equalTo("Hello Alice, from floci"));
    }

    @Test
    @Order(8)
    void sendTemplatedEmail_unknownTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "v1-sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Template", "ghost")
            .formParam("TemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>TemplateDoesNotExist</Code>"));
    }

    @ParameterizedTest(name = "TemplateData={0}")
    @MethodSource("nonObjectTemplateDataPayloads")
    @Order(50)
    void sendTemplatedEmail_nonObjectTemplateData_returnsInvalidParameterValue(String templateData) {
        // TemplateData is parsed before template lookup, so any template name suffices
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "v1-sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Template", "any")
            .formParam("TemplateData", templateData)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    static Stream<Arguments> nonObjectTemplateDataPayloads() {
        return Stream.of(
                Arguments.of("[1,2,3]"),
                Arguments.of("42")
        );
    }

    @Test
    @Order(9)
    void deleteTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteTemplate")
            .formParam("TemplateName", "v1-welcome")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DeleteTemplateResponse"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("TemplateName", "v1-welcome")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>TemplateDoesNotExist</Code>"));
    }

    @Test
    @Order(10)
    void deleteTemplate_notFound() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteTemplate")
            .formParam("TemplateName", "already-gone")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>TemplateDoesNotExist</Code>"));
    }

    @Test
    @Order(11)
    void createTemplate_rejectsLeadingTrailingWhitespace() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", " padded ")
            .formParam("Template.SubjectPart", "s")
            .formParam("Template.TextPart", "t")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidTemplate</Code>"));
    }

    @Test
    @Order(12)
    void sendTemplatedEmail_withTemplateArn_resolvesStoredTemplate() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateTemplate")
            .formParam("Template.TemplateName", "v1-arn-welcome")
            .formParam("Template.SubjectPart", "Hi {{name}}")
            .formParam("Template.TextPart", "Hello {{name}}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", "v1-arn-sender@example.com")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "v1-arn-sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("TemplateArn", "arn:aws:ses:us-east-1:000000000000:template/v1-arn-welcome")
            .formParam("TemplateData", "{\"name\":\"Alice\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }

    @Test
    @Order(13)
    void sendTemplatedEmail_withNameAndArn_accepted() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "v1-arn-sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("Template", "v1-arn-welcome")
            .formParam("TemplateArn", "arn:aws:ses:us-east-1:000000000000:template/v1-arn-welcome")
            .formParam("TemplateData", "{\"name\":\"Alice\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));
    }

    @Test
    @Order(14)
    void sendTemplatedEmail_withMalformedArn_returns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendTemplatedEmail")
            .formParam("Source", "v1-arn-sender@example.com")
            .formParam("Destination.ToAddresses.member.1", "to@example.com")
            .formParam("TemplateArn", "not-an-arn")
            .formParam("TemplateData", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }
}
