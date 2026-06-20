package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end integration test verifying that presigned URL requests carrying
 * credentials only in {@code X-Amz-Credential} (no Authorization header)
 * correctly resolve the account ID and route to the right account's bucket.
 *
 * <p>This is the scenario that was previously broken: the AccountContextFilter
 * now falls back to X-Amz-Credential query param when Authorization is absent,
 * allowing presigned URL requests to be routed to the correct account's storage.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PreSignedUrlAccountResolutionIntegrationTest {

    private static final String BUCKET = "presigned-account-resolution-bucket";
    private static final String KEY = "account-resolved-object.txt";
    private static final String BODY = "content-for-account-000000000001";

    // 12-digit numeric AKID → treated as account ID
    private static final String ACCOUNT_1 = "000000000001";
    private static final String ACCOUNT_2 = "000000000002";

    private static final String AUTH_ACCOUNT_1 =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_1 + "/20260617/us-east-1/s3/aws4_request";
    private static final String AUTH_ACCOUNT_2 =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_2 + "/20260617/us-east-1/s3/aws4_request";

    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    @Order(1)
    void createBucketAndPutObjectUnderAccount1() {
        // Create bucket under account 000000000001
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);

        // Put object under account 000000000001
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .body(BODY)
            .contentType("text/plain")
        .when()
            .put("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void presignedUrlWithXAmzCredentialResolvesCorrectAccount() {
        // Craft a presigned GET URL that carries credentials ONLY in X-Amz-Credential
        // query parameter (no Authorization header). This is the scenario that was
        // previously broken: AccountContextFilter must fall back to X-Amz-Credential
        // when Authorization is absent.
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        String dateStamp = amzDate.substring(0, 8);
        String credential = ACCOUNT_1 + "/" + dateStamp + "/us-east-1/s3/aws4_request";

        String path = "/" + BUCKET + "/" + KEY
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + URLEncoder.encode(credential, StandardCharsets.UTF_8)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=fakesig";

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .body(equalTo(BODY));
    }

    @Test
    @Order(3)
    void presignedUrlWithWrongAccountCannotAccessOtherAccountsBucket() {
        // Verify isolation: when account 000000000002 tries to access a bucket
        // that belongs to account 000000000001, it should get NoSuchBucket (404)
        // because that bucket doesn't exist under account 2.
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        String dateStamp = amzDate.substring(0, 8);
        String credential = ACCOUNT_2 + "/" + dateStamp + "/us-east-1/s3/aws4_request";

        String path = "/" + BUCKET + "/" + KEY
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + URLEncoder.encode(credential, StandardCharsets.UTF_8)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=fakesig";

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(path)
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(4)
    void presignedUrlWithXAmzCredentialDoesNotFallBackToDefault() {
        // Verify that a presigned URL with X-Amz-Credential does NOT fall back to
        // the default account when the credential specifies a real 12-digit account.
        // This re-validates after the wrong-account isolation test to confirm no
        // state pollution occurred.
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        String dateStamp = amzDate.substring(0, 8);
        String credential = ACCOUNT_1 + "/" + dateStamp + "/us-east-1/s3/aws4_request";

        // A properly-credentialed presigned URL succeeds (re-validate after previous test)
        String path = "/" + BUCKET + "/" + KEY
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + URLEncoder.encode(credential, StandardCharsets.UTF_8)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=3600"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=fakesig";

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .body(equalTo(BODY));
    }

    @Test
    @Order(99)
    void cleanUp() {
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
        .when()
            .delete("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH_ACCOUNT_1)
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }
}
