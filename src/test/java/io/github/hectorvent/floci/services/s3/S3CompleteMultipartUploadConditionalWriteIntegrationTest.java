package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

/**
 * "The first write operation to finish succeeds. Amazon S3 then fails subsequent writes with a 412":
 * a conditional CompleteMultipartUpload is decided when the object is stored, not by an earlier look
 * at the key. The spy writes the competing object right after the first lookup of the key, the one
 * the controller makes before it hands the upload to the service.
 */
@QuarkusTest
class S3CompleteMultipartUploadConditionalWriteIntegrationTest {

    @InjectSpy
    S3Service s3Service;

    @Test
    void anObjectWrittenAfterTheEarlyCheckStillFailsIfNoneMatch() {
        String bucket = "cond-complete-race-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "object.bin";
        given().when().put("/" + bucket).then().statusCode(200);
        String uploadId = given()
        .when()
            .post("/" + bucket + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String partETag = given()
            .body("part")
        .when()
            .put("/" + bucket + "/" + key + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .extract().header("ETag");
        AtomicBoolean competingWriteDone = new AtomicBoolean();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } finally {
                if (competingWriteDone.compareAndSet(false, true)) {
                    s3Service.putObject(bucket, key, "winner".getBytes(StandardCharsets.UTF_8), "text/plain",
                            Map.of());
                }
            }
        }).when(s3Service).headObject(bucket, key);
        try {
            given()
                .header("If-None-Match", "*")
                .contentType("application/xml")
                .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + partETag
                        + "</ETag></Part></CompleteMultipartUpload>")
            .when()
                .post("/" + bucket + "/" + key + "?uploadId=" + uploadId)
            .then()
                .statusCode(412)
                .body("Error.Code", equalTo("PreconditionFailed"))
                .body("Error.Condition", equalTo("If-None-Match"));

            assertTrue(competingWriteDone.get(), "the competing write never ran");
            given().when().get("/" + bucket + "/" + key).then().statusCode(200).body(equalTo("winner"));
            given()
            .when()
                .get("/" + bucket + "/" + key + "?uploadId=" + uploadId)
            .then()
                .statusCode(200)
                .body("ListPartsResult.Part.size()", equalTo(1));
        } finally {
            given().when().delete("/" + bucket + "/" + key + "?uploadId=" + uploadId);
            given().when().delete("/" + bucket + "/" + key);
            given().when().delete("/" + bucket);
        }
    }
}
