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
    private static String headersTestVersionId;
    private static String headersTestMarkerId;

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

    @Test
    @Order(16)
    void deleteRegularVersionReturnsVersionIdHeaderOnly() {
        headersTestVersionId = given()
            .body("headers-test")
            .contentType("text/plain")
        .when()
            .put("/" + BUCKET + "/headers.txt")
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        given()
        .when()
            .delete("/" + BUCKET + "/headers.txt?versionId=" + headersTestVersionId)
        .then()
            .statusCode(204)
            .header("x-amz-version-id", headersTestVersionId)
            .header("x-amz-delete-marker", nullValue());
    }

    @Test
    @Order(17)
    void deleteMarkerByVersionIdReturnsBothHeaders() {
        given()
            .body("restore-test")
            .contentType("text/plain")
        .when()
            .put("/" + BUCKET + "/restore.txt")
        .then()
            .statusCode(200);

        headersTestMarkerId = given()
        .when()
            .delete("/" + BUCKET + "/restore.txt")
        .then()
            .statusCode(204)
            .header("x-amz-delete-marker", "true")
            .extract().header("x-amz-version-id");

        given()
        .when()
            .delete("/" + BUCKET + "/restore.txt?versionId=" + headersTestMarkerId)
        .then()
            .statusCode(204)
            .header("x-amz-version-id", headersTestMarkerId)
            .header("x-amz-delete-marker", "true");
    }

    @Test
    @Order(18)
    void rollbackScenario_copyWithReplaceMetadataPreservesVersionKey() {
        String rollbackBucket = "rollback-scenario-test";
        given().when().put("/" + rollbackBucket).then().statusCode(200);
        String xml = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>";
        given().body(xml).when().put("/" + rollbackBucket + "?versioning").then().statusCode(200);

        String key = "bank/config.properties";

        // Upload v1 with metadata version=v1
        String vid1 = given()
            .body("v1-content")
            .contentType("text/plain")
            .header("x-amz-meta-version", "20231103")
        .when()
            .put("/" + rollbackBucket + "/" + key)
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        // Tag v1
        String tagXml = "<Tagging><TagSet><Tag><Key>version</Key><Value>20231103</Value></Tag></TagSet></Tagging>";
        given().body(tagXml).when().put("/" + rollbackBucket + "/" + key + "?tagging").then().statusCode(200);

        // Upload v2 with metadata version=v2
        given()
            .body("v2-content")
            .contentType("text/plain")
            .header("x-amz-meta-version", "20231204")
        .when()
            .put("/" + rollbackBucket + "/" + key)
        .then()
            .statusCode(200);

        // Tag v2 (latest)
        String tagXml2 = "<Tagging><TagSet><Tag><Key>version</Key><Value>20231204</Value></Tag></TagSet></Tagging>";
        given().body(tagXml2).when().put("/" + rollbackBucket + "/" + key + "?tagging").then().statusCode(200);

        // HEAD latest — should have version=20231204
        given()
        .when()
            .head("/" + rollbackBucket + "/" + key)
        .then()
            .statusCode(200)
            .header("x-amz-meta-version", "20231204");

        // HEAD v1 by versionId — should have version=20231103
        given()
        .when()
            .head("/" + rollbackBucket + "/" + key + "?versionId=" + vid1)
        .then()
            .statusCode(200)
            .header("x-amz-meta-version", "20231103");

        // Rollback copy: copy v1 to same key with MetadataDirective=REPLACE, setting last_operation + version
        given()
            .header("x-amz-copy-source", "/" + rollbackBucket + "/" + key + "?versionId=" + vid1)
            .header("x-amz-metadata-directive", "REPLACE")
            .header("x-amz-meta-last_operation", "roll_back")
            .header("x-amz-meta-version", "20231103")
        .when()
            .put("/" + rollbackBucket + "/" + key)
        .then()
            .statusCode(200);

        // HEAD after rollback — both metadata keys must be present
        given()
        .when()
            .head("/" + rollbackBucket + "/" + key)
        .then()
            .statusCode(200)
            .header("x-amz-meta-last_operation", "roll_back")
            .header("x-amz-meta-version", "20231103");

        // GET tagging after rollback — source tags should be preserved (TaggingDirective defaults to COPY)
        given()
        .when()
            .get("/" + rollbackBucket + "/" + key + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>version</Key>"))
            .body(containsString("<Value>20231103</Value>"));
    }

    @Test
    @Order(19)
    void deleteAllVersionsThenReuploadSameContent_noResurrection() {
        String resBucket = "resurrection-test";
        given().when().put("/" + resBucket).then().statusCode(200);
        String xml = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>";
        given().body(xml).when().put("/" + resBucket + "?versioning").then().statusCode(200);

        String key = "resurrection-test.txt";

        // Upload two versions
        String vid1 = given()
            .body("v1-content")
            .contentType("text/plain")
        .when()
            .put("/" + resBucket + "/" + key)
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        String vid2 = given()
            .body("v2-content")
            .contentType("text/plain")
        .when()
            .put("/" + resBucket + "/" + key)
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        // Permanently delete both versions (latest first)
        given().when().delete("/" + resBucket + "/" + key + "?versionId=" + vid2).then().statusCode(204);
        given().when().delete("/" + resBucket + "/" + key + "?versionId=" + vid1).then().statusCode(204);

        // Confirm zero versions for this key
        String afterDeleteXml = given()
        .when()
            .get("/" + resBucket + "?versions&prefix=" + key)
        .then()
            .statusCode(200)
            .extract().body().asString();
        assertFalse(afterDeleteXml.contains("<Version>"),
                "no versions should remain after permanent delete, got: " + afterDeleteXml);

        // Re-upload with same content as v1
        given()
            .body("v1-content")
            .contentType("text/plain")
        .when()
            .put("/" + resBucket + "/" + key)
        .then()
            .statusCode(200);

        // Verify exactly 1 version exists
        var versions = given()
        .when()
            .get("/" + resBucket + "?versions&prefix=" + key)
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getList("ListVersionsResult.Version");

        assertEquals(1, versions.size(),
                "only the newly uploaded version should exist — deleted versions must not resurrect");
    }
}
