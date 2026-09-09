package io.github.hectorvent.floci.services.ses;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for SES V2 email template CRUD and templated send
 * via the REST JSON protocol at /v2/email/templates.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTemplateV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "v2-welcome",
                  "TemplateContent": {
                    "Subject": "Hello {{name}}",
                    "Text": "Hi {{name}}!",
                    "Html": "<p>Hi <b>{{name}}</b>!</p>"
                  }
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createTemplate_duplicateRejected() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "v2-welcome",
                  "TemplateContent": {"Subject": "dup", "Text": "dup"}
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"));
    }

    @Test
    @Order(3)
    void createTemplate_missingName() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateContent": {"Subject": "x"}}
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void getTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/v2-welcome")
        .then()
            .statusCode(200)
            .body("TemplateName", equalTo("v2-welcome"))
            .body("TemplateContent.Subject", equalTo("Hello {{name}}"))
            .body("TemplateContent.Text", equalTo("Hi {{name}}!"))
            .body("TemplateContent.Html", containsString("{{name}}"));
    }

    @Test
    @Order(5)
    void getTemplate_notFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/does-not-exist")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(6)
    void updateTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateContent": {
                    "Subject": "Welcome {{name}}!",
                    "Text": "Hello {{name}}, from {{team}}",
                    "Html": "<p>Welcome {{name}}</p>"
                  }
                }
                """)
        .when()
            .put("/v2/email/templates/v2-welcome")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/v2-welcome")
        .then()
            .statusCode(200)
            .body("TemplateContent.Subject", equalTo("Welcome {{name}}!"))
            .body("TemplateContent.Text", containsString("{{team}}"));
    }

    @Test
    @Order(7)
    void updateTemplate_notFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"TemplateContent": {"Subject": "x"}}
                """)
        .when()
            .put("/v2/email/templates/ghost")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(8)
    void listTemplates_includesCreated() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates")
        .then()
            .statusCode(200)
            .body("TemplatesMetadata", notNullValue())
            .body("TemplatesMetadata.TemplateName", hasItem("v2-welcome"));
    }

    @Test
    @Order(9)
    void sendEmail_withTemplate_substitutesVariables() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        String messageId = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateName": "v2-welcome",
                      "TemplateData": "{\\"name\\":\\"Alice\\",\\"team\\":\\"floci\\"}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue())
            .extract().path("MessageId");

        given()
            .queryParam("id", messageId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Welcome Alice!"))
            .body("messages[0].Body.text_part", equalTo("Hello Alice, from floci"))
            .body("messages[0].Body.html_part", equalTo("<p>Welcome Alice</p>"));
    }

    @Test
    @Order(10)
    void sendEmail_withUnknownTemplate_returns404() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateName": "ghost",
                      "TemplateData": "{}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(11)
    void sendEmail_withTemplate_missingName_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {"Template": {"TemplateData": "{}"}}
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(12)
    void deleteTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/templates/v2-welcome")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/v2-welcome")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void deleteTemplate_notFound() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/templates/already-gone")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(14)
    void createTemplate_rejectsLeadingTrailingWhitespace() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": " padded ",
                  "TemplateContent": {"Subject": "s", "Text": "t"}
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(15)
    void sendEmail_withInlineTemplate_substitutesVariables() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "inline-sender@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        String messageId = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "inline-sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateContent": {
                        "Subject": "Inline {{name}}",
                        "Text": "Hello inline {{name}} on {{team}}",
                        "Html": "<p>Hello inline <b>{{name}}</b></p>"
                      },
                      "TemplateData": "{\\"name\\":\\"Alice\\",\\"team\\":\\"floci\\"}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue())
            .extract().path("MessageId");

        given()
            .queryParam("id", messageId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Inline Alice"))
            .body("messages[0].Body.text_part", equalTo("Hello inline Alice on floci"))
            .body("messages[0].Body.html_part", equalTo("<p>Hello inline <b>Alice</b></p>"));
    }

    @Test
    @Order(16)
    void sendEmail_templateWithBothNameAndContent_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "inline-sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateName": "anything",
                      "TemplateContent": {"Subject": "s", "Text": "t"},
                      "TemplateData": "{}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(17)
    void sendEmail_withTemplateArn_resolvesStoredTemplate() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "arn-welcome",
                  "TemplateContent": {
                    "Subject": "Hi {{name}}",
                    "Text": "Hello {{name}}"
                  }
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        String messageId = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "inline-sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateArn": "arn:aws:ses:us-east-1:000000000000:template/arn-welcome",
                      "TemplateData": "{\\"name\\":\\"Alice\\"}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue())
            .extract().path("MessageId");

        given()
            .queryParam("id", messageId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Hi Alice"))
            .body("messages[0].Body.text_part", equalTo("Hello Alice"));
    }

    @Test
    @Order(18)
    void sendEmail_templateWithMalformedArn_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "inline-sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateArn": "not-an-arn",
                      "TemplateData": "{}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(19)
    void sendEmail_templateWithNameAndArn_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "inline-sender@example.com",
                  "Destination": {"ToAddresses": ["recipient@example.com"]},
                  "Content": {
                    "Template": {
                      "TemplateName": "arn-welcome",
                      "TemplateArn": "arn:aws:ses:us-east-1:000000000000:template/arn-welcome",
                      "TemplateData": "{}"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    // ──────────────── Cross-region isolation ────────────────

    private static final String AUTH_EU_WEST_1 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-1/ses/aws4_request";

    @Test
    @Order(20)
    void crossRegionIsolation_templateNotVisibleInOtherRegion() {
        // Create template in eu-west-1
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_EU_WEST_1)
            .body("""
                {
                  "TemplateName": "region-test",
                  "TemplateContent": {
                    "Subject": "Regional",
                    "Text": "Hello from eu-west-1"
                  }
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        // Verify it exists in eu-west-1
        given()
            .header("Authorization", AUTH_EU_WEST_1)
        .when()
            .get("/v2/email/templates/region-test")
        .then()
            .statusCode(200)
            .body("TemplateName", equalTo("region-test"));

        // Verify it does NOT exist in us-east-1
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/region-test")
        .then()
            .statusCode(404);

        // Clean up
        given()
            .header("Authorization", AUTH_EU_WEST_1)
        .when()
            .delete("/v2/email/templates/region-test")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void createEmailTemplate_withTags_visibleViaGet() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "tpl-with-tags",
                  "TemplateContent": {
                    "Subject": "S",
                    "Text": "T"
                  },
                  "Tags": [
                    {"Key": "env", "Value": "stg"},
                    {"Key": "team", "Value": "platform"}
                  ]
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/tpl-with-tags")
        .then()
            .statusCode(200)
            .body("TemplateName", equalTo("tpl-with-tags"))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("stg"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));
    }

    @Test
    @Order(22)
    void createEmailTemplate_withTags_visibleViaListTagsForResource() {
        // tpl-with-tags is created in @Order(21) above
        String arn = "arn:aws:ses:us-east-1:000000000000:template/tpl-with-tags";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("stg"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));
    }

    @Test
    @Order(23)
    void updateEmailTemplate_preservesTags() {
        // tpl-with-tags has 2 tags from @Order(21)
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateContent": {
                    "Subject": "S2",
                    "Text": "T2"
                  }
                }
                """)
        .when()
            .put("/v2/email/templates/tpl-with-tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/tpl-with-tags")
        .then()
            .statusCode(200)
            .body("TemplateContent.Subject", equalTo("S2"))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("stg"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));
    }

    @Test
    @Order(24)
    void createTemplate_invalidTags_returns400_andNotPersisted() {
        // Route-level coverage: CreateEmailTemplate must reject an invalid tag set (here duplicate
        // keys) atomically, before the template is persisted.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "v2-badtags",
                  "TemplateContent": {"Subject": "S", "Text": "T"},
                  "Tags": [{"Key": "dup", "Value": "1"}, {"Key": "dup", "Value": "2"}]
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Cannot provide multiple tags with the same key"));

        // The failed create must leave no template behind.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/templates/v2-badtags")
        .then()
            .statusCode(404);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedSendEmailBodies")
    @Order(50)
    void sendEmail_malformedShape_returnsBadRequest(String label, String body) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/v2/email/outbound-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    static Stream<Arguments> malformedSendEmailBodies() {
        return Stream.of(
                Arguments.of("body is array", "[1,2,3]"),
                Arguments.of("body is JSON null literal", "null"),
                Arguments.of("body is JSON string", "\"hello\""),
                Arguments.of("Destination as string", """
                    {
                      "FromEmailAddress": "sender@example.com",
                      "Destination": "bad",
                      "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "t"}}}}
                    }
                    """),
                Arguments.of("TemplateData as object", """
                    {
                      "FromEmailAddress": "sender@example.com",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "Content": {
                        "Template": {"TemplateName": "any", "TemplateData": {"name": "Alice"}}
                      }
                    }
                    """),
                Arguments.of("TemplateData as array", """
                    {
                      "FromEmailAddress": "sender@example.com",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "Content": {
                        "Template": {"TemplateName": "any", "TemplateData": [1,2,3]}
                      }
                    }
                    """),
                Arguments.of("TemplateData as invalid JSON string", """
                    {
                      "FromEmailAddress": "sender@example.com",
                      "Destination": {"ToAddresses": ["recipient@example.com"]},
                      "Content": {
                        "Template": {"TemplateName": "any", "TemplateData": "{not json"}
                      }
                    }
                    """)
        );
    }

}
