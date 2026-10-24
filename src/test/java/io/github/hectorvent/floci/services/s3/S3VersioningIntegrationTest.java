package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
        List<Object> versions = given()
        .when()
            .get("/" + resBucket + "?versions&prefix=" + key)
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getList("ListVersionsResult.Version");

        assertEquals(1, versions.size(),
                "only the newly uploaded version should exist — deleted versions must not resurrect");
    }

    @Test
    @Order(20)
    void deleteSpecificVersionSendsObjectRemovedDeleteToQueue() {
        String notifBucket = "version-delete-notif-test";
        given().when().put("/" + notifBucket).then().statusCode(200);
        given()
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + notifBucket + "?versioning")
        .then()
            .statusCode(200);

        String queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "version-delete-notif-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        try {
            given()
                .contentType("application/xml")
                .queryParam("notification", "")
                .body("""
                    <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <QueueConfiguration>
                            <Id>removed</Id>
                            <Queue>arn:aws:sqs:us-east-1:000000000000:version-delete-notif-queue</Queue>
                            <Event>s3:ObjectRemoved:*</Event>
                        </QueueConfiguration>
                    </NotificationConfiguration>
                """)
            .when()
                .put("/" + notifBucket)
            .then()
                .statusCode(200);

            String validationReceipt = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ReceiveMessageResponse.ReceiveMessageResult.Message.Body",
                    containsString("\"Event\":\"s3:TestEvent\""))
                .extract().xmlPath().getString("ReceiveMessageResponse.ReceiveMessageResult.Message.ReceiptHandle");
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("ReceiptHandle", validationReceipt)
            .when()
                .post("/")
            .then()
                .statusCode(200);

            String olderVersionId = given()
                .body("v1")
                .contentType("text/plain")
            .when()
                .put("/" + notifBucket + "/k.txt")
            .then()
                .statusCode(200)
                .extract().header("x-amz-version-id");
            given().body("v2").contentType("text/plain").when().put("/" + notifBucket + "/k.txt")
                .then().statusCode(200);

            given()
            .when()
                .delete("/" + notifBucket + "/k.txt?versionId=" + olderVersionId)
            .then()
                .statusCode(204);

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ReceiveMessageResponse.ReceiveMessageResult.Message.Body", allOf(
                    containsString("\"eventName\":\"ObjectRemoved:Delete\""),
                    containsString("\"versionId\":\"" + olderVersionId + "\"")));
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", queueUrl)
                .post("/");
        }
    }

    @Test
    @Order(21)
    void deleteBucketRejectsVersionsBehindDeleteMarker() {
        String bucket = "versioned-delete-bucket-test";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        String versionId = given().body("data").when().put("/" + bucket + "/key")
                .then().statusCode(200).extract().header("x-amz-version-id");
        String markerId = given().when().delete("/" + bucket + "/key")
                .then().statusCode(204).extract().header("x-amz-version-id");

        given().when().delete("/" + bucket).then().statusCode(409)
                .body(containsString("<Code>BucketNotEmpty</Code>"));

        given().when().delete("/" + bucket + "/key?versionId=" + markerId).then().statusCode(204);
        given().when().delete("/" + bucket + "/key?versionId=" + versionId).then().statusCode(204);
        given().when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    @Order(22)
    void taggingActsOnTheVersionNamed() {
        String bucket = "versioned-tagging-test";
        given().when().put("/" + bucket).then().statusCode(200);
        // Written before versioning, "old" is the null version, which has no version id to report.
        given().body("v0").when().put("/" + bucket + "/old").then().statusCode(200);
        given().body(tagging("unversioned")).when().put("/" + bucket + "/old?tagging")
                .then().statusCode(200).header("x-amz-version-id", nullValue());
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        String v1 = given().body("v1").when().put("/" + bucket + "/key")
                .then().statusCode(200).extract().header("x-amz-version-id");
        String v2 = given().body("v2").when().put("/" + bucket + "/key")
                .then().statusCode(200).extract().header("x-amz-version-id");

        given().body(tagging("first")).when().put("/" + bucket + "/key?tagging&versionId=" + v1)
                .then().statusCode(200).header("x-amz-version-id", v1);
        given().when().get("/" + bucket + "/key?tagging&versionId=" + v1)
                .then().statusCode(200).header("x-amz-version-id", v1)
                .body(containsString("<Value>first</Value>"));
        given().when().get("/" + bucket + "/key?tagging")
                .then().statusCode(200).header("x-amz-version-id", v2)
                .body(not(containsString("<Tag>")));
        given().when().delete("/" + bucket + "/key?tagging&versionId=" + v1)
                .then().statusCode(204).header("x-amz-version-id", v1);
        given().when().get("/" + bucket + "/key?tagging&versionId=" + v1)
                .then().statusCode(200).body(not(containsString("<Tag>")));

        // versionId=null names the null version only: "key" was written with versioning on.
        given().when().get("/" + bucket + "/old?tagging&versionId=null")
                .then().statusCode(200).body(containsString("<Value>unversioned</Value>"));
        given().body(tagging("other")).when().put("/" + bucket + "/key?tagging&versionId=null")
                .then().statusCode(404).body(containsString("<Code>NoSuchVersion</Code>"));
        given().when().get("/" + bucket + "/key?tagging")
                .then().statusCode(200).body(not(containsString("<Value>other</Value>")));
    }

    @Test
    @Order(23)
    void taggingADeleteMarkerIsNotAllowed() {
        String bucket = "versioned-tagging-marker-test";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        String markerId = given().when().delete("/" + bucket + "/key")
                .then().statusCode(204).extract().header("x-amz-version-id");

        // Refused whether the request names the marker or the marker is the current version.
        String key = "/" + bucket + "/key?tagging";
        for (String path : List.of(key, key + "&versionId=" + markerId)) {
            given().body(tagging("v")).when().put(path)
                    .then().statusCode(405).header("x-amz-delete-marker", "true")
                    .body(deleteMarkerRefused("PUT"));
            given().when().get(path)
                    .then().statusCode(405).header("x-amz-delete-marker", "true")
                    .body(deleteMarkerRefused("GET"));
            given().when().delete(path)
                    .then().statusCode(405).header("x-amz-delete-marker", "true")
                    .body(deleteMarkerRefused("DELETE"));
        }
    }

    @Test
    @Order(24)
    void versionIdNullNamesOnlyTheNullVersionOnEveryObjectOperation() {
        String bucket = "versioned-null-version-test";
        given().when().put("/" + bucket).then().statusCode(200);
        // ACLs enabled, so the ACL requests below reach the version they name.
        given().body("<OwnershipControls><Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>")
                .when().put("/" + bucket + "?ownershipControls").then().statusCode(200);
        given().body("old").when().put("/" + bucket + "/old").then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().body("new").when().put("/" + bucket + "/new").then().statusCode(200);

        // "old", written before versioning, is the null version.
        String old = "/" + bucket + "/old";
        given().queryParam("versionId", "null").when().get(old).then().statusCode(200).body(equalTo("old"));
        given().queryParam("versionId", "null").when().head(old).then().statusCode(200);
        given().queryParam("versionId", "null").header("x-amz-object-attributes", "ObjectSize")
                .when().get(old + "?attributes").then().statusCode(200)
                .body(containsString("<ObjectSize>3</ObjectSize>"));
        given().queryParam("versionId", "null").when().get(old + "?acl").then().statusCode(200);
        given().queryParam("versionId", "null").header("x-amz-acl", "private")
                .when().put(old + "?acl").then().statusCode(200);
        given().queryParam("versionId", "null").queryParam("annotationName", "labels").body("x")
                .when().put(old + "?annotation").then().statusCode(200);
        given().queryParam("versionId", "null").queryParam("annotationName", "labels")
                .when().get(old + "?annotation").then().statusCode(200);
        given().header("x-amz-copy-source", "/" + bucket + "/old?versionId=null")
                .when().put("/" + bucket + "/copy").then().statusCode(200);
        given().when().get("/" + bucket + "/copy").then().statusCode(200).body(equalTo("old"));

        // "new", written with versioning on, has no null version, and its current version is not
        // one: each request naming the null version fails as for any missing version.
        String added = "/" + bucket + "/new";
        Matcher<String> noSuchVersion = containsString("<Code>NoSuchVersion</Code>");
        given().queryParam("versionId", "null").when().get(added).then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").when().head(added).then().statusCode(404);
        given().queryParam("versionId", "null").header("x-amz-object-attributes", "ObjectSize")
                .when().get(added + "?attributes").then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").when().get(added + "?acl").then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").header("x-amz-acl", "private")
                .when().put(added + "?acl").then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").queryParam("annotationName", "labels").body("x")
                .when().put(added + "?annotation").then().statusCode(404)
                .body(containsString("<Code>NoSuchKey</Code>"));
        given().header("x-amz-copy-source", "/" + bucket + "/new?versionId=null")
                .when().put("/" + bucket + "/copy-of-new").then().statusCode(404).body(noSuchVersion);
        given().when().get(added).then().statusCode(200).body(equalTo("new"));
    }

    @Test
    @Order(25)
    void retentionAndLegalHoldFindTheNullVersion() {
        // Object Lock enabled on a bucket that already holds a null version, which keeps it.
        String bucket = "versioned-null-version-lock-test";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("old").when().put("/" + bucket + "/old").then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().body("<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>")
                .when().put("/" + bucket + "?object-lock").then().statusCode(200);
        given().body("new").when().put("/" + bucket + "/new").then().statusCode(200);
        String retention = "<Retention><Mode>GOVERNANCE</Mode>"
                + "<RetainUntilDate>2031-01-01T00:00:00Z</RetainUntilDate></Retention>";
        String legalHold = "<LegalHold><Status>OFF</Status></LegalHold>";

        String old = "/" + bucket + "/old";
        given().body("replacement").when().put(old).then().statusCode(200);
        given().queryParam("versionId", "null").body(retention).when().put(old + "?retention").then().statusCode(200);
        given().queryParam("versionId", "null").when().get(old + "?retention")
                .then().statusCode(200).body(containsString("<Mode>GOVERNANCE</Mode>"));
        given().queryParam("versionId", "null").body(legalHold).when().put(old + "?legal-hold").then().statusCode(200);
        given().queryParam("versionId", "null").when().get(old + "?legal-hold")
                .then().statusCode(200).body(containsString("<Status>OFF</Status>"));
        given().queryParam("versionId", "null").when().delete(old)
                .then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));

        String added = "/" + bucket + "/new";
        Matcher<String> noSuchVersion = containsString("<Code>NoSuchVersion</Code>");
        given().queryParam("versionId", "null").body(retention).when().put(added + "?retention")
                .then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").when().get(added + "?retention")
                .then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").body(legalHold).when().put(added + "?legal-hold")
                .then().statusCode(404).body(noSuchVersion);
        given().queryParam("versionId", "null").when().get(added + "?legal-hold")
                .then().statusCode(404).body(noSuchVersion);

        given().queryParam("versionId", "null").header("x-amz-bypass-governance-retention", "true")
                .when().delete(old).then().statusCode(204);
    }

    @Test
    @Order(26)
    void aVersionedOverwritePreservesAndPromotesTheNullVersion() {
        String bucket = "versioned-archived-null-test";
        String key = "/" + bucket + "/key";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("null body").when().put(key).then().statusCode(200);
        given().queryParam("annotationName", "labels").body("kept")
                .when().put(key + "?annotation").then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        String currentVersion = given().body("versioned body").when().put(key)
                .then().statusCode(200).extract().header("x-amz-version-id");

        String versions = given().when().get("/" + bucket + "?versions&prefix=key")
                .then().statusCode(200).extract().asString();
        assertEquals(1, occurrences(versions, "<VersionId>null</VersionId>"));
        assertEquals(1, occurrences(versions, "<VersionId>" + currentVersion + "</VersionId>"));
        given().queryParam("versionId", "null").when().get(key)
                .then().statusCode(200).body(equalTo("null body"));
        given().queryParam("versionId", "null").queryParam("annotationName", "labels")
                .when().get(key + "?annotation").then().statusCode(200).body(equalTo("kept"));
        given().header("x-amz-copy-source", "/" + bucket + "/key?versionId=null")
                .when().put("/" + bucket + "/copy").then().statusCode(200);
        given().when().get("/" + bucket + "/copy").then().statusCode(200).body(equalTo("null body"));

        given().when().delete(key + "?versionId=" + currentVersion).then().statusCode(204);
        given().when().get(key).then().statusCode(200).body(equalTo("null body"));
        versions = given().when().get("/" + bucket + "?versions&prefix=key")
                .then().statusCode(200).extract().asString();
        assertEquals(1, occurrences(versions, "<VersionId>null</VersionId>"));
        assertEquals(1, occurrences(versions, "<IsLatest>true</IsLatest>"));

        given().when().delete(key + "?versionId=null").then().statusCode(204);
        given().when().get(key).then().statusCode(404);
        given().queryParam("versionId", "null").queryParam("annotationName", "labels")
                .when().get(key + "?annotation").then().statusCode(404);
    }

    @Test
    @Order(27)
    void aDeleteMarkerPreservesTheCurrentNullVersion() {
        String bucket = "versioned-null-marker-test";
        String key = "/" + bucket + "/key";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("null body").when().put(key).then().statusCode(200);
        given().queryParam("annotationName", "labels").body("kept")
                .when().put(key + "?annotation").then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);

        String markerId = given().when().delete(key)
                .then().statusCode(204).extract().header("x-amz-version-id");
        given().when().get(key).then().statusCode(404);
        given().queryParam("versionId", "null").when().get(key)
                .then().statusCode(200).body(equalTo("null body"));
        given().queryParam("versionId", "null").queryParam("annotationName", "labels")
                .when().get(key + "?annotation").then().statusCode(200).body(equalTo("kept"));

        given().when().delete(key + "?versionId=" + markerId).then().statusCode(204);
        given().when().get(key).then().statusCode(200).body(equalTo("null body"));
        String versions = given().when().get("/" + bucket + "?versions&prefix=key")
                .then().statusCode(200).extract().asString();
        assertEquals(1, occurrences(versions, "<VersionId>null</VersionId>"));
    }

    private static String tagging(String value) {
        return "<Tagging><TagSet><Tag><Key>version</Key><Value>" + value + "</Value></Tag></TagSet></Tagging>";
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @Order(29)
    void readingADeleteMarkerSaysSo() {
        String bucket = "versioned-read-marker-test";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        String markerId = given().when().delete("/" + bucket + "/key")
                .then().statusCode(204).extract().header("x-amz-version-id");

        // The current version is the marker: 404 as if the object were deleted, saying why.
        String path = "/" + bucket + "/key";
        given().when().get(path).then().statusCode(404)
                .header("x-amz-delete-marker", "true").header("x-amz-version-id", markerId)
                .body(containsString("<Code>NoSuchKey</Code>"));
        given().when().head(path).then().statusCode(404)
                .header("x-amz-delete-marker", "true").header("x-amz-version-id", markerId);

        // Naming the marker's version is refused.
        String markerPath = path + "?versionId=" + markerId;
        given().when().get(markerPath).then().statusCode(405)
                .header("x-amz-delete-marker", "true").header("x-amz-version-id", markerId)
                .header("Last-Modified", notNullValue()).header("Allow", "DELETE")
                .body(deleteMarkerRefused("GET"));
        given().when().head(markerPath).then().statusCode(405)
                .header("x-amz-delete-marker", "true").header("x-amz-version-id", markerId)
                .header("Last-Modified", notNullValue()).header("Allow", "DELETE");

        // A key that never existed answers without the header.
        given().when().get("/" + bucket + "/missing").then().statusCode(404)
                .header("x-amz-delete-marker", nullValue());
    }

    @Test
    @Order(30)
    void readingADeleteMarkerRecordsAFailedDataEvent() {
        List<String> reads = new CopyOnWriteArrayList<>();
        CloudTrailService cloudTrail = mock(CloudTrailService.class);
        doAnswer(invocation -> {
            CloudTrailService.S3EventInput event = invocation.getArgument(0);
            if (event.eventName().equals("GetObject") || event.eventName().equals("HeadObject")) {
                reads.add(event.eventName() + " " + event.errorCode());
            }
            return null;
        }).when(cloudTrail).emitS3DataEvent(any());
        QuarkusMock.installMockForType(cloudTrail, CloudTrailService.class);

        String bucket = "versioned-read-marker-event-test";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().body("data").when().put("/" + bucket + "/key").then().statusCode(200);
        String markerId = given().when().delete("/" + bucket + "/key")
                .then().statusCode(204).extract().header("x-amz-version-id");

        String path = "/" + bucket + "/key";
        given().when().get(path).then().statusCode(404);
        given().when().head(path).then().statusCode(404);
        given().when().get(path + "?versionId=" + markerId).then().statusCode(405);
        given().when().head(path + "?versionId=" + markerId).then().statusCode(405);

        // Each read is recorded with the error code its caller got.
        assertEquals(List.of("GetObject NoSuchKey", "HeadObject NoSuchKey",
                "GetObject MethodNotAllowed", "HeadObject MethodNotAllowed"), reads);
    }

    @Test
    @Order(31)
    void aDeletedWebsitePageServesTheErrorDocument() {
        String bucket = "versioned-website-marker-test";
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().contentType("application/xml")
                .body("<WebsiteConfiguration><IndexDocument><Suffix>index.html</Suffix></IndexDocument>"
                        + "<ErrorDocument><Key>error.html</Key></ErrorDocument></WebsiteConfiguration>")
                .when().put("/" + bucket + "?website").then().statusCode(200);
        given().contentType("text/html").body("<html><body>Custom Error</body></html>")
                .when().put("/" + bucket + "/error.html").then().statusCode(200);
        given().contentType("text/html").body("<html><body>Page</body></html>")
                .when().put("/" + bucket + "/page.html").then().statusCode(200);
        given().when().delete("/" + bucket + "/page.html").then().statusCode(204);

        // The website endpoint answers a deleted page with the error document, as for any missing page.
        String host = bucket + ".s3-website-us-east-1.localhost:" + RestAssured.port;
        given().header("Host", host).when().get("/page.html").then().statusCode(404)
                .contentType("text/html").header("x-amz-error-code", "NoSuchKey")
                .body(equalTo("<html><body>Custom Error</body></html>"));
        given().header("Host", host).when().head("/page.html").then().statusCode(404)
                .contentType("text/html").header("x-amz-error-code", "NoSuchKey");
    }

    private static Matcher<String> deleteMarkerRefused(String method) {
        return allOf(containsString("<Code>MethodNotAllowed</Code>"),
                containsString("<Method>" + method + "</Method>"),
                containsString("<ResourceType>DeleteMarker</ResourceType>"));
    }
    @Test
    @Order(28)
    void versionedWritesAndMarkersPreserveComplianceLockedNullVersions() {
        String bucket = "versioned-locked-null-preservation";
        given().when().put("/" + bucket).then().statusCode(200);
        for (String key : List.of("put", "copy", "marker")) {
            given().body("original").when().put("/" + bucket + "/" + key).then().statusCode(200);
        }
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning").then().statusCode(200);
        given().body("<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>")
                .when().put("/" + bucket + "?object-lock").then().statusCode(200);
        for (String key : List.of("put", "copy", "marker")) {
            given().queryParam("versionId", "null")
                    .body("<Retention><Mode>COMPLIANCE</Mode><RetainUntilDate>2031-01-01T00:00:00Z</RetainUntilDate></Retention>")
                    .when().put("/" + bucket + "/" + key + "?retention").then().statusCode(200);
        }

        given().body("replacement").when().put("/" + bucket + "/put").then().statusCode(200);
        given().header("x-amz-copy-source", "/" + bucket + "/put")
                .when().put("/" + bucket + "/copy").then().statusCode(200);
        given().when().delete("/" + bucket + "/marker").then().statusCode(204)
                .header("x-amz-delete-marker", "true");

        for (String key : List.of("put", "copy", "marker")) {
            String path = "/" + bucket + "/" + key;
            given().queryParam("versionId", "null").when().get(path)
                    .then().statusCode(200).body(equalTo("original"));
            given().queryParam("versionId", "null").when().get(path + "?retention")
                    .then().statusCode(200).body(containsString("<Mode>COMPLIANCE</Mode>"));
            given().queryParam("versionId", "null").header("x-amz-bypass-governance-retention", "true")
                    .when().delete(path).then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));
        }
    }

}
