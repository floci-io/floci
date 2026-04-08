package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3VersioningIntegrationTest {

    private static final String BUCKET = "versioning-int-test";
    private static String versionId1;
    private static String versionId2;

    @Test
    @Order(1)
    void createBucket() {
        given().when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void versioningNotEnabledByDefault() {
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200)
            .body(containsString("<VersioningConfiguration"))
            .body(not(containsString("<Status>")));
    }

    @Test
    @Order(3)
    void enableVersioning() {
        String xml = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>";
        given()
            .body(xml)
        .when()
            .put("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getVersioningStatus() {
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    @Order(5)
    void putObjectReturnsVersionId() {
        versionId1 = given()
            .body("Version 1 content")
            .contentType("text/plain")
        .when()
            .put("/" + BUCKET + "/test.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", notNullValue())
            .extract().header("x-amz-version-id");
    }

    @Test
    @Order(6)
    void putObjectSecondVersion() {
        versionId2 = given()
            .body("Version 2 content")
            .contentType("text/plain")
        .when()
            .put("/" + BUCKET + "/test.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", notNullValue())
            .extract().header("x-amz-version-id");

        assertNotEquals(versionId1, versionId2);
    }

    @Test
    @Order(7)
    void getLatestVersion() {
        given()
        .when()
            .get("/" + BUCKET + "/test.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", versionId2)
            .body(equalTo("Version 2 content"));
    }

    @Test
    @Order(8)
    void getSpecificVersion() {
        given()
        .when()
            .get("/" + BUCKET + "/test.txt?versionId=" + versionId1)
        .then()
            .statusCode(200)
            .header("x-amz-version-id", versionId1)
            .body(equalTo("Version 1 content"));
    }

    @Test
    @Order(9)
    void getObjectAttributesSpecificVersion() {
        given()
            .header("x-amz-object-attributes", "ETag,ObjectSize,StorageClass")
        .when()
            .get("/" + BUCKET + "/test.txt?attributes&versionId=" + versionId1)
        .then()
            .statusCode(200)
            .header("x-amz-version-id", versionId1)
            .body(containsString("<GetObjectAttributesResponse"))
            .body(containsString("<ObjectSize>17</ObjectSize>"))
            .body(containsString("<StorageClass>STANDARD</StorageClass>"));
    }

    @Test
    @Order(10)
    void listObjectVersions() {
        given()
        .when()
            .get("/" + BUCKET + "?versions")
        .then()
            .statusCode(200)
            .body(containsString("<ListVersionsResult"))
            .body(containsString("<IsTruncated>false</IsTruncated>"))
            .body(containsString("<Version>"))
            .body(containsString(versionId1))
            .body(containsString(versionId2));
    }

    @Test
    @Order(11)
    void deleteCreatesMarker() {
        given()
        .when()
            .delete("/" + BUCKET + "/test.txt")
        .then()
            .statusCode(204)
            .header("x-amz-delete-marker", "true");
    }

    @Test
    @Order(12)
    void getAfterDeleteReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "/test.txt")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void getSpecificVersionAfterDelete() {
        // Specific version should still be accessible
        given()
        .when()
            .get("/" + BUCKET + "/test.txt?versionId=" + versionId1)
        .then()
            .statusCode(200)
            .body(equalTo("Version 1 content"));
    }

    @Test
    @Order(14)
    void listVersionsShowsDeleteMarker() {
        given()
        .when()
            .get("/" + BUCKET + "?versions")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteMarker>"));
    }

    @Test
    @Order(15)
    void cleanUp() {
        // Delete specific versions permanently
        given().when().delete("/" + BUCKET + "/test.txt?versionId=" + versionId1).then().statusCode(204);
        given().when().delete("/" + BUCKET + "/test.txt?versionId=" + versionId2).then().statusCode(204);
    }
}
