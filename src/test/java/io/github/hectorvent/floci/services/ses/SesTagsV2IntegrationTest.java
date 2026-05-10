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
    void tagResource_emptyTags_returns400() {
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
            .statusCode(400);
    }

    @Test
    @Order(8)
    void untagResource_missingTagKeys_returns400() {
        String arn = "arn:aws:ses:us-east-1:000000000000:configuration-set/tag-cs-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400);
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
        // ("Failed to tag resource"). The behaviour differs from UntagResource, which
        // routes the lookup to the ARN's region and surfaces NotFoundException instead.
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
    void untagResource_arnRegionMismatch_returns404() {
        // tag-cs-1 exists in us-east-1 but the ARN points to eu-west-1, so AWS
        // returns NotFoundException with the resource-specific message.
        String arn = "arn:aws:ses:eu-west-1:000000000000:configuration-set/tag-cs-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(404)
            .body(containsString("No ConfigurationSet present with name: tag-cs-1"));
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
    void listTagsForResource_template_arnRegionIgnored_usesSigningRegion() {
        // For template ARNs AWS ignores the ARN region for ListTagsForResource and
        // resolves the template against the signing region instead. The seeded
        // tag-tpl-1 lives in us-east-1 and was left with a single "owner=alice" tag
        // by the lifecycle case at @Order(2); an eu-west-1 ARN must still surface it.
        String arn = "arn:aws:ses:eu-west-1:000000000000:template/tag-tpl-1";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"))
            .body("Tags[0].Value", equalTo("alice"));
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
    void listTagsForResource_identity_arnRegionIgnored_usesSigningRegion() {
        // tag-id-1 lives in us-east-1 and was left with a single "owner=alice" tag by
        // the lifecycle case at @Order(16); an eu-west-1 ARN must still surface it.
        String arn = "arn:aws:ses:eu-west-1:000000000000:identity/tag-id-1@example.com";
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"))
            .body("Tags[0].Value", equalTo("alice"));
    }
}
