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
 * Mirrors the AWS CLI flow: versioned bucket, two puts, CopyObject with
 * {@code x-amz-copy-source: /bucket/key?versionId=} to restore an older version as the latest.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3CopyObjectVersionedIntegrationTest {

    private static final String BUCKET = "copy-version-repro-it";
    /** First object version id (after v1 upload, while still latest). */
    private static String v1VersionId;

    @Test
    @Order(1)
    void createBucket() {
        given().when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void enableVersioning() {
        String xml = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>";
        given().body(xml).when().put("/" + BUCKET + "?versioning").then().statusCode(200);
    }

    @Test
    @Order(3)
    void uploadVersion1AndCaptureVersionId() {
        v1VersionId = given()
                .contentType("text/plain")
                .body("v1")
                .when()
                .put("/" + BUCKET + "/key")
                .then()
                .statusCode(200)
                .header("x-amz-version-id", notNullValue())
                .extract()
                .header("x-amz-version-id");
    }

    @Test
    @Order(4)
    void uploadVersion2() {
        given()
                .contentType("text/plain")
                .body("v2")
                .when()
                .put("/" + BUCKET + "/key")
                .then()
                .statusCode(200)
                .header("x-amz-version-id", notNullValue());
    }

    @Test
    @Order(5)
    void latestIsVersion2BeforeCopy() {
        given()
                .when()
                .get("/" + BUCKET + "/key")
                .then()
                .statusCode(200)
                .body(equalTo("v2"));
    }

    @Test
    @Order(6)
    void copyObjectFromV1RestoresV1AsLatest() {
        given()
                .header("x-amz-copy-source", "/" + BUCKET + "/key?versionId=" + v1VersionId)
                .when()
                .put("/" + BUCKET + "/key")
                .then()
                .statusCode(200)
                .body(containsString("CopyObjectResult"));

        given()
                .when()
                .get("/" + BUCKET + "/key")
                .then()
                .statusCode(200)
                .body(equalTo("v1"));
    }
}
