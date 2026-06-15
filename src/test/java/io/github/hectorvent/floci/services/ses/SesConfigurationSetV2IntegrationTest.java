package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Integration tests for SES V2 ConfigurationSet endpoints under /v2/email/configuration-sets.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createConfigurationSet() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-alpha",
                  "Tags": [{"Key": "env", "Value": "test"}]
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createConfigurationSet_duplicateRejected() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "v2-cs-alpha"}
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"));
    }

    @Test
    @Order(3)
    void createConfigurationSet_tagsNotArray() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-bad-tags",
                  "Tags": "not-an-array"
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void createConfigurationSet_missingName() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(5)
    void getConfigurationSet_returnsRoundTrip() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-alpha")
        .then()
            .statusCode(200)
            .body("ConfigurationSetName", equalTo("v2-cs-alpha"))
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("test"));
    }

    @Test
    @Order(6)
    void getConfigurationSet_unknownReturns404() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-ghost")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void listConfigurationSets() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "v2-cs-beta"}
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets")
        .then()
            .statusCode(200)
            .body("ConfigurationSets", hasItem("v2-cs-alpha"))
            .body("ConfigurationSets", hasItem("v2-cs-beta"));
    }

    @Test
    @Order(8)
    void deleteConfigurationSet() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/configuration-sets/v2-cs-alpha")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-alpha")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(9)
    void deleteConfigurationSet_unknownReturns404() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/configuration-sets/v2-cs-ghost")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(10)
    void createConfigurationSet_invalidNameCharacters() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "bad name!"}
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(11)
    void createConfigurationSet_nameTooLong() {
        String longName = "a".repeat(65);
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "%s"}
                """.formatted(longName))
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(12)
    void createConfigurationSet_tagWithMissingValue_roundTripsAsAbsent() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-tag-no-value",
                  "Tags": [{"Key": "env"}]
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-tag-no-value")
        .then()
            .statusCode(200)
            .body("Tags[0].Key", equalTo("env"));
    }

    @Test
    @Order(13)
    void createConfigurationSet_tagWithMissingKey_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-bad-tag-key",
                  "Tags": [{"Value": "v"}]
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(14)
    void createConfigurationSet_tagKeyTooLong() {
        String longKey = "k".repeat(129);
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-long-tag-key",
                  "Tags": [{"Key": "%s", "Value": "v"}]
                }
                """.formatted(longKey))
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(15)
    void createConfigurationSet_tagValueTooLong() {
        String longValue = "v".repeat(257);
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-long-tag-value",
                  "Tags": [{"Key": "k", "Value": "%s"}]
                }
                """.formatted(longValue))
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(16)
    void listTagsForResource_returnsTagsSetAtCreation() {
        // Tags supplied to CreateConfigurationSet must also be reachable through
        // the ListTagsForResource endpoint, not just GET configuration-sets/{name}.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-tag-roundtrip",
                  "Tags": [
                    {"Key": "team", "Value": "platform"},
                    {"Key": "env", "Value": "stg"}
                  ]
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/v2-cs-tag-roundtrip";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("stg"));
    }

    @Test
    @Order(17)
    void createConfigurationSet_inlineOptions_echoedOnGet() {
        // AWS echoes inline options set at create time verbatim
        // (verified against real AWS SES V2 on 2026-06-12).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-inline-options",
                  "SendingOptions": {"SendingEnabled": false},
                  "SuppressionOptions": {"SuppressedReasons": ["BOUNCE"]}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-inline-options")
        .then()
            .statusCode(200)
            .body("SendingOptions.SendingEnabled", equalTo(false))
            .body("SuppressionOptions.SuppressedReasons", hasSize(1))
            .body("SuppressionOptions.SuppressedReasons", hasItem("BOUNCE"));
    }

    @Test
    @Order(18)
    void createConfigurationSet_invalidSuppressionReason_rejectedWithoutCreating() {
        // Unlike PutConfigurationSetSuppressionOptions, AWS reports the
        // constraint-style validation message on this endpoint, even for
        // multiple invalid values (verified against real AWS SES V2 on
        // 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-bad-reason",
                  "SuppressionOptions": {"SuppressedReasons": ["INVALID"]}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'suppressionOptions.suppressedReasons' failed to satisfy constraint: "
                    + "Member must satisfy constraint: "
                    + "[Member must satisfy enum value set: [BOUNCE, COMPLAINT]]"));

        // Validation happens before the store write, so the set must not exist.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-bad-reason")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(19)
    void createConfigurationSet_stringSendingEnabled_coercesToTrue() {
        // AWS accepts any string for SendingEnabled and stores true
        // (verified against real AWS SES V2 on 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-string-sending",
                  "SendingOptions": {"SendingEnabled": "yes"}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-string-sending")
        .then()
            .statusCode(200)
            .body("SendingOptions.SendingEnabled", equalTo(true));
    }

    @Test
    @Order(20)
    void createConfigurationSet_emptySendingOptions_defaultsToFalse() {
        // AWS treats a missing SendingEnabled member inside a present
        // SendingOptions block as false, unlike a fully absent block which
        // defaults to true (verified against real AWS SES V2 on 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-empty-sending",
                  "SendingOptions": {}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-empty-sending")
        .then()
            .statusCode(200)
            .body("SendingOptions.SendingEnabled", equalTo(false));
    }

    @Test
    @Order(21)
    void createConfigurationSet_nullSendingEnabled_serializationError() {
        // Explicit null fails AWS deserialization with a null message and the
        // set is not created (verified against real AWS SES V2 on 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-null-sending",
                  "SendingOptions": {"SendingEnabled": null}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-null-sending")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(22)
    void createConfigurationSet_numberSendingEnabled_serializationError() {
        // Verified against real AWS SES V2 on 2026-06-13.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-number-sending",
                  "SendingOptions": {"SendingEnabled": 1}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("NUMBER_VALUE can not be converted to a Boolean"));
    }

    @Test
    @Order(23)
    void createConfigurationSet_missingSuppressedReasons_internalFailure() {
        // AWS itself returns 500 InternalFailure when SuppressionOptions is
        // present without SuppressedReasons, and the set is not created;
        // reproduced faithfully (verified against real AWS SES V2 on
        // 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-empty-suppression",
                  "SuppressionOptions": {}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(500)
            .body("__type", equalTo("InternalFailure"))
            .body("message", equalTo("An internal failure has occurred."));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-empty-suppression")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(24)
    void createConfigurationSet_nonArraySuppressedReasons_serializationError() {
        // Verified against real AWS SES V2 on 2026-06-13.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-string-reasons",
                  "SuppressionOptions": {"SuppressedReasons": "BOUNCE"}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("Expected list or null"));
    }

    @Test
    @Order(25)
    void createConfigurationSet_emptySuppressedReasonsList_echoedOnGet() {
        // An explicit empty list is stored and echoed as-is
        // (verified against real AWS SES V2 on 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-empty-reasons",
                  "SuppressionOptions": {"SuppressedReasons": []}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-empty-reasons")
        .then()
            .statusCode(200)
            .body("SuppressionOptions.SuppressedReasons", hasSize(0));
    }

    @Test
    @Order(26)
    void createConfigurationSet_nullSuppressedReason_rejectedWithPutStyleMessage() {
        // A null element passes AWS deserialization ("Expected list or null")
        // and fails value validation with the natural-language sentence, not
        // the constraint-style message used for invalid non-null values
        // (verified against real AWS SES V2 on 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-null-reason",
                  "SuppressionOptions": {"SuppressedReasons": ["BOUNCE", null]}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Reason null is invalid, must be one of [BOUNCE, COMPLAINT]."));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-null-reason")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(27)
    void createConfigurationSet_booleanSuppressedReason_serializationError() {
        // Verified against real AWS SES V2 on 2026-06-13.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-boolean-reason",
                  "SuppressionOptions": {"SuppressedReasons": [false]}
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("FALSE_VALUE can not be converted to a String"));
    }

    @Test
    @Order(28)
    void createConfigurationSet_nonObjectOptionBlocks_serializationError() {
        // "Expected null" looks odd but is the verbatim AWS response for a
        // non-object option block (verified against real AWS SES V2 on
        // 2026-06-13).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-string-suppression",
                  "SuppressionOptions": "nope"
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("Expected null"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "ConfigurationSetName": "v2-cs-string-options",
                  "SendingOptions": "nope"
                }
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("Expected null"));
    }
}
