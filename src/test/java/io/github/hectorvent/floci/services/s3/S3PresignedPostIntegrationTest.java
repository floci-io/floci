package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3PresignedPostIntegrationTest {

    private static final String BUCKET = "presigned-post-bucket";
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

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
    void presignedPostUploadsObject() {
        String key = "uploads/test-file.txt";
        String fileContent = "Hello from presigned POST!";
        String contentType = "text/plain";

        String policy = buildPolicy(BUCKET, key, contentType, 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", contentType)
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "test-file.txt", fileContent.getBytes(StandardCharsets.UTF_8), contentType)
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204)
            .header("ETag", notNullValue());

        // Verify the object was stored correctly
        given()
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .header("Content-Type", equalTo(contentType))
            .body(equalTo(fileContent));
    }

    @Test
    @Order(20)
    void presignedPostWithBinaryData() {
        String key = "uploads/binary-data.bin";
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }

        String policy = buildPolicy(BUCKET, key, "application/octet-stream", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "application/octet-stream")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "binary-data.bin", binaryData, "application/octet-stream")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204)
            .header("ETag", notNullValue());

        // Verify the binary object was stored correctly
        byte[] retrieved = given()
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .extract().body().asByteArray();

        org.junit.jupiter.api.Assertions.assertArrayEquals(binaryData, retrieved);
    }

    @Test
    @Order(30)
    void presignedPostRejectsExceedingContentLength() {
        String key = "uploads/too-large.txt";
        // Create data that exceeds the max content-length-range of 10 bytes
        String fileContent = "This content is definitely longer than 10 bytes";

        String policy = buildPolicy(BUCKET, key, "text/plain", 0, 10);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "too-large.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("EntityTooLarge")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Your proposed upload exceeds the maximum allowed size.")));
    }

    @Test
    @Order(40)
    void presignedPostRequiresKeyField() {
        given()
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", "dummypolicy")
            .multiPart("file", "test.txt", "content".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("InvalidArgument")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Bucket POST must contain a field named 'key'.")));
    }

    @Test
    @Order(50)
    void presignedPostRequiresFileField() {
        given()
            .multiPart("key", "uploads/no-file.txt")
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", "dummypolicy")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("InvalidArgument")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Bucket POST must contain a file field.")));
    }

    @Test
    @Order(60)
    void presignedPostWithoutPolicySkipsValidation() {
        String key = "uploads/no-policy.txt";
        String fileContent = "Uploaded without policy";

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("file", "no-policy.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204)
            .header("ETag", notNullValue());

        // Verify the object was stored
        given()
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .body(equalTo(fileContent));
    }

    @Test
    @Order(70)
    void presignedPostContentTypeFromFormField() {
        String key = "uploads/typed-file.json";
        String fileContent = "{\"test\": true}";

        String policy = buildPolicy(BUCKET, key, "application/json", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "application/json")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "typed-file.json", fileContent.getBytes(StandardCharsets.UTF_8), "application/octet-stream")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204);

        // Content-Type should come from the form field, not the file part
        given()
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/json"));
    }

    @Test
    @Order(80)
    void presignedPostToNonExistentBucketFails() {
        given()
            .multiPart("key", "test.txt")
            .multiPart("file", "test.txt", "data".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/nonexistent-presigned-bucket")
        .then()
            .statusCode(404)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("NoSuchBucket")));
    }

    @Test
    @Order(90)
    void presignedPostWithContentLengthWithinRange() {
        String key = "uploads/within-range.txt";
        // Exactly 5 bytes, within range [1, 100]
        String fileContent = "12345";

        String policy = buildPolicy(BUCKET, key, "text/plain", 1, 100);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "within-range.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(91)
    void presignedPostRejectsContentTypeMismatch() {
        String key = "uploads/ct-mismatch.png";
        String fileContent = "not a real png";

        String policy = buildPolicy(BUCKET, key, "image/png", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "image/gif")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "ct-mismatch.png", fileContent.getBytes(StandardCharsets.UTF_8), "image/gif")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("AccessDenied")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Invalid according to Policy: Policy Condition failed: "
                            + "[\"eq\", \"$Content-Type\", \"image/png\"]")));
    }

    @Test
    @Order(92)
    void presignedPostRejectsKeyMismatch() {
        String key = "uploads/wrong-key.txt";
        String fileContent = "test content";

        String policy = buildPolicy(BUCKET, "uploads/expected-key.txt", "text/plain", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "wrong-key.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("AccessDenied")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Invalid according to Policy: Policy Condition failed: "
                            + "[\"eq\", \"$key\", \"uploads/expected-key.txt\"]")));
    }

    @Test
    @Order(93)
    void presignedPostWithStartsWithCondition() {
        String key = "uploads/prefix-test.txt";
        String fileContent = "starts-with test";

        String policy = buildStartsWithPolicy(BUCKET, "uploads/", "text/", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "prefix-test.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(94)
    void presignedPostRejectsStartsWithMismatch() {
        String key = "other/wrong-prefix.txt";
        String fileContent = "starts-with mismatch";

        String policy = buildStartsWithPolicy(BUCKET, "uploads/", "text/", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "wrong-prefix.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("AccessDenied")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Invalid according to Policy: Policy Condition failed: "
                            + "[\"starts-with\", \"$key\", \"uploads/\"]")));
    }

    @Test
    @Order(94)
    void presignedPostReturnsXmlErrorResponseBody() {
        // Verify the raw XML wire format matches what AWS S3 and LocalStack return.
        // This ensures clients that parse the raw response body (e.g. seadn) see the
        // expected XML structure with &quot;-encoded quotes, not JSON.
        String key = "uploads/xml-error-check.png";
        String fileContent = "not a real png";

        String policy = buildPolicy(BUCKET, key, "image/png", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        String responseBody =
            given()
                .multiPart("key", key)
                .multiPart("Content-Type", "image/gif")
                .multiPart("policy", policyBase64)
                .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
                .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
                .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
                .multiPart("x-amz-signature", "dummysignature")
                .multiPart("file", "xml-error-check.png", fileContent.getBytes(StandardCharsets.UTF_8), "image/gif")
            .when()
                .post("/" + BUCKET)
            .then()
                .statusCode(403)
                .contentType("application/xml")
                .extract().body().asString();

        // Assert the exact XML structure, matching what AWS S3 and LocalStack return.
        // The RequestId is a random UUID, so we match it with a regex.
        assertThat(responseBody, matchesRegex(
                "\\Q<?xml version=\"1.0\" encoding=\"UTF-8\"?>\\E"
                        + "\\Q<Error>\\E"
                        + "\\Q<Code>AccessDenied</Code>\\E"
                        + "\\Q<Message>Invalid according to Policy: Policy Condition failed: \\E"
                        + "\\Q[&quot;eq&quot;, &quot;$Content-Type&quot;, &quot;image/png&quot;]</Message>\\E"
                        + "<RequestId>[0-9a-f\\-]+</RequestId>"
                        + "\\Q</Error>\\E"));
    }

    @Test
    @Order(95)
    void presignedPostEnforcesPolicyWithCapitalPFieldName() {
        // The AWS SDK sends the policy field as "Policy" (capital P).
        // This test verifies that validation works regardless of casing.
        String key = "uploads/capital-p-reject.png";
        String fileContent = "not a real png";

        String policy = buildPolicy(BUCKET, key, "image/png", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        // Send with capital-P "Policy" and mismatched Content-Type — should be rejected
        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "image/gif")
            .multiPart("Policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "capital-p-reject.png", fileContent.getBytes(StandardCharsets.UTF_8), "image/gif")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .contentType("application/xml")
            .body(hasXPath("/Error/Code", equalTo("AccessDenied")))
            .body(hasXPath("/Error/Message", equalTo(
                    "Invalid according to Policy: Policy Condition failed: "
                            + "[\"eq\", \"$Content-Type\", \"image/png\"]")));
    }

    @Test
    @Order(96)
    void presignedPostSucceedsWithCapitalPFieldName() {
        // Verify that a valid upload with capital-P "Policy" also succeeds
        String key = "uploads/capital-p-ok.txt";
        String fileContent = "capital P success";

        String policy = buildPolicy(BUCKET, key, "text/plain", 0, 10485760);
        String policyBase64 = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .multiPart("key", key)
            .multiPart("Content-Type", "text/plain")
            .multiPart("Policy", policyBase64)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "AKIAIOSFODNN7EXAMPLE/20260330/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE_FORMAT.format(Instant.now()))
            .multiPart("x-amz-signature", "dummysignature")
            .multiPart("file", "capital-p-ok.txt", fileContent.getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(100)
    void cleanupBucket() {
        // Delete all objects
        given().delete("/" + BUCKET + "/uploads/test-file.txt");
        given().delete("/" + BUCKET + "/uploads/binary-data.bin");
        given().delete("/" + BUCKET + "/uploads/no-policy.txt");
        given().delete("/" + BUCKET + "/uploads/typed-file.json");
        given().delete("/" + BUCKET + "/uploads/within-range.txt");
        given().delete("/" + BUCKET + "/uploads/prefix-test.txt");
        given().delete("/" + BUCKET + "/uploads/capital-p-ok.txt");

        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    private String buildPolicy(String bucket, String key, String contentType, long minSize, long maxSize) {
        String expiration = Instant.now().plusSeconds(3600)
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        return """
                {
                  "expiration": "%s",
                  "conditions": [
                    {"bucket": "%s"},
                    {"key": "%s"},
                    {"Content-Type": "%s"},
                    ["content-length-range", %d, %d]
                  ]
                }
                """.formatted(expiration, bucket, key, contentType, minSize, maxSize);
    }

    private String buildStartsWithPolicy(String bucket, String keyPrefix, String contentTypePrefix,
                                         long minSize, long maxSize) {
        String expiration = Instant.now().plusSeconds(3600)
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        return """
                {
                  "expiration": "%s",
                  "conditions": [
                    {"bucket": "%s"},
                    ["starts-with", "$key", "%s"],
                    ["starts-with", "$Content-Type", "%s"],
                    ["content-length-range", %d, %d]
                  ]
                }
                """.formatted(expiration, bucket, keyPrefix, contentTypePrefix, minSize, maxSize);
    }
}
