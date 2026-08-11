package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Verifies that S3 object bytes are isolated between accounts, mirroring
 * {@link io.github.hectorvent.floci.core.common.AccountIsolationIntegrationTest}'s
 * SQS coverage.
 *
 * <p>Bucket metadata is already account-scoped via {@code AccountAwareStorageBackend}
 * (two accounts may each own a bucket with the same name — real S3 disallows this since
 * bucket names are globally unique, but that's a separate concern from what's tested
 * here), but object bytes ({@code S3Service.memoryDataStore} / on-disk files under
 * {@code dataRoot}) were keyed only by {@code bucketName + "/" + key}, with no account
 * component, so two accounts with a same-named bucket shared the same underlying bytes.
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

        // Both accounts write to the same key in their own (same-named) bucket. Object
        // metadata (per-account) would mask the leak if account 2 never wrote the key at
        // all, so this must exercise the actual byte-storage collision: account 2's write
        // must not overwrite what account 1 reads back, and vice versa.
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
