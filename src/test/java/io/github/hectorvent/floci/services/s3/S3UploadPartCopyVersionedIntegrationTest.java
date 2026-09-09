package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * UploadPartCopy with {@code x-amz-copy-source} including {@code ?versionId=...} reads the matching
 * object version bytes (same contract as CopyObject).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3UploadPartCopyVersionedIntegrationTest {

    private static final String BUCKET = "upload-part-copy-ver-it";
    private static final String SRC_KEY = "versioned-mp-source.txt";
    private static final String DEST_KEY = "versioned-mp-dest.bin";
    private static String sourceV1VersionId;
    private static String uploadId;
    private static String copiedPartETag;

    @Test
    @Order(1)
    void createBucketAndVersioning() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        String xml = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>";
        given().body(xml).when().put("/" + BUCKET + "?versioning").then().statusCode(200);
    }

    @Test
    @Order(2)
    void putSourceVersion1CaptureVersionId() {
        sourceV1VersionId = given()
                .contentType("text/plain")
                .body("version-one-body")
                .when()
                .put("/" + BUCKET + "/" + SRC_KEY)
                .then()
                .statusCode(200)
                .header("x-amz-version-id", notNullValue())
                .extract()
                .header("x-amz-version-id");
    }

    @Test
    @Order(3)
    void overwriteSourceVersion2() {
        given()
                .contentType("text/plain")
                .body("version-two-body")
                .when()
                .put("/" + BUCKET + "/" + SRC_KEY)
                .then()
                .statusCode(200)
                .header("x-amz-version-id", notNullValue());

        given()
                .when()
                .get("/" + BUCKET + "/" + SRC_KEY)
                .then()
                .statusCode(200)
                .body(equalTo("version-two-body"));
    }

    @Test
    @Order(4)
    void initiateMultipartDest() {
        uploadId = given()
                .when()
                .post("/" + BUCKET + "/" + DEST_KEY + "?uploads")
                .then()
                .statusCode(200)
                .body(containsString("<UploadId>"))
                .extract()
                .xmlPath()
                .getString("InitiateMultipartUploadResult.UploadId");
    }

    @Test
    @Order(5)
    void uploadPartCopyUsesOlderVersion() {
        copiedPartETag = given()
                .header("x-amz-copy-source", "/" + BUCKET + "/" + SRC_KEY + "?versionId=" + sourceV1VersionId)
                .when()
                .put("/" + BUCKET + "/" + DEST_KEY + "?uploadId=" + uploadId + "&partNumber=1")
                .then()
                .statusCode(200)
                .body(containsString("<CopyPartResult"))
                .body(containsString("<ETag>"))
                .extract().xmlPath().getString("CopyPartResult.ETag");
    }

    @Test
    @Order(6)
    void completeAndVerifyBodyFromVersion1() {
        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(copiedPartETag);

        given()
                .contentType("application/xml")
                .body(completeXml)
                .when()
                .post("/" + BUCKET + "/" + DEST_KEY + "?uploadId=" + uploadId)
                .then()
                .statusCode(200)
                .body(containsString("<CompleteMultipartUploadResult"));

        given()
                .when()
                .get("/" + BUCKET + "/" + DEST_KEY)
                .then()
                .statusCode(200)
                .body(equalTo("version-one-body"));
    }
}
