package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for AWS's InvalidArgument response for an invalid multipart part number. */
@QuarkusTest
class S3UploadPartValidationIntegrationTest {

    private static final String[] INVALID_PART_NUMBERS = {"-1", "0", "10001", "abc", "4294967297"};

    @TestHTTPResource("/")
    static URI baseUri;

    @Inject
    S3Service s3Service;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void uploadPartRejectsInvalidPartNumbersWithTheAwsErrorDetails() {
        String bucket = createBucket("upload-part-validation");
        String key = "object.bin";
        String uploadId = createUpload(bucket, key);
        cleanup.register(() -> abortUpload(bucket, key, uploadId));

        for (String partNumber : INVALID_PART_NUMBERS) {
            assertInvalidPartNumber(bucket, key, uploadId, partNumber, null);
        }
    }

    @Test
    void uploadPartCopyRejectsInvalidPartNumbersWithTheAwsErrorDetails() {
        String bucket = createBucket("upload-part-copy-validation");
        String key = "copy.bin";
        given().body("source").when().put("/" + bucket + "/source.bin").then().statusCode(200);
        cleanup.register(() -> given().when().delete("/" + bucket + "/source.bin").then().statusCode(204));
        String uploadId = createUpload(bucket, key);
        cleanup.register(() -> abortUpload(bucket, key, uploadId));

        for (String partNumber : INVALID_PART_NUMBERS) {
            assertInvalidPartNumber(bucket, key, uploadId, partNumber, "/" + bucket + "/source.bin");
        }
    }

    private void assertInvalidPartNumber(String bucket, String key, String uploadId,
                                         String partNumber, String copySource) {
        RequestSpecification request = given().body("part");
        if (copySource != null) {
            request.header("x-amz-copy-source", copySource);
        }
        request.when()
                .put("/" + bucket + "/" + key + "?uploadId=" + uploadId + "&partNumber=" + partNumber)
                .then()
                .statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Message", equalTo("Part number must be an integer between 1 and 10000, inclusive"))
                .body("Error.ArgumentName", equalTo("partNumber"))
                .body("Error.ArgumentValue", equalTo(partNumber));

        assertTrue(s3Service.listParts(bucket, key, uploadId).getParts().isEmpty(),
                "Rejected part " + partNumber + " must not be recorded");
    }

    private String createBucket(String prefix) {
        String bucket = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth("test", "s3"), bucket));
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static String createUpload(String bucket, String key) {
        return given().when()
                .post("/" + bucket + "/" + key + "?uploads")
                .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private static void abortUpload(String bucket, String key, String uploadId) {
        given().when().delete("/" + bucket + "/" + key + "?uploadId=" + uploadId).then().statusCode(204);
    }
}
