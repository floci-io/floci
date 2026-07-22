package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the SES V1 Query-protocol identity-policy APIs
 * (Put/Get/List/DeleteIdentityPolicy). Verified against real AWS: v1 Put is an
 * upsert, v1 Delete is idempotent, v1 List/Get do not require the identity, and
 * policies are shared with the V2 store (a v1 policy is visible via V2 Get).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityPolicyV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String V2_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String IDENTITY = "policy-v1.floci.test";
    private static final String DOC_A = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"AAA\"}]}";
    private static final String DOC_B = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"BBB\"}]}";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action);
    }

    @Test
    @Order(0)
    void setup_createIdentity() {
        query("VerifyDomainIdentity").formParam("Domain", IDENTITY).when().post("/").then().statusCode(200);
    }

    @Test
    @Order(1)
    void putIdentityPolicy_thenUpsert() {
        query("PutIdentityPolicy").formParam("Identity", IDENTITY)
                .formParam("PolicyName", "pA").formParam("Policy", DOC_A)
        .when().post("/").then().statusCode(200)
                .body(containsString("PutIdentityPolicyResponse"));

        // Same name again with a different document -> upsert, no error.
        query("PutIdentityPolicy").formParam("Identity", IDENTITY)
                .formParam("PolicyName", "pA").formParam("Policy", DOC_B)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void getIdentityPolicies_returnsUpdatedDoc() {
        query("GetIdentityPolicies").formParam("Identity", IDENTITY)
                .formParam("PolicyNames.member.1", "pA")
        .when().post("/").then().statusCode(200)
                .body(containsString("<key>pA</key>"))
                .body(containsString("BBB"))
                .body(not(containsString("AAA")));
    }

    @Test
    @Order(3)
    void getIdentityPolicies_missingNameOmitted() {
        query("GetIdentityPolicies").formParam("Identity", IDENTITY)
                .formParam("PolicyNames.member.1", "pA")
                .formParam("PolicyNames.member.2", "nope")
        .when().post("/").then().statusCode(200)
                .body(containsString("<key>pA</key>"))
                .body(not(containsString("nope")));
    }

    @Test
    @Order(4)
    void listIdentityPolicies() {
        query("ListIdentityPolicies").formParam("Identity", IDENTITY)
        .when().post("/").then().statusCode(200)
                .body(containsString("<member>pA</member>"));
    }

    @Test
    @Order(5)
    void deleteIdentityPolicy_isIdempotent() {
        query("DeleteIdentityPolicy").formParam("Identity", IDENTITY).formParam("PolicyName", "pA")
        .when().post("/").then().statusCode(200);
        // Deleting a missing policy again succeeds (no error) on v1.
        query("DeleteIdentityPolicy").formParam("Identity", IDENTITY).formParam("PolicyName", "pA")
        .when().post("/").then().statusCode(200)
                .body(containsString("DeleteIdentityPolicyResponse"));
    }

    @Test
    @Order(6)
    void listAndGetOnMissingIdentity_returnEmpty() {
        query("ListIdentityPolicies").formParam("Identity", "never-verified.floci.test")
        .when().post("/").then().statusCode(200)
                .body(containsString("<PolicyNames"));
        query("GetIdentityPolicies").formParam("Identity", "never-verified.floci.test")
                .formParam("PolicyNames.member.1", "x")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(8)
    void invalidPolicyName_v1ErrorCodes() {
        // Verified against real AWS: v1 uses InvalidParameterValue for bad chars and ValidationError
        // for length (the v2 controller remaps both to BadRequestException).
        query("PutIdentityPolicy").formParam("Identity", IDENTITY)
                .formParam("PolicyName", "bad name!").formParam("Policy", DOC_A)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Policy names must only include"));
        query("PutIdentityPolicy").formParam("Identity", IDENTITY)
                .formParam("PolicyName", "a".repeat(65)).formParam("Policy", DOC_A)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("length less than or equal to 64"));
    }

    @Test
    @Order(7)
    void v1Policy_isVisibleViaV2() {
        query("PutIdentityPolicy").formParam("Identity", IDENTITY)
                .formParam("PolicyName", "shared").formParam("Policy", DOC_A)
        .when().post("/").then().statusCode(200);

        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/identities/" + IDENTITY + "/policies")
        .then().statusCode(200)
                .body("Policies.shared", containsString("AAA"));
    }
}
