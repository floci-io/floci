package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * No spelling of a traversing bucket name reaches another account's objects. The status codes vary
 * by spelling: JAX-RS answers a literal {@code ..} or {@code .} path itself, while the encoded
 * forms reach the service undecoded and name an ordinary directory. {@code S3ServiceTest} pins the
 * service guard directly, for the callers that never come through this router.
 */
@QuarkusTest
class S3BucketNameTraversalIntegrationTest {

    private static final String VICTIM =
            "AWS4-HMAC-SHA256 Credential=000000000011/20260215/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String ATTACKER =
            "AWS4-HMAC-SHA256 Credential=000000000012/20260215/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";

    @ParameterizedTest
    @ValueSource(strings = {".accounts", ".versions", ".annotations"})
    void createBucketRefusesReservedStorageRoots(String bucketName) {
        given()
                .header("Authorization", ATTACKER)
        .when()
                .put("/" + bucketName)
        .then()
                .statusCode(400)
                .body(containsString("InvalidBucketName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", ".", "%2E%2E", "..%2Fvictim"})
    void noSpellingOfATraversalBucketReachesAnotherAccountsObject(String bucketName) {
        given()
                .header("Authorization", VICTIM)
        .when()
                .put("/traversal-victim-bucket")
        .then()
                .statusCode(anyOf(is(200), is(409)));

        given()
                .header("Authorization", VICTIM)
                .body("victim-secret")
        .when()
                .put("/traversal-victim-bucket/secret.txt")
        .then()
                .statusCode(200);

        given()
                .header("Authorization", ATTACKER)
        .when()
                .put("/" + bucketName);

        given()
                .header("Authorization", ATTACKER)
        .when()
                .get("/" + bucketName + "/000000000011/traversal-victim-bucket/secret.txt")
        .then()
                .statusCode(not(200))
                .body(not(containsString("victim-secret")));
    }
}
