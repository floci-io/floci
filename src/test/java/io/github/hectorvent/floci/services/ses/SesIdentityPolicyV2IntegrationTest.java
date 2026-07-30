package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the SES V2 REST JSON identity-policy APIs
 * (Create/Get/Update/DeleteEmailIdentityPolicy). Behaviour and error shapes are
 * verified against real AWS (per-identity limit, create-dup/update-missing/
 * delete-missing errors, PolicyName constraints, missing-identity handling).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityPolicyV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String IDENTITY = "policy-v2.floci.test";
    private static final String BASE = "/v2/email/identities/" + IDENTITY + "/policies";
    private static final String DOC = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private static String policyBody(String doc) {
        return "{\"Policy\":\"" + doc.replace("\"", "\\\"") + "\"}";
    }

    @Test
    @Order(0)
    void setup_createIdentity() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\": \"" + IDENTITY + "\"}")
        .when().post("/v2/email/identities")
        .then().statusCode(200);
    }

    @Test
    @Order(1)
    void createPolicy_thenGet() {
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(BASE + "/p1")
        .then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get(BASE)
        .then().statusCode(200)
                .body("Policies.p1", notNullValue());
    }

    @Test
    @Order(2)
    void createDuplicateName_throwsAlreadyExists() {
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(BASE + "/p1")
        .then().statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"))
                .body("message", equalTo("Policy <p1> already exists"));
    }

    @Test
    @Order(3)
    void createSameContentDifferentName_ok() {
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(BASE + "/p2")
        .then().statusCode(200);
    }

    @Test
    @Order(4)
    void updatePolicy_replaces() {
        String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"X\"}]}";
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(updated))
        .when().put(BASE + "/p1")
        .then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get(BASE)
        .then().statusCode(200)
                .body("Policies.p1", equalTo(updated));
    }

    @Test
    @Order(5)
    void updateMissing_throwsNotFound() {
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().put(BASE + "/nope")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Policy <nope> does not exist"));
    }

    @Test
    @Order(6)
    void deletePolicy_thenGone() {
        given().header("Authorization", AUTH)
        .when().delete(BASE + "/p2")
        .then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get(BASE)
        .then().statusCode(200)
                .body("Policies.p2", nullValue());
    }

    @Test
    @Order(7)
    void deleteMissing_throwsNotFound() {
        given().header("Authorization", AUTH)
        .when().delete(BASE + "/nope")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(8)
    void operationsOnMissingIdentity_throwNotFound() {
        String ghost = "/v2/email/identities/ghost-v2.floci.test/policies";
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(ghost + "/p")
        .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Email identity <ghost-v2.floci.test> does not exist."));
        given().header("Authorization", AUTH).when().get(ghost).then().statusCode(404);
    }

    @Test
    @Order(9)
    void invalidPolicyName_chars_returns400() {
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(BASE + "/bad%20name")
        .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("PolicyName is invalid. Policy names must only include "
                        + "alpha-numeric characters, dashes, and underscores."));
    }

    @Test
    @Order(10)
    void invalidPolicyName_tooLong_returns400() {
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(BASE + "/" + "a".repeat(65))
        .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'policyName' failed "
                        + "to satisfy constraint: Member must have length less than or equal to 64"));
    }

    @Test
    @Order(11)
    void perIdentityLimit_21stThrowsLimitExceeded() {
        // p1 already exists (Order 4). Fill to 20, then the 21st create fails.
        for (int i = 2; i <= 20; i++) {
            given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
            .when().post(BASE + "/lp" + i)
            .then().statusCode(200);
        }
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(BASE + "/overflow")
        .then().statusCode(400)
                .body("__type", equalTo("LimitExceededException"))
                .body("message", equalTo("Number of policies for <" + IDENTITY
                        + "> exceeds max allowed number of policies per resource"));
    }

    @Test
    @Order(12)
    void deleteIdentity_purgesPolicies_noResurrectOnRecreate() {
        String id = "cascade.floci.test";
        String base = "/v2/email/identities/" + id + "/policies";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\": \"" + id + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body(policyBody(DOC))
        .when().post(base + "/c1").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().delete("/v2/email/identities/" + id).then().statusCode(200);

        // Recreate the same-named identity: the old policy must not leak back in.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\": \"" + id + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        given().header("Authorization", AUTH)
        .when().get(base).then().statusCode(200)
                .body("Policies.size()", equalTo(0));
    }

    @Test
    @Order(13)
    void policyWrongType_returns400() {
        // Policy is a string field; a non-string must be rejected, not coerced to "123".
        given().contentType("application/json").header("Authorization", AUTH).body("{\"Policy\": 123}")
        .when().post(BASE + "/wrongtype")
        .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(14)
    void policyMissingOrNull_returnsValidationError() {
        String expected = "1 validation error detected: Value at 'policy' failed to satisfy "
                + "constraint: Member must not be null";
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
        .when().post(BASE + "/missing")
        .then().statusCode(400).body("message", equalTo(expected));
        given().contentType("application/json").header("Authorization", AUTH).body("{\"Policy\": null}")
        .when().post(BASE + "/nullpol")
        .then().statusCode(400).body("message", equalTo(expected));
    }
}
