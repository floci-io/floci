package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Verifies S3 object bytes are isolated between accounts, mirroring
 * {@link io.github.hectorvent.floci.core.common.AccountIsolationIntegrationTest}'s SQS coverage.
 * Two accounts may own a same-named bucket in Floci (bucket names are only unique per account
 * here, unlike real S3); object bytes must not collide when they do.
 */
@QuarkusTest
class S3AccountIsolationIntegrationTest {

    private static final String AUTH_ACCOUNT_1 =
            "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_ACCOUNT_2 =
            "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";

    @Test
    void objectsInSameNamedBucketAreIsolatedBetweenAccounts() {
        String bucket = "account-isolation-shared-bucket";

        createBucket(AUTH_ACCOUNT_1, bucket);
        createBucket(AUTH_ACCOUNT_2, bucket);

        // Both write the same key in their own bucket — exercises the byte-storage
        // collision directly, not just metadata isolation.
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .body("account-1-data")
        .when()
            .put("/" + bucket + "/shared-key.txt")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_ACCOUNT_2)
            .body("account-2-data")
        .when()
            .put("/" + bucket + "/shared-key.txt")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH_ACCOUNT_1)
        .when()
            .get("/" + bucket + "/shared-key.txt")
        .then()
            .statusCode(200)
            .body(containsString("account-1-data"));

        given()
            .header("Authorization", AUTH_ACCOUNT_2)
        .when()
            .get("/" + bucket + "/shared-key.txt")
        .then()
            .statusCode(200)
            .body(containsString("account-2-data"));
    }

    @Test
    void deletingBucketDoesNotRemoveAnotherAccountsSameNamedBucketData() {
        String bucket = "account-isolation-delete-bucket";

        createBucket(AUTH_ACCOUNT_1, bucket);
        createBucket(AUTH_ACCOUNT_2, bucket);

        given()
            .header("Authorization", AUTH_ACCOUNT_2)
            .body("account-2-data")
        .when()
            .put("/" + bucket + "/kept-key.txt")
        .then()
            .statusCode(200);

        // Account 1 deletes its own (empty) same-named bucket.
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
        .when()
            .delete("/" + bucket)
        .then()
            .statusCode(204);

        // Account 2's object must be unaffected.
        given()
            .header("Authorization", AUTH_ACCOUNT_2)
        .when()
            .get("/" + bucket + "/kept-key.txt")
        .then()
            .statusCode(200)
            .body(not(containsString("<Error>")));
    }

    private static void createBucket(String auth, String bucket) {
        given()
            .header("Authorization", auth)
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
    }
}
