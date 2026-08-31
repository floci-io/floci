package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the SES V2 tag endpoints
 * (TagResource / UntagResource / ListTagsForResource at /v2/email/tags).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTagsV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void tags_lifecycle_onConfigurationSet() {
        // Seed: create a configuration set we can tag against
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "tag-cs-1"}
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/tag-cs-1";

        // Initially empty
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        // TagResource
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [
                  {"Key": "env", "Value": "dev"},
                  {"Key": "owner", "Value": "alice"}
                ]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("dev"))
            .body("Tags.find { it.Key == 'owner' }.Value", equalTo("alice"));

        // TagResource on existing key replaces value (merge semantics)
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [
                  {"Key": "env", "Value": "prod"}
                ]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("prod"));

        // UntagResource removes specific keys
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "env")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));
    }

    @Test
    @Order(2)
    void tags_lifecycle_onEmailTemplate() {
        // Seed: create an email template we can tag against
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "tag-tpl-1",
                  "TemplateContent": {"Subject": "S", "Text": "T"}
                }
                """)
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:template/tag-tpl-1";

        // Initially empty
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        // Tag the template
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [
                  {"Key": "env", "Value": "stg"},
                  {"Key": "owner", "Value": "alice"}
                ]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));

        // Remove a key
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "env")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));
    }

    @Test
    @Order(3)
    void tagResource_unknownEmailTemplate_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:template/missing-tpl";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404)
            .body(containsString("No Template present with name: missing-tpl"));
    }

    @Test
    @Order(4)
    void tagResource_unknownConfigurationSet_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/missing-cs";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(5)
    void listTagsForResource_unsupportedResourceType_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:identity/example.com";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(6)
    void tagResource_invalidArn_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "not-an-arn", "Tags": [{"Key": "env", "Value": "dev"}]}
                """)
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(7)
    void tagResource_emptyTags_isNoOp() {
        // An empty Tags list is not an error (probe-confirmed): the existence check still runs,
        // then the empty merge no-ops. tag-cs-1 was left with a single "owner" tag by @Order(1).
        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/tag-cs-1";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": []}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));

        // The no-op still checks existence: an unknown resource stays NotFound.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "arn:aws:ses:us-east-1:000000000000:configuration-set/nowhere", "Tags": []}
                """)
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(8)
    void untagResource_missingTagKeys_returnsBareValidationException() {
        // A missing/empty TagKeys member is a message-less ValidationException (probe-confirmed:
        // AWS sends only the error-type header with an empty body; Floci's standard error body
        // carries "message":null, which restJson1 SDKs parse identically).
        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/tag-cs-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", containsString("ValidationException"))
            // Exact raw-body match: GPath member assertions can't distinguish an absent member
            // from an explicit JSON null, and a substring can't reject extra members — this
            // pins the documented shape wholesale.
            .body(equalTo("{\"__type\":\"ValidationException\",\"message\":null}"));
    }

    @Test
    @Order(9)
    void tagResource_arnMissingRegion_returns400() {
        String arn = "arn:aws:ses::000000000000:configuration-set/tag-cs-1";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "k", "Value": "v"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(10)
    void tagResource_nonSesArn_returns400() {
        String arn = "arn:aws:s3:us-east-1:000000000000:bucket/my-bucket";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "k", "Value": "v"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(11)
    void tagResource_arnRegionMismatch_returns400() {
        // AWS rejects TagResource on ARN/signing region mismatch with BadRequestException
        // ("Failed to tag resource") for every resource type (probe-confirmed).
        String arn = "arn:aws:ses:eu-west-1:000000000000:configuration-set/tag-cs-1";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "k", "Value": "v"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to tag resource"));
    }

    @Test
    @Order(12)
    void untagResource_arnRegionMismatch_returns400() {
        // AWS rejects UntagResource on ARN/signing region mismatch with BadRequestException
        // ("Failed to untag resource") for every resource type, configuration sets included
        // (probe-confirmed).
        String arn = "arn:aws:ses:eu-west-1:000000000000:configuration-set/tag-cs-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to untag resource"));
    }

    @Test
    @Order(13)
    void tagResource_invalidConfigurationSetName_returns400() {
        // Whitespace in configuration-set name fails configSetKey validation,
        // which is remapped from InvalidParameterValue -> BadRequestException at the controller.
        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/has spaces";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "k", "Value": "v"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(14)
    void untagResource_template_arnRegionMismatch_returns400() {
        // For template ARNs AWS rejects UntagResource on signing/ARN region mismatch with
        // BadRequestException ("Failed to untag resource"), unlike ConfigurationSet which
        // routes the lookup to the ARN's region and surfaces NotFound instead.
        String arn = "arn:aws:ses:eu-west-1:000000000000:template/tag-tpl-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to untag resource"));
    }

    @Test
    @Order(15)
    void listTagsForResource_template_arnRegionMismatch_returnsEmpty() {
        // AWS checks existence against the signing region but keys tags by the literal ARN
        // (probe-confirmed): tag-tpl-1 exists in us-east-1 (seeded with tags by the
        // lifecycle case at @Order(2)), so an eu-west-1 ARN passes the existence check yet
        // addresses an ARN nothing was tagged under — 200 with an empty tag set.
        String arn = "arn:aws:ses:eu-west-1:000000000000:template/tag-tpl-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(16)
    void tags_lifecycle_onEmailIdentity() {
        // Seed: create an email identity we can tag against
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "tag-id-1@example.com"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:identity/tag-id-1@example.com";

        // Initially empty
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        // Tag it
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [
                  {"Key": "env", "Value": "stg"},
                  {"Key": "owner", "Value": "alice"}
                ]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));

        // Untag a key
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "env")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));
    }

    @Test
    @Order(17)
    void tagResource_unknownEmailIdentity_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:identity/missing-id@example.com";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404)
            .body(containsString("No EmailIdentity present with name: missing-id@example.com"));
    }

    @Test
    @Order(18)
    void untagResource_identity_arnRegionMismatch_returns400() {
        // Identity follows the same strict region check as templates for UntagResource.
        String arn = "arn:aws:ses:eu-west-1:000000000000:identity/tag-id-1@example.com";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to untag resource"));
    }

    @Test
    @Order(19)
    void listTagsForResource_identity_arnRegionMismatch_returnsEmpty() {
        // tag-id-1 exists in us-east-1 (tagged by the lifecycle case at @Order(16)), so the
        // signing-region existence check passes, but the eu-west-1 ARN addresses an ARN nothing
        // was tagged under — 200 with an empty tag set (probe-confirmed).
        String arn = "arn:aws:ses:eu-west-1:000000000000:identity/tag-id-1@example.com";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(20)
    void tagResource_mergeExceedingFifty_returns400() {
        // AWS enforces the 50-tag ceiling on the MERGED (existing + incoming) set, and updating an
        // existing key (net count unchanged) is allowed. Probe-confirmed against real AWS.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "tag-cs-merge"}
                """)
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/tag-cs-merge";

        // Seed with the full 50 tags.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\": \"" + arn + "\", \"Tags\": " + tagArray(0, 50) + "}")
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        // Updating an existing key keeps the count at 50 and is accepted.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\": \"" + arn + "\", \"Tags\": [{\"Key\": \"k0\", \"Value\": \"updated\"}]}")
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        // Adding one new key would make 51 — rejected with the merge-specific message.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\": \"" + arn + "\", \"Tags\": [{\"Key\": \"extra\", \"Value\": \"v\"}]}")
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Maximum of 50 user tags are allowed per resource, consider "
                + "reducing the number of tags in the request or delete existing tags and retry"));
    }

    @Test
    @Order(21)
    void tagResource_reservedAwsPrefixKey_returns400() {
        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/tag-cs-1";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\": \"" + arn + "\", \"Tags\": [{\"Key\": \"aws:foo\", \"Value\": \"v\"}]}")
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Caller is an end user and not allowed to mutate system tags"));
    }

    @Test
    @Order(22)
    void createEmailIdentity_invalidInlineTags_isAtomic_identityNotPersisted() {
        // A semantic tag error must fail CreateEmailIdentity before the identity is persisted, so a
        // corrected retry does not hit AlreadyExistsException. Validation is now applied to the parsed
        // tag list up front, before verifyEmailIdentity/verifyDomainIdentity.
        String id = "atomic-tag-id@example.com";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "%s", "Tags": [
                  {"Key": "dup", "Value": "1"},
                  {"Key": "dup", "Value": "2"}
                ]}
                """.formatted(id))
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Cannot provide multiple tags with the same key"));

        // The failed create must leave no identity behind.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/identities/" + id)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(23)
    void tags_lifecycle_onContactList() {
        // Seed: create a contact list with inline tags
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ContactListName": "tag-cl-1", "Tags": [{"Key": "env", "Value": "dev"}]}
                """)
        .when()
            .post("/v2/email/contact-lists")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:contact-list/tag-cl-1";

        // Inline create tags are visible via ListTagsForResource
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));

        // TagResource merges an additional key
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "owner", "Value": "alice"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));

        // UntagResource removes a key
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "env")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));

        // Region mismatch handling (probe-confirmed): UntagResource rejects an
        // ARN/signing region mismatch, and ListTagsForResource passes the signing-region
        // existence check but returns an empty set for the untagged mismatched ARN.
        String euArn = "arn:aws:ses:eu-west-1:000000000000:contact-list/tag-cl-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", euArn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to untag resource"));

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", euArn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        // Free the one-list-per-account slot so later contact-list tests aren't order-dependent.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/v2/email/contact-lists/tag-cl-1")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(24)
    void tagResource_unknownContactList_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:contact-list/missing-cl";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404)
            .body(containsString("No ContactList present with name: missing-cl"));
    }

    @Test
    @Order(25)
    void tags_lifecycle_onCustomVerificationEmailTemplate() {
        // Seed: CVET creation requires a verified From identity
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"EmailIdentity": "tag-cvet-sender@floci.test"}
                """)
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "tag-cvet-1",
                  "FromEmailAddress": "tag-cvet-sender@floci.test",
                  "TemplateSubject": "Verify",
                  "TemplateContent": "<html><body>verify</body></html>",
                  "SuccessRedirectionURL": "https://example.com/ok",
                  "FailureRedirectionURL": "https://example.com/no"
                }
                """)
        .when()
            .post("/v2/email/custom-verification-email-templates")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:custom-verification-email-template/tag-cvet-1";

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [
                  {"Key": "env", "Value": "dev"},
                  {"Key": "owner", "Value": "alice"}
                ]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        // UpdateCustomVerificationEmailTemplate must not wipe the tags
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "FromEmailAddress": "tag-cvet-sender@floci.test",
                  "TemplateSubject": "Verify again",
                  "TemplateContent": "<html><body>verify</body></html>",
                  "SuccessRedirectionURL": "https://example.com/ok",
                  "FailureRedirectionURL": "https://example.com/no"
                }
                """)
        .when()
            .put("/v2/email/custom-verification-email-templates/tag-cvet-1")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "env")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));
    }

    @Test
    @Order(26)
    void tagResource_unknownCustomVerificationEmailTemplate_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:custom-verification-email-template/missing-cvet";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404)
            .body(containsString("No CustomVerificationEmailTemplate present with name: missing-cvet"));
    }

    @Test
    @Order(27)
    void tags_lifecycle_onDedicatedIpPool() {
        // Seed: create a pool with inline tags
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"PoolName": "tag-pool-1", "Tags": [{"Key": "env", "Value": "dev"}]}
                """)
        .when()
            .post("/v2/email/dedicated-ip-pools")
        .then()
            .statusCode(200);

        String arn = "arn:aws:ses:us-east-1:000000000000:dedicated-ip-pool/tag-pool-1";

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));

        // GetDedicatedIpPool keeps the AWS shape: no Tags member
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/dedicated-ip-pools/tag-pool-1")
        .then()
            .statusCode(200)
            .body("DedicatedIpPool.PoolName", equalTo("tag-pool-1"))
            .body("DedicatedIpPool.Tags", nullValue());

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "owner", "Value": "alice"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "env")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"));
    }

    @Test
    @Order(28)
    void tagResource_unknownDedicatedIpPool_returns404() {
        String arn = "arn:aws:ses:us-east-1:000000000000:dedicated-ip-pool/missing-pool";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(404)
            .body(containsString("No DedicatedIpPool present with name: missing-pool"));
    }

    @Test
    @Order(29)
    void createDedicatedIpPool_invalidInlineTags_isAtomic_poolNotPersisted() {
        // A semantic tag error must fail CreateDedicatedIpPool before the pool is persisted, so a
        // corrected retry does not hit AlreadyExistsException.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"PoolName": "atomic-tag-pool", "Tags": [
                  {"Key": "dup", "Value": "1"},
                  {"Key": "dup", "Value": "2"}
                ]}
                """)
        .when()
            .post("/v2/email/dedicated-ip-pools")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Cannot provide multiple tags with the same key"));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/dedicated-ip-pools/atomic-tag-pool")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(30)
    void untagResource_cvet_arnRegionMismatch_returns400() {
        // Probe-confirmed.
        String arn = "arn:aws:ses:eu-west-1:000000000000:custom-verification-email-template/tag-cvet-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to untag resource"));
    }

    @Test
    @Order(31)
    void listTagsForResource_cvet_arnRegionMismatch_returnsEmpty() {
        // tag-cvet-1 exists in us-east-1 (tagged by the lifecycle case at @Order(25)), so the
        // signing-region existence check passes, but the eu-west-1 ARN addresses an ARN nothing
        // was tagged under — 200 with an empty tag set (probe-confirmed).
        String arn = "arn:aws:ses:eu-west-1:000000000000:custom-verification-email-template/tag-cvet-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(32)
    void untagResource_dedicatedIpPool_arnRegionMismatch_returns400() {
        // Probe-confirmed.
        String arn = "arn:aws:ses:eu-west-1:000000000000:dedicated-ip-pool/tag-pool-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Failed to untag resource"));
    }

    @Test
    @Order(33)
    void listTagsForResource_dedicatedIpPool_arnRegionMismatch_returnsEmpty() {
        // tag-pool-1 exists in us-east-1 (tagged by the lifecycle case at @Order(27)), so the
        // signing-region existence check passes, but the eu-west-1 ARN addresses an ARN nothing
        // was tagged under — 200 with an empty tag set (probe-confirmed).
        String arn = "arn:aws:ses:eu-west-1:000000000000:dedicated-ip-pool/tag-pool-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(34)
    void listTagsForResource_foreignAccountArn_returns400_beforeExistenceCheck() {
        // A foreign-account ARN is rejected before any existence check (probe-confirmed):
        // the name here exists nowhere, yet the account error wins over NotFound.
        String arn = "arn:aws:ses:us-east-1:999999999999:configuration-set/nowhere";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Operations on a resource created in a different account is not allowed"));
    }

    @Test
    @Order(35)
    void tagResource_foreignAccountArn_returns400() {
        String arn = "arn:aws:ses:us-east-1:999999999999:configuration-set/tag-cs-1";
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "k", "Value": "v"}]}
                """.formatted(arn))
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Operations on a resource created in a different account is not allowed"));
    }

    @Test
    @Order(36)
    void untagResource_foreignAccountAndRegionMismatch_accountErrorWins() {
        // With both the account and the region mismatched, AWS reports the account error,
        // not "Failed to untag resource" (probe-confirmed).
        String arn = "arn:aws:ses:eu-west-1:999999999999:configuration-set/tag-cs-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(containsString("Operations on a resource created in a different account is not allowed"));
    }

    @Test
    @Order(37)
    void createCustomVerificationEmailTemplate_inlineTags_storedAndAtomic() {
        // v2 CreateCustomVerificationEmailTemplate accepts Tags (probe-confirmed); the From
        // identity tag-cvet-sender@floci.test was verified by the lifecycle case at @Order(25).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "tag-cvet-inline",
                  "FromEmailAddress": "tag-cvet-sender@floci.test",
                  "TemplateSubject": "Verify",
                  "TemplateContent": "<html><body>verify</body></html>",
                  "SuccessRedirectionURL": "https://example.com/ok",
                  "FailureRedirectionURL": "https://example.com/no",
                  "Tags": [{"Key": "env", "Value": "dev"}]
                }
                """)
        .when()
            .post("/v2/email/custom-verification-email-templates")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn",
                    "arn:aws:ses:us-east-1:000000000000:custom-verification-email-template/tag-cvet-inline")
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));

        // A semantic tag error fails the create before anything is persisted, so a corrected
        // retry does not hit AlreadyExistsException (probe-confirmed message and atomicity).
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "tag-cvet-atomic",
                  "FromEmailAddress": "tag-cvet-sender@floci.test",
                  "TemplateSubject": "Verify",
                  "TemplateContent": "<html><body>verify</body></html>",
                  "SuccessRedirectionURL": "https://example.com/ok",
                  "FailureRedirectionURL": "https://example.com/no",
                  "Tags": [{"Key": "dup", "Value": "1"}, {"Key": "dup", "Value": "2"}]
                }
                """)
        .when()
            .post("/v2/email/custom-verification-email-templates")
        .then()
            .statusCode(400)
            .body(containsString("Cannot provide multiple tags with the same key"));

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/custom-verification-email-templates/tag-cvet-atomic")
        .then()
            .statusCode(404);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "TemplateName": "tag-cvet-atomic",
                  "FromEmailAddress": "tag-cvet-sender@floci.test",
                  "TemplateSubject": "Verify",
                  "TemplateContent": "<html><body>verify</body></html>",
                  "SuccessRedirectionURL": "https://example.com/ok",
                  "FailureRedirectionURL": "https://example.com/no",
                  "Tags": [{"Key": "env", "Value": "dev"}]
                }
                """)
        .when()
            .post("/v2/email/custom-verification-email-templates")
        .then()
            .statusCode(200);
    }

    private static String tagArray(int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(",");
            }
            sb.append("{\"Key\": \"k").append(i).append("\", \"Value\": \"v\"}");
        }
        return sb.append("]").toString();
    }
}
