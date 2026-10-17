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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for SES V2 SendBulkEmail at /v2/email/outbound-bulk-emails.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesBulkV2IntegrationTest {

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
                  "TemplateName": "v2-bulk-welcome",
                  "TemplateContent": {
                    "Subject": "Hello {{name}}",
                    "Text": "Hi {{name}}, team {{team}}!",
                    "Html": "<p>Hi <b>{{name}}</b> ({{team}})</p>"
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
    void sendBulkEmail_storedTemplate_perEntryReplacement() {
        String firstId = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {
                      "TemplateName": "v2-bulk-welcome",
                      "TemplateData": "{\\"team\\":\\"floci\\"}"
                    }
                  },
                  "BulkEmailEntries": [
                    {
                      "Destination": {"ToAddresses": ["alice@example.com"]},
                      "ReplacementEmailContent": {
                        "ReplacementTemplate": {
                          "ReplacementTemplateData": "{\\"name\\":\\"Alice\\"}"
                        }
                      }
                    },
                    {
                      "Destination": {"ToAddresses": ["bob@example.com"]},
                      "ReplacementEmailContent": {
                        "ReplacementTemplate": {
                          "ReplacementTemplateData": "{\\"name\\":\\"Bob\\",\\"team\\":\\"override\\"}"
                        }
                      }
                    }
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults", hasSize(2))
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"))
            .body("BulkEmailEntryResults[0].MessageId", notNullValue())
            .body("BulkEmailEntryResults[1].Status", equalTo("SUCCESS"))
            .body("BulkEmailEntryResults[1].MessageId", notNullValue())
            .extract().path("BulkEmailEntryResults[0].MessageId");

        String secondId = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk2@example.com",
                  "DefaultContent": {
                    "Template": {
                      "TemplateName": "v2-bulk-welcome",
                      "TemplateData": "{\\"team\\":\\"floci\\"}"
                    }
                  },
                  "BulkEmailEntries": [
                    {
                      "Destination": {"ToAddresses": ["carol@example.com"]},
                      "ReplacementEmailContent": {
                        "ReplacementTemplate": {
                          "ReplacementTemplateData": "{\\"name\\":\\"Carol\\"}"
                        }
                      }
                    }
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults", hasSize(1))
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"))
            .extract().path("BulkEmailEntryResults[0].MessageId");

        given()
            .queryParam("id", firstId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Hello Alice"))
            .body("messages[0].Body.text_part", equalTo("Hi Alice, team floci!"));

        given()
            .queryParam("id", secondId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Hello Carol"));
    }

    @Test
    @Order(3)
    void sendBulkEmail_inlineTemplateContent() {
        String messageId = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "inline@example.com",
                  "DefaultContent": {
                    "Template": {
                      "TemplateContent": {
                        "Subject": "Inline {{name}}",
                        "Text": "Body for {{name}}"
                      },
                      "TemplateData": "{\\"name\\":\\"Default\\"}"
                    }
                  },
                  "BulkEmailEntries": [
                    {
                      "Destination": {"ToAddresses": ["dora@example.com"]},
                      "ReplacementEmailContent": {
                        "ReplacementTemplate": {
                          "ReplacementTemplateData": "{\\"name\\":\\"Dora\\"}"
                        }
                      }
                    }
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"))
            .extract().path("BulkEmailEntryResults[0].MessageId");

        given()
            .queryParam("id", messageId)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages[0].Subject", equalTo("Inline Dora"))
            .body("messages[0].Body.text_part", equalTo("Body for Dora"));
    }

    @Test
    @Order(4)
    void sendBulkEmail_unknownTemplate_returns404() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {"TemplateName": "ghost"}
                  },
                  "BulkEmailEntries": [
                    {"Destination": {"ToAddresses": ["alice@example.com"]}}
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(5)
    void sendBulkEmail_emptyEntries_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {"TemplateName": "v2-bulk-welcome"}
                  },
                  "BulkEmailEntries": []
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(6)
    void sendBulkEmail_missingTemplate_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {},
                  "BulkEmailEntries": [
                    {"Destination": {"ToAddresses": ["alice@example.com"]}}
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void sendBulkEmail_entriesExceeds50_returnsMessageRejected() {
        StringBuilder entries = new StringBuilder();
        for (int i = 1; i <= 51; i++) {
            if (i > 1) entries.append(",");
            entries.append("{\"Destination\":{\"ToAddresses\":[\"user")
                    .append(i).append("@example.com\"]}}");
        }
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {"TemplateName": "v2-bulk-welcome"}
                  },
                  "BulkEmailEntries": [%s]
                }
                """.formatted(entries))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
    }

    @Test
    @Order(10)
    void sendBulkEmail_perEntryMissingVariable_mapsToInvalidParameter() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {"TemplateName": "v2-bulk-welcome", "TemplateData": "{\\"team\\":\\"floci\\"}"}
                  },
                  "BulkEmailEntries": [
                    {
                      "Destination": {"ToAddresses": ["alice@example.com"]},
                      "ReplacementEmailContent": {
                        "ReplacementTemplate": {"ReplacementTemplateData": "{}"}
                      }
                    }
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults[0].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[0].Error", org.hamcrest.Matchers.containsString("name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedSendBulkEmailBodies")
    @Order(11)
    void sendBulkEmail_malformedShape_returns400(String label, String body) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTypeSendBulkEmailBodies")
    @Order(12)
    void sendBulkEmail_wrongMemberType_returnsSerializationException(String label, String body) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    static Stream<Arguments> malformedSendBulkEmailBodies() {
        String validDefaultTemplate = "\"DefaultContent\": {\"Template\": {\"TemplateName\": \"v2-bulk-welcome\", \"TemplateData\": \"{\\\"team\\\":\\\"floci\\\",\\\"name\\\":\\\"X\\\"}\"}}";
        return Stream.of(
                Arguments.of("body is array", "[1,2,3]"),
                Arguments.of("body is JSON null literal", "null"),
                Arguments.of("body is JSON string", "\"hello\""),
                Arguments.of("BulkEmailEntries element is null", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      %s,
                      "BulkEmailEntries": [null]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("DefaultTemplateData as invalid JSON string", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      "DefaultContent": {
                        "Template": {"TemplateName": "v2-bulk-welcome", "TemplateData": "{not json"}
                      },
                      "BulkEmailEntries": [{"Destination": {"ToAddresses": ["alice@example.com"]}}]
                    }
                    """)
        );
    }

    static Stream<Arguments> wrongTypeSendBulkEmailBodies() {
        String validDefaultTemplate = "\"DefaultContent\": {\"Template\": {\"TemplateName\": \"v2-bulk-welcome\", \"TemplateData\": \"{\\\"team\\\":\\\"floci\\\",\\\"name\\\":\\\"X\\\"}\"}}";
        return Stream.of(
                Arguments.of("BulkEmailEntries element is string", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      %s,
                      "BulkEmailEntries": ["bad"]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("Destination as string", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      %s,
                      "BulkEmailEntries": [{"Destination": "bad"}]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("DefaultTemplateData as object", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      "DefaultContent": {
                        "Template": {"TemplateName": "v2-bulk-welcome", "TemplateData": {"team": "floci"}}
                      },
                      "BulkEmailEntries": [{"Destination": {"ToAddresses": ["alice@example.com"]}}]
                    }
                    """),
                Arguments.of("per-entry ReplacementTemplateData as object", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      %s,
                      "BulkEmailEntries": [
                        {
                          "Destination": {"ToAddresses": ["alice@example.com"]},
                          "ReplacementEmailContent": {
                            "ReplacementTemplate": {"ReplacementTemplateData": {"name": "Alice"}}
                          }
                        }
                      ]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("ReplacementEmailContent as string", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      %s,
                      "BulkEmailEntries": [
                        {
                          "Destination": {"ToAddresses": ["alice@example.com"]},
                          "ReplacementEmailContent": "not-an-object"
                        }
                      ]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("ReplacementEmailContent as array", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      %s,
                      "BulkEmailEntries": [
                        {
                          "Destination": {"ToAddresses": ["alice@example.com"]},
                          "ReplacementEmailContent": [1,2,3]
                        }
                      ]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("ReplacementTemplate as array", """
                    {
                      "FromEmailAddress": "bulk@example.com",
                      "DefaultContent": {
                        "Template": {"TemplateName": "v2-bulk-welcome", "TemplateData": "{\\"team\\":\\"floci\\"}"}
                      },
                      "BulkEmailEntries": [
                        {
                          "Destination": {"ToAddresses": ["alice@example.com"]},
                          "ReplacementEmailContent": {"ReplacementTemplate": [1,2,3]}
                        }
                      ]
                    }
                    """)
        );
    }

    @Test
    @Order(8)
    void sendBulkEmail_recipientsExceeds50_returnsMessageRejected() {
        StringBuilder addrs = new StringBuilder();
        for (int i = 1; i <= 51; i++) {
            if (i > 1) addrs.append(",");
            addrs.append("\"user").append(i).append("@example.com\"");
        }
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {"TemplateName": "v2-bulk-welcome"}
                  },
                  "BulkEmailEntries": [
                    {"Destination": {"ToAddresses": [%s]}}
                  ]
                }
                """.formatted(addrs))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("MessageRejected"));
    }
}
