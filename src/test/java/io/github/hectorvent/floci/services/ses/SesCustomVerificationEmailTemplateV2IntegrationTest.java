package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the SES V2 REST JSON custom-verification-email-template APIs. Behaviour and
 * error shapes are verified against real AWS: the From address must be a verified identity, the
 * redirection URLs must be valid, List omits TemplateContent, and duplicate/not-found error shapes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesCustomVerificationEmailTemplateV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String FROM = "cvet-sender@floci.test";
    private static final String BASE = "/v2/email/custom-verification-email-templates";
    private static final String NAME = "cvet-v2-a";

    private static String body(String name, String from, String subject, String successUrl) {
        return """
            {
              "TemplateName": "%s",
              "FromEmailAddress": "%s",
              "TemplateSubject": "%s",
              "TemplateContent": "<html><body>verify</body></html>",
              "SuccessRedirectionURL": "%s",
              "FailureRedirectionURL": "https://example.com/fail"
            }
            """.formatted(name, from, subject, successUrl);
    }

    @Test
    @Order(0)
    void setup_verifyFromIdentity() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\":\"" + FROM + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    @Test
    @Order(1)
    void createThenGet() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(body(NAME, FROM, "Verify your email", "https://example.com/ok"))
        .when().post(BASE).then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get(BASE + "/" + NAME)
        .then().statusCode(200)
                .body("TemplateName", equalTo(NAME))
                .body("FromEmailAddress", equalTo(FROM))
                .body("TemplateSubject", equalTo("Verify your email"))
                .body("TemplateContent", equalTo("<html><body>verify</body></html>"))
                .body("SuccessRedirectionURL", equalTo("https://example.com/ok"))
                .body("FailureRedirectionURL", equalTo("https://example.com/fail"));
    }

    @Test
    @Order(2)
    void list_omitsTemplateContent() {
        given().header("Authorization", AUTH)
        .when().get(BASE)
        .then().statusCode(200)
                .body("CustomVerificationEmailTemplates.TemplateName", hasItem(NAME))
                .body("CustomVerificationEmailTemplates.find { it.TemplateName == '" + NAME + "' }.TemplateContent",
                        nullValue());
    }

    @Test
    @Order(3)
    void createDuplicate_throwsAlreadyExists() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(body(NAME, FROM, "x", "https://example.com/ok"))
        .when().post(BASE).then().statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"))
                .body("message", equalTo("Custom verification email template <" + NAME + "> already exists"));
    }

    @Test
    @Order(4)
    void getMissing_throwsNotFound() {
        given().header("Authorization", AUTH)
        .when().get(BASE + "/nope")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Custom verification email template <nope> does not exist"));
    }

    @Test
    @Order(5)
    void update_replaces() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(body(NAME, FROM, "Updated subject", "https://example.com/ok2"))
        .when().put(BASE + "/" + NAME).then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get(BASE + "/" + NAME)
        .then().statusCode(200)
                .body("TemplateSubject", equalTo("Updated subject"))
                .body("SuccessRedirectionURL", equalTo("https://example.com/ok2"));
    }

    @Test
    @Order(6)
    void updateMissing_throwsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(body("nope", FROM, "x", "https://example.com/ok"))
        .when().put(BASE + "/nope").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void deleteThenGone() {
        given().header("Authorization", AUTH)
        .when().delete(BASE + "/" + NAME).then().statusCode(200);
        given().header("Authorization", AUTH)
        .when().get(BASE + "/" + NAME).then().statusCode(404);
    }

    @Test
    @Order(8)
    void deleteMissing_throwsNotFound() {
        given().header("Authorization", AUTH)
        .when().delete(BASE + "/nope").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(9)
    void createWithUnverifiedFrom_throwsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(body("cvet-unverified", "nobody@unverified.test", "x", "https://example.com/ok"))
        .when().post(BASE).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("The from email address <nobody@unverified.test> is not verified"));
    }

    @Test
    @Order(10)
    void createWithInvalidRedirectUrl_throwsBadRequest() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body(body("cvet-badurl", FROM, "x", "not-a-url"))
        .when().post(BASE).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("The success redirection URL is invalid"));
    }
}
