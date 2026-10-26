package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
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
    // Senders of their own: an identity under example.com would outlive this class and reach the
    // other SES classes that share the application.
    private static final List<String> SENDERS =
            List.of("bulk-v2@floci.test", "bulk-v2-second@floci.test", "bulk-v2-inline@floci.test");

    private static final String DESTINATION_COUNT_MESSAGE =
            "You must specify at least 1 destination, and no more than 50 destinations";

    @Test
    @Order(1)
    void createTemplate() {
        for (String sender : SENDERS) {
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("{\"EmailIdentity\": \"" + sender + "\"}")
            .when()
                .post("/v2/email/identities")
            .then()
                .statusCode(200);
        }
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
                  "FromEmailAddress": "bulk-v2@floci.test",
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
                  "FromEmailAddress": "bulk-v2-second@floci.test",
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
                  "FromEmailAddress": "bulk-v2-inline@floci.test",
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
                  "FromEmailAddress": "bulk-v2@floci.test",
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
                  "FromEmailAddress": "bulk-v2@floci.test",
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
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(DESTINATION_COUNT_MESSAGE));
    }

    @Test
    @Order(6)
    void sendBulkEmail_missingTemplate_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk-v2@floci.test",
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
    void sendBulkEmail_entriesExceeds50_returnsBadRequest() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk-v2@floci.test",
                  "DefaultContent": {
                    "Template": {"TemplateName": "v2-bulk-welcome"}
                  },
                  "BulkEmailEntries": [%s]
                }
                """.formatted(entries(51)))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(DESTINATION_COUNT_MESSAGE));
    }

    @Test
    @Order(10)
    void sendBulkEmail_perEntryMissingVariable_mapsToInvalidParameter() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk-v2@floci.test",
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
    @MethodSource("io.github.hectorvent.floci.services.ses.SesV2SerializationErrors#nonObjectBodies")
    @Order(12)
    void sendBulkEmail_nonObjectBody_isASerializationException(String body, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo(message));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTypeSendBulkEmailBodies")
    @Order(13)
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
                Arguments.of("BulkEmailEntries element is null", """
                    {
                      "FromEmailAddress": "bulk-v2@floci.test",
                      %s,
                      "BulkEmailEntries": [null]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("DefaultTemplateData as invalid JSON string", """
                    {
                      "FromEmailAddress": "bulk-v2@floci.test",
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
                      "FromEmailAddress": "bulk-v2@floci.test",
                      %s,
                      "BulkEmailEntries": ["bad"]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("Destination as string", """
                    {
                      "FromEmailAddress": "bulk-v2@floci.test",
                      %s,
                      "BulkEmailEntries": [{"Destination": "bad"}]
                    }
                    """.formatted(validDefaultTemplate)),
                Arguments.of("DefaultTemplateData as object", """
                    {
                      "FromEmailAddress": "bulk-v2@floci.test",
                      "DefaultContent": {
                        "Template": {"TemplateName": "v2-bulk-welcome", "TemplateData": {"team": "floci"}}
                      },
                      "BulkEmailEntries": [{"Destination": {"ToAddresses": ["alice@example.com"]}}]
                    }
                    """),
                Arguments.of("per-entry ReplacementTemplateData as object", """
                    {
                      "FromEmailAddress": "bulk-v2@floci.test",
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
                      "FromEmailAddress": "bulk-v2@floci.test",
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
                      "FromEmailAddress": "bulk-v2@floci.test",
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
                      "FromEmailAddress": "bulk-v2@floci.test",
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
                  "FromEmailAddress": "bulk-v2@floci.test",
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

    @Test
    @Order(14)
    void sendBulkEmail_fiftyEntries_sendsEachEntry() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk-v2@floci.test",
                  "DefaultContent": {
                    "Template": {
                      "TemplateName": "v2-bulk-welcome",
                      "TemplateData": "{\\"name\\":\\"X\\",\\"team\\":\\"floci\\"}"
                    }
                  },
                  "BulkEmailEntries": [%s]
                }
                """.formatted(entries(50)))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults", hasSize(50))
            .body("BulkEmailEntryResults.Status", everyItem(equalTo("SUCCESS")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingEntriesMembers")
    @Order(15)
    void sendBulkEmail_missingEntries_isAModelViolation(String label, String entriesMember) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk@example.com",
                  "DefaultContent": {
                    "Template": {"TemplateName": "v2-bulk-welcome"}
                  }%s
                }
                """.formatted(entriesMember))
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at 'bulkEmailEntries' "
                    + "failed to satisfy constraint: Member must not be null"));
    }

    static Stream<Arguments> missingEntriesMembers() {
        return Stream.of(
                Arguments.of("absent", ""),
                Arguments.of("null", ", \"BulkEmailEntries\": null"));
    }

    // AWS counts the entries before it resolves the stored template, the configuration set and the
    // tenant, each of which would otherwise answer 404 (probed 2026-10-24).
    @ParameterizedTest(name = "{0}")
    @MethodSource("countBeforeLookupBodies")
    @Order(16)
    void sendBulkEmail_entryCountIsCheckedBeforeLookups(String label, String body) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(DESTINATION_COUNT_MESSAGE));
    }

    static Stream<Arguments> countBeforeLookupBodies() {
        String inline = "\"DefaultContent\": {\"Template\": {\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"t\"}}}";
        return Stream.of(
                Arguments.of("ghost stored template", """
                    {"FromEmailAddress": "bulk@example.com",
                     "DefaultContent": {"Template": {"TemplateName": "ghost-template"}},
                     "BulkEmailEntries": [%s]}
                    """.formatted(entries(51))),
                Arguments.of("ghost stored template by ARN", """
                    {"FromEmailAddress": "bulk@example.com",
                     "DefaultContent": {"Template": {
                       "TemplateArn": "arn:aws:ses:us-east-1:000000000000:template/ghost-template"}},
                     "BulkEmailEntries": [%s]}
                    """.formatted(entries(51))),
                Arguments.of("ghost configuration set", """
                    {"FromEmailAddress": "bulk@example.com", "ConfigurationSetName": "ghost-cs", %s,
                     "BulkEmailEntries": [%s]}
                    """.formatted(inline, entries(51))),
                Arguments.of("ghost tenant", """
                    {"FromEmailAddress": "bulk@example.com", "TenantName": "ghost-tenant", %s,
                     "BulkEmailEntries": [%s]}
                    """.formatted(inline, entries(51))),
                Arguments.of("empty entries with a ghost tenant", """
                    {"FromEmailAddress": "bulk@example.com", "TenantName": "ghost-tenant", %s,
                     "BulkEmailEntries": []}
                    """.formatted(inline)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checkedBeforeCountBodies")
    @Order(17)
    void sendBulkEmail_earlierChecksWinOverEntryCount(String label, String body, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    static Stream<Arguments> checkedBeforeCountBodies() {
        return Stream.of(
                Arguments.of("malformed From", """
                    {"FromEmailAddress": "not an address",
                     "DefaultContent": {"Template": {"TemplateContent": {"Subject": "s", "Text": "t"}}},
                     "BulkEmailEntries": [%s]}
                    """.formatted(entries(51)), "Invalid email address<not an address>."),
                Arguments.of("empty inline content", """
                    {"FromEmailAddress": "bulk@example.com",
                     "DefaultContent": {"Template": {"TemplateContent": {}}},
                     "BulkEmailEntries": [%s]}
                    """.formatted(entries(51)), "Template must have at least a subject, text, or html part."));
    }

    // An entry with no recipient beside one with a recipient fails on its own (probed 2026-10-25).
    @Test
    @Order(18)
    void sendBulkEmail_emptyEntryBesideRecipients_failsOnlyThatEntry() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "bulk-v2-inline@floci.test",
                  "DefaultContent": {"Template": {"TemplateContent": {"Subject": "s", "Text": "t"}}},
                  "BulkEmailEntries": [
                    {"Destination": {"ToAddresses": ["alice@example.com"]}},
                    {"Destination": {"ToAddresses": []}}
                  ]
                }
                """)
        .when()
            .post("/v2/email/outbound-bulk-emails")
        .then()
            .statusCode(200)
            .body("BulkEmailEntryResults", hasSize(2))
            .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"))
            .body("BulkEmailEntryResults[1].Status", equalTo("INVALID_PARAMETER"))
            .body("BulkEmailEntryResults[1].Error", equalTo("Missing required header 'To'."));
    }

    private static String entries(int count) {
        StringBuilder entries = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                entries.append(",");
            }
            entries.append("{\"Destination\":{\"ToAddresses\":[\"user")
                    .append(i).append("@example.com\"]}}");
        }
        return entries.toString();
    }
}
