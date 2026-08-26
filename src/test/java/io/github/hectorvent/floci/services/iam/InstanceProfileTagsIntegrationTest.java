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

    /**
     * {@code tagListType} is {@code max: 50}, and AWS caps an instance profile at 50 tags.
     * Without the check the extra tags land in the profile's stored tag map and
     * GetInstanceProfile reports a resource AWS could never have produced.
     */
    @Test
    void tagInstanceProfileBeyondFiftyTagsReturnsLimitExceeded() {
        String profile = "tag-limit-" + UUID.randomUUID().toString().substring(0, 8);
        createProfile(profile);

        var request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TagInstanceProfile")
            .formParam("InstanceProfileName", profile)
            .header("Authorization", auth(ACCOUNT, "iam"));
        for (int i = 1; i <= 51; i++) {
            request.formParam("Tags.member." + i + ".Key", "key" + i)
                   .formParam("Tags.member." + i + ".Value", "value" + i);
        }
        request.when().post("/")
            .then().statusCode(409)
            .body(containsString("LimitExceeded"));

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
}
