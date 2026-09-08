package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression coverage for presigned POST (browser-form multipart upload) rejecting fabricated
 * or absent credentials once {@code floci.services.s3.enforce-auth} is enabled: see the fix in
 * {@code S3Controller#validatePresignedPostAuth}.
 */
@QuarkusTest
@TestProfile(S3PresignedPostAuthEnforcementIntegrationTest.S3AuthProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3PresignedPostAuthEnforcementIntegrationTest {

    public static final class S3AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }

    private static final String BUCKET = "presigned-post-auth-bucket";
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";
    // Matches the "20260101" date embedded in every credential scope built by this test class.
    private static final String AMZ_DATE = "20260101T000000Z";

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(10)
    void rejectsUploadWithNoPolicyAndFabricatedCredentials() {
        given()
            .multiPart("key", "anon-upload.txt")
            .multiPart("x-amz-credential", "totally-fake/20260101/us-east-1/s3/aws4_request")
            .multiPart("x-amz-signature", "0000000000000000000000000000000000000000000000000000000000dead")
            .multiPart("file", "anon-upload.txt",
                    "uploaded with no real credentials".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    @Test
    @Order(11)
    void rejectsUploadWithValidPolicyButFabricatedSignature() {
        String key = "fabricated-signature.txt";
        String policyBase64 = buildPolicyBase64(BUCKET, key);

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature",
                    "0000000000000000000000000000000000000000000000000000000000dead")
            .multiPart("file", "fabricated-signature.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("SignatureDoesNotMatch"));

        given()
            .header("Authorization", authorizationHeader(LEGACY_ACCESS_KEY_ID))
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(12)
    void rejectsUploadWithUnknownAccessKeyId() {
        String key = "unknown-key.txt";
        String policyBase64 = buildPolicyBase64(BUCKET, key);
        String credential = "AKIAUNKNOWNACCESSKEY/20260101/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, "some-random-secret");

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "unknown-key.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(13)
    void rejectsUploadMissingRequiredFormFields() {
        String key = "missing-date.txt";
        String policyBase64 = buildPolicyBase64(BUCKET, key);
        String credential = LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        // Missing x-amz-date entirely, even though the policy and signature are otherwise genuine.
        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "missing-date.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    @Test
    @Order(14)
    void rejectsUploadWithMalformedCredentialScope() {
        String key = "malformed-scope.txt";
        String policyBase64 = buildPolicyBase64(BUCKET, key);
        // Wrong service ("ec2" instead of "s3") in an otherwise well-formed credential scope.
        String credential = LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/ec2/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "malformed-scope.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    @Test
    @Order(15)
    void rejectsUploadWhereFormDateDisagreesWithCredentialScope() {
        String key = "date-mismatch.txt";
        String policyBase64 = buildPolicyBase64(BUCKET, key);
        String credential = LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        // x-amz-date's date (20260102) disagrees with the credential scope's date (20260101).
        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", "20260102T000000Z")
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "date-mismatch.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    @Test
    @Order(16)
    void rejectsUploadWithImpossibleFormDate() {
        String key = "impossible-date.txt";
        String policyBase64 = buildPolicyBase64(BUCKET, key);
        // "20261332" has the right digit shape but month 13 / day 32 don't exist; same for the
        // 99:99:99 time. The credential scope's date is made to match digit-for-digit so only
        // strict calendar parsing (not a same-date-string comparison) can catch this.
        String credential = LEGACY_ACCESS_KEY_ID + "/20261332/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", "20261332T999999Z")
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "impossible-date.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    @Test
    @Order(20)
    void acceptsUploadWithGenuineSignature() {
        String key = "uploads/genuine.txt";
        String fileContent = "uploaded with a real signature";
        String policyBase64 = buildPolicyBase64(BUCKET, key);
        String credential = LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "genuine.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204)
            .header("ETag", notNullValue());

        given()
            .header("Authorization", authorizationHeader(LEGACY_ACCESS_KEY_ID))
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .body(org.hamcrest.Matchers.equalTo(fileContent));
    }

    @Test
    @Order(21)
    void rejectsExpiredPolicyEvenWithGenuineSignature() {
        String key = "uploads/expired.txt";
        String expiration = Instant.now().minusSeconds(3600)
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        String policy = """
                {
                  "expiration": "%s",
                  "conditions": [
                    {"bucket": "%s"},
                    {"key": "%s"}
                  ]
                }
                """.formatted(expiration, BUCKET, key);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
        String credential = LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "expired.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    @Test
    @Order(22)
    void rejectsPolicyWithNoExpirationEvenWithGenuineSignature() {
        String key = "uploads/no-expiration.txt";
        String policy = """
                {
                  "conditions": [
                    {"bucket": "%s"},
                    {"key": "%s"}
                  ]
                }
                """.formatted(BUCKET, key);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
        String credential = LEGACY_ACCESS_KEY_ID + "/20260101/us-east-1/s3/aws4_request";
        String signature = signPolicy(policyBase64, credential, LEGACY_SECRET_KEY);

        given()
            .multiPart("key", key)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", signature)
            .multiPart("file", "no-expiration.txt",
                    "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", org.hamcrest.Matchers.equalTo("AccessDenied"));
    }

    private static String authorizationHeader(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260101/us-east-1/s3/aws4_request"
                + ", SignedHeaders=host;x-amz-date, Signature=test";
    }

    private static String buildPolicyBase64(String bucket, String key) {
        String expiration = Instant.now().plusSeconds(3600)
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        String policy = """
                {
                  "expiration": "%s",
                  "conditions": [
                    {"bucket": "%s"},
                    {"key": "%s"}
                  ]
                }
                """.formatted(expiration, bucket, key);
        return Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
    }

    private static String signPolicy(String policyBase64, String credential, String secretKey) {
        try {
            String[] parts = credential.split("/");
            String date = parts[1];
            String region = parts[2];
            String service = parts[3];
            byte[] signingKey = deriveSigningKey(secretKey, date, region, service);
            return hexEncode(hmacSha256(signingKey, policyBase64));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region,
                                           String service) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
