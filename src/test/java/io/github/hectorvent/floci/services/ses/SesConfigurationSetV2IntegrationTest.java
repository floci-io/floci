package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for SES V2 ConfigurationSet endpoints under /v2/email/configuration-sets.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    private static void verifyDomainIdentityViaRoute53(String domain, String callerReference) {
        List<String> tokens = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"EmailIdentity\": \"" + domain + "\"}")
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("DkimAttributes.Tokens", String.class);

        String locationHeader = given()
            .contentType("application/xml")
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>floci.test</Name>
                  <CallerReference>""" + callerReference + """
                  </CallerReference>
                </CreateHostedZoneRequest>
                """)
        .when()
            .post("/2013-04-01/hostedzone")
        .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String zoneId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        StringBuilder body = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                """);
        for (String token : tokens) {
            body.append("""
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>""").append(token).append("._domainkey.").append(domain).append(".</Name>\n")
                    .append("""
                          <Type>CNAME</Type>
                          <TTL>300</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>""").append(token).append(".dkim.amazonses.com.</Value></ResourceRecord>\n")
                    .append("""
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    """);
        }
        body.append("""
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """);

        given()
            .contentType("application/xml")
            .body(body.toString())
        .when()
            .post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then()
            .statusCode(200);
    }

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

    // ─────────── Reputation / Tracking / Delivery / Archiving options ───────────
    // Behavior verified against real AWS SES V2 on 2026-06-17.

    @Test
    @Order(30)
    void getConfigurationSet_reputationOptions_defaultsEnabled() {
        putConfigSet("v2-cs-rep-default");
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-rep-default")
        .then().statusCode(200)
            .body("ReputationOptions.ReputationMetricsEnabled", equalTo(true));
    }

    @Test
    @Order(31)
    void putReputationOptions_disables_andEchoes() {
        putConfigSet("v2-cs-rep");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ReputationMetricsEnabled\": false}")
        .when().put("/v2/email/configuration-sets/v2-cs-rep/reputation-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-rep")
        .then().statusCode(200)
            .body("ReputationOptions.ReputationMetricsEnabled", equalTo(false));
    }

    @Test
    @Order(32)
    void createConfigurationSet_inlineReputation_echoed() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "v2-cs-rep-inline",
                 "ReputationOptions": {"ReputationMetricsEnabled": false}}
                """)
        .when().post("/v2/email/configuration-sets").then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-rep-inline")
        .then().statusCode(200)
            .body("ReputationOptions.ReputationMetricsEnabled", equalTo(false));
    }

    @Test
    @Order(33)
    void putDeliveryOptions_echoed() {
        putConfigSet("v2-cs-delivery");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"REQUIRE\", \"MaxDeliverySeconds\": 300}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery/delivery-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-delivery")
        .then().statusCode(200)
            .body("DeliveryOptions.TlsPolicy", equalTo("REQUIRE"))
            .body("DeliveryOptions.MaxDeliverySeconds", equalTo(300));
    }

    @Test
    @Order(34)
    void putDeliveryOptions_nonexistentPool_returns400() {
        putConfigSet("v2-cs-delivery-pool");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"OPTIONAL\", \"SendingPoolName\": \"ghost-pool\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-pool/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("SendingPool <ghost-pool> doesn't exist"));
    }

    @Test
    @Order(35)
    void putArchivingOptions_validArn_echoed() {
        putConfigSet("v2-cs-archiving");
        String arn = "arn:aws:ses:us-east-1:123456789012:mailmanager-archive/a-abcdefghijklmnopqrstuvwx";
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ArchiveArn\": \"" + arn + "\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-archiving/archiving-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-archiving")
        .then().statusCode(200)
            .body("ArchivingOptions.ArchiveArn", equalTo(arn));
    }

    @Test
    @Order(36)
    void putArchivingOptions_arbitraryArn_accepted() {
        // Floci does not model Mail Manager archives, so the ArchiveArn is not
        // validated — any value is stored and echoed (matching the store-only
        // behaviour of other local AWS emulators).
        String arn = "not-an-arn";
        putConfigSet("v2-cs-archiving-any");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ArchiveArn\": \"" + arn + "\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-archiving-any/archiving-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-archiving-any")
        .then().statusCode(200)
            .body("ArchivingOptions.ArchiveArn", equalTo(arn));
    }

    @Test
    @Order(37)
    void putTrackingOptions_verifiedDomain_echoed() {
        verifyDomainIdentityViaRoute53("track.floci.test", "v2-cs-tracking");
        putConfigSet("v2-cs-tracking");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"track.floci.test\", \"HttpsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking/tracking-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-tracking")
        .then().statusCode(200)
            .body("TrackingOptions.CustomRedirectDomain", equalTo("track.floci.test"))
            .body("TrackingOptions.HttpsPolicy", equalTo("REQUIRE"));
    }

    @Test
    @Order(38)
    void putTrackingOptions_unverifiedDomain_returns400() {
        putConfigSet("v2-cs-tracking-unverified");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"never-verified.example.com\", \"HttpsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-unverified/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Domain <never-verified.example.com> is not verified under this account."));
    }

    @Test
    @Order(39)
    void putTrackingOptions_invalidHttpsPolicy_returns400() {
        // AWS validates the HttpsPolicy enum only after CustomRedirectDomain
        // presence and verification, so the domain must be verified to reach it.
        verifyDomainIdentityViaRoute53("track-enum.floci.test", "v2-cs-tracking-enum");
        putConfigSet("v2-cs-tracking-enum");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"track-enum.floci.test\", \"HttpsPolicy\": \"BOGUS\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-enum/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'httpsPolicy' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [OPTIONAL, REQUIRE, REQUIRE_OPEN_ONLY]"));
    }

    @Test
    @Order(100)
    void putTrackingOptions_pendingDomain_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"EmailIdentity\": \"track-pending.floci.test\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        putConfigSet("v2-cs-tracking-pending");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"track-pending.floci.test\", \"HttpsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-pending/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Domain <track-pending.floci.test> is not verified under this account."));
    }

    @Test
    @Order(40)
    void putReputationOptions_emptyBody_disables() {
        // AWS treats a PutConfigurationSetReputationOptions with no
        // ReputationMetricsEnabled member as "false" (verified against real AWS
        // SES V2 on 2026-06-17) — it is not required and does not error.
        putConfigSet("v2-cs-rep-empty");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{}")
        .when().put("/v2/email/configuration-sets/v2-cs-rep-empty/reputation-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-rep-empty")
        .then().statusCode(200)
            .body("ReputationOptions.ReputationMetricsEnabled", equalTo(false));
    }

    @Test
    @Order(41)
    void putDeliveryOptions_invalidTlsPolicy_returns400() {
        putConfigSet("v2-cs-delivery-tls");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"MAYBE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-tls/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'tlsPolicy' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [OPTIONAL, REQUIRE]"));
    }

    @Test
    @Order(42)
    void putDeliveryOptions_nonNumericMaxDeliverySeconds_returns400() {
        putConfigSet("v2-cs-delivery-max");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"REQUIRE\", \"MaxDeliverySeconds\": \"soon\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-max/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("MaxDeliverySeconds must be a number."));
    }

    @Test
    @Order(43)
    void putTrackingOptions_nonStringHttpsPolicy_returns400() {
        putConfigSet("v2-cs-tracking-type");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"HttpsPolicy\": 123}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-type/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("HttpsPolicy must be a JSON string."));
    }

    @Test
    @Order(44)
    void putArchivingOptions_nonStringArn_returns400() {
        putConfigSet("v2-cs-archiving-type");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ArchiveArn\": true}")
        .when().put("/v2/email/configuration-sets/v2-cs-archiving-type/archiving-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("ArchiveArn must be a JSON string."));
    }

    @Test
    @Order(48)
    void putDeliveryOptions_fractionalMaxDeliverySeconds_returns400() {
        // MaxDeliverySeconds is an integer; a fractional value must not be
        // silently truncated.
        putConfigSet("v2-cs-delivery-frac");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"REQUIRE\", \"MaxDeliverySeconds\": 1.5}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-frac/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("MaxDeliverySeconds must be an integer."));
    }

    @Test
    @Order(45)
    void putTrackingOptions_httpsPolicyWithoutDomain_returns400() {
        // AWS requires CustomRedirectDomain whenever HttpsPolicy is set
        // (verified against real AWS 2026-06-17); an empty body is accepted.
        putConfigSet("v2-cs-tracking-nodomain");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"HttpsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-nodomain/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("CustomRedirectDomain must be specified."));
    }

    @Test
    @Order(46)
    void putTrackingOptions_unverifiedDomainPrecedesEnum_returns400() {
        // AWS checks domain verification before the HttpsPolicy enum, so an
        // unverified domain wins over an invalid enum value.
        putConfigSet("v2-cs-tracking-order");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"unverified-order.example.com\", \"HttpsPolicy\": \"BOGUS\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-order/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Domain <unverified-order.example.com> is not verified under this account."));
    }

    @Test
    @Order(47)
    void putTrackingOptions_verifiedEmailIdentityAsDomain_returns400() {
        // CustomRedirectDomain must be a verified *domain* identity; a verified
        // email-address identity does not qualify.
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"EmailIdentity\": \"redirect@floci.test\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        putConfigSet("v2-cs-tracking-email-id");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"redirect@floci.test\", \"HttpsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-email-id/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Domain <redirect@floci.test> is not verified under this account."));
    }

    @Test
    @Order(49)
    void putTrackingOptions_blankDomain_returns400() {
        // AWS rejects a present-but-blank CustomRedirectDomain rather than
        // storing it (verified against real AWS 2026-06-17), even with no
        // HttpsPolicy set.
        putConfigSet("v2-cs-tracking-blank");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"   \"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-blank/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("CustomRedirectDomain must be specified."));
    }

    @Test
    @Order(50)
    void putTrackingOptions_unverifiedDomainWithoutHttpsPolicy_returns400() {
        // AWS verifies CustomRedirectDomain even when HttpsPolicy is omitted.
        putConfigSet("v2-cs-tracking-nopolicy");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"never-verified-nopolicy.example.com\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-nopolicy/tracking-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Domain <never-verified-nopolicy.example.com> is not verified under this account."));
    }

    @Test
    @Order(51)
    void putTrackingOptions_emptyBody_clears() {
        // An empty PUT body clears tracking options rather than persisting an
        // empty block that GetConfigurationSet would echo as {}.
        verifyDomainIdentityViaRoute53("clear.floci.test", "v2-cs-tracking-clear");
        putConfigSet("v2-cs-tracking-clear");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"CustomRedirectDomain\": \"clear.floci.test\", \"HttpsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-clear/tracking-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-tracking-clear")
        .then().statusCode(200).body("TrackingOptions.HttpsPolicy", equalTo("REQUIRE"));
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{}")
        .when().put("/v2/email/configuration-sets/v2-cs-tracking-clear/tracking-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-tracking-clear")
        .then().statusCode(200).body("TrackingOptions", nullValue());
    }

    @Test
    @Order(52)
    void putDeliveryOptions_emptyBody_clears() {
        putConfigSet("v2-cs-delivery-clear");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-clear/delivery-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-delivery-clear")
        .then().statusCode(200).body("DeliveryOptions.TlsPolicy", equalTo("REQUIRE"));
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-clear/delivery-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-delivery-clear")
        .then().statusCode(200).body("DeliveryOptions", nullValue());
    }

    @Test
    @Order(53)
    void putDeliveryOptions_blankSendingPool_returns400() {
        // AWS rejects a blank SendingPoolName with a distinct message (verified
        // against real AWS 2026-06-17), separate from the non-existent-pool case.
        putConfigSet("v2-cs-delivery-blankpool");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"SendingPoolName\": \"   \"}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-blankpool/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("sendingPoolName can't be blank."));
    }

    @Test
    @Order(54)
    void putDeliveryOptions_maxDeliverySecondsBelowMinimum_returns400() {
        putConfigSet("v2-cs-delivery-min");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"MaxDeliverySeconds\": 5}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-min/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'maxDeliverySeconds' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 300"));
    }

    @Test
    @Order(55)
    void putDeliveryOptions_maxDeliverySecondsAboveMaximum_returns400() {
        putConfigSet("v2-cs-delivery-overmax");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"MaxDeliverySeconds\": 999999}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-overmax/delivery-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'maxDeliverySeconds' failed to satisfy constraint: "
                    + "Member must have value less than or equal to 50400"));
    }

    @Test
    @Order(56)
    void putVdmOptions_echoed() {
        putConfigSet("v2-cs-vdm");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"VdmOptions\": {\"DashboardOptions\": {\"EngagementMetrics\": \"ENABLED\"}, "
                    + "\"GuardianOptions\": {\"OptimizedSharedDelivery\": \"DISABLED\"}}}")
        .when().put("/v2/email/configuration-sets/v2-cs-vdm/vdm-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-vdm")
        .then().statusCode(200)
            .body("VdmOptions.DashboardOptions.EngagementMetrics", equalTo("ENABLED"))
            .body("VdmOptions.GuardianOptions.OptimizedSharedDelivery", equalTo("DISABLED"));
    }

    @Test
    @Order(57)
    void putVdmOptions_invalidEngagementMetrics_returns400() {
        putConfigSet("v2-cs-vdm-bad");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"VdmOptions\": {\"DashboardOptions\": {\"EngagementMetrics\": \"NONSENSE\"}}}")
        .when().put("/v2/email/configuration-sets/v2-cs-vdm-bad/vdm-options")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'vdmOptions.dashboardOptions.engagementMetrics' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [ENABLED, DISABLED]"));
    }

    @Test
    @Order(58)
    void createConfigurationSet_inlineVdm_echoed() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ConfigurationSetName\": \"v2-cs-vdm-inline\", "
                    + "\"VdmOptions\": {\"DashboardOptions\": {\"EngagementMetrics\": \"DISABLED\"}}}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-vdm-inline")
        .then().statusCode(200)
            .body("VdmOptions.DashboardOptions.EngagementMetrics", equalTo("DISABLED"));
    }

    @Test
    @Order(59)
    void putVdmOptions_emptyBody_clears() {
        putConfigSet("v2-cs-vdm-clear");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"VdmOptions\": {\"DashboardOptions\": {\"EngagementMetrics\": \"ENABLED\"}}}")
        .when().put("/v2/email/configuration-sets/v2-cs-vdm-clear/vdm-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-vdm-clear")
        .then().statusCode(200).body("VdmOptions.DashboardOptions.EngagementMetrics", equalTo("ENABLED"));
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{}")
        .when().put("/v2/email/configuration-sets/v2-cs-vdm-clear/vdm-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-vdm-clear")
        .then().statusCode(200).body("VdmOptions", nullValue());
    }

    @Test
    @Order(60)
    void createConfigurationSet_invalidInlineVdm_returns400_andDoesNotCreate() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ConfigurationSetName\": \"v2-cs-vdm-inline-bad\", "
                    + "\"VdmOptions\": {\"DashboardOptions\": {\"EngagementMetrics\": \"NONSENSE\"}}}")
        .when().post("/v2/email/configuration-sets")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("1 validation error detected: Value at "
                    + "'vdmOptions.dashboardOptions.engagementMetrics' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [ENABLED, DISABLED]"));

        // Validation happens before the store write, so the set must not exist.
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-vdm-inline-bad")
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(61)
    void putDeliveryOptions_existingSendingPool_roundTrips() {
        // A SendingPoolName that refers to a dedicated IP pool created via
        // CreateDedicatedIpPool is accepted and echoed (verified against real
        // AWS 2026-06-18).
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"v2-cs-pool-ref\"}")
        .when().post("/v2/email/dedicated-ip-pools").then().statusCode(200);
        putConfigSet("v2-cs-delivery-poolref");
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"TlsPolicy\": \"REQUIRE\", \"SendingPoolName\": \"v2-cs-pool-ref\"}")
        .when().put("/v2/email/configuration-sets/v2-cs-delivery-poolref/delivery-options")
        .then().statusCode(200);
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/v2-cs-delivery-poolref")
        .then().statusCode(200)
            .body("DeliveryOptions.SendingPoolName", equalTo("v2-cs-pool-ref"));
    }

    private static void putConfigSet(String name) {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"ConfigurationSetName\": \"" + name + "\"}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);
    }
}
