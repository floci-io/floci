package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies TagInstanceProfile and UntagInstanceProfile wire compatibility:
 * successful tagging/untagging returns 200 XML, and tagging a nonexistent
 * profile returns NoSuchEntity (404).
 */
@QuarkusTest
class InstanceProfileTagsIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static void createProfile(String name) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateInstanceProfile")
            .formParam("InstanceProfileName", name)
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void tagInstanceProfileReturns200() {
        String profile = "tag-test-" + UUID.randomUUID().toString().substring(0, 8);
        createProfile(profile);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TagInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .formParam("Tags.member.1.Key", "team")
            .formParam("Tags.member.1.Value", "platform")
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void untagInstanceProfileReturns200() {
        String profile = "untag-test-" + UUID.randomUUID().toString().substring(0, 8);
        createProfile(profile);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UntagInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .formParam("TagKeys.member.1", "team")
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(200);
    }

    private static io.restassured.specification.RequestSpecification tagRequest(String profile,
                                                                                int from, int to) {
        var request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TagInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .header("Authorization", auth(ACCOUNT, "iam"));
        for (int i = from; i <= to; i++) {
            request.formParam("Tags.member." + (i - from + 1) + ".Key", "key" + i)
                   .formParam("Tags.member." + (i - from + 1) + ".Value", "value" + i);
        }
        return request;
    }

    /**
     * {@code tagListType} is {@code max: 50}, so a request carrying more than 50 tags is a
     * request-shape failure, checked before the profile is even resolved. Without it the extra
     * tags landed in the profile's stored tag map and GetInstanceProfile reported a resource
     * AWS could never have produced.
     */
    @Test
    void tagInstanceProfileBeyondFiftyTagsInOneRequestReturnsValidationError() {
        String profile = "tag-limit-" + UUID.randomUUID().toString().substring(0, 8);
        createProfile(profile);

        tagRequest(profile, 1, 51)
            .when().post("/")
            .then().statusCode(400)
            .body(containsString("ValidationError"));

        // Nothing was stored: the profile still reports no tags.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(200)
            .body(org.hamcrest.Matchers.not(containsString("key1")));
    }

    /**
     * The 50-tag cap is a per-resource quota, so two in-shape requests that together exceed it
     * are rejected as LimitExceeded rather than silently over-filling the stored tag map.
     */
    @Test
    void tagInstanceProfileBeyondFiftyTagsAcrossRequestsReturnsLimitExceeded() {
        String profile = "tag-quota-" + UUID.randomUUID().toString().substring(0, 8);
        createProfile(profile);

        tagRequest(profile, 1, 30).when().post("/").then().statusCode(200);
        tagRequest(profile, 31, 60)
            .when().post("/")
            .then().statusCode(409)
            .body(containsString("LimitExceeded"));

        // The second request was rejected whole: none of its keys landed.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(200)
            .body(org.hamcrest.Matchers.not(containsString("key60")));
    }

    /** {@code tagKeyListType} is {@code max: 50} on the request itself. */
    @Test
    void untagInstanceProfileBeyondFiftyKeysReturnsValidationError() {
        String profile = "untag-limit-" + UUID.randomUUID().toString().substring(0, 8);
        createProfile(profile);

        var request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UntagInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .header("Authorization", auth(ACCOUNT, "iam"));
        for (int i = 1; i <= 51; i++) {
            request.formParam("TagKeys.member." + i, "key" + i);
        }
        request.when().post("/")
            .then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void untagInstanceProfileMalformedNameReturnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UntagInstanceProfile")
            .formParam("InstanceProfileName", "not a valid name!")
            .formParam("TagKeys.member.1", "team")
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void tagNonexistentProfileReturnsNoSuchEntity() {
        String profile = "no-such-" + UUID.randomUUID().toString().substring(0, 8);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TagInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .formParam("Tags.member.1.Key", "team")
            .formParam("Tags.member.1.Value", "platform")
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    /**
     * {@code instanceProfileNameType} is {@code [\w+=,.@-]{1,128}}, so a name outside that shape
     * is a request-shape failure, checked before the profile is resolved — otherwise a malformed
     * name would surface as NoSuchEntity instead of the ValidationError AWS returns.
     */
    @Test
    void tagInstanceProfileMalformedNameReturnsValidationError() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TagInstanceProfile")
            .formParam("InstanceProfileName", "not a valid name!")
            .formParam("Tags.member.1.Key", "team")
            .formParam("Tags.member.1.Value", "platform")
            .header("Authorization", auth(ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("ValidationError"));
    }
}
