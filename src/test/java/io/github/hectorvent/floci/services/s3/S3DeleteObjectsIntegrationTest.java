package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3DeleteObjectsIntegrationTest {

    @Test
    void deleteObjects_emptyKeyList_returnsMalformedXml() {
        String bucket = createBucket();

        given()
            .contentType("application/xml")
            .body("<Delete><Quiet>true</Quiet></Delete>")
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MalformedXML</Code>"));
    }

    @Test
    void deleteObjects_withKeys_deletesAndReportsResult() {
        String bucket = createBucket();
        putObject(bucket, "keep.txt");
        putObject(bucket, "remove.txt");

        given()
            .contentType("application/xml")
            .body("<Delete><Object><Key>remove.txt</Key></Object></Delete>")
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Key>remove.txt</Key>"))
            .body(not(containsString("<VersionId>")));

        given()
        .when()
            .get("/" + bucket + "/remove.txt")
        .then()
            .statusCode(404);

        given()
        .when()
            .get("/" + bucket + "/keep.txt")
        .then()
            .statusCode(200);
    }

    @Test
    void deleteObjects_withExplicitVersionIds_permanentlyDeletesVersionsOnVersionedBucket() {
        String bucket = createBucket();
        enableVersioning(bucket);

        String key = "versioned.txt";
        String versionId1 = putVersionedObject(bucket, key);
        String versionId2 = putVersionedObject(bucket, key);

        String deleteBody = """
                <Delete>
                  <Object><Key>%s</Key><VersionId>%s</VersionId></Object>
                  <Object><Key>%s</Key><VersionId>%s</VersionId></Object>
                </Delete>
                """.formatted(key, versionId1, key, versionId2);

        given()
            .contentType("application/xml")
            .body(deleteBody)
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + key + "</Key>"))
            .body(containsString("<VersionId>" + versionId1 + "</VersionId>"))
            .body(containsString("<VersionId>" + versionId2 + "</VersionId>"));

        given()
        .when()
            .get("/" + bucket + "?versions&prefix=" + key)
        .then()
            .statusCode(200)
            .body(not(containsString("<Version>")));

        given()
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(404);
    }

    /**
     * github.com/floci-io/floci/issues/3355: the singular DELETE handler already threads
     * x-amz-bypass-governance-retention through to S3Service, but the batch DeleteObjects path
     * never read the header and always called the 3-arg deleteObject overload, which hardcodes
     * bypassGovernance=false. A GOVERNANCE-locked object could never be force-deleted through a
     * batch request, and any AwsException a per-object delete threw (e.g. the resulting
     * AccessDenied) was flattened to a generic InternalError instead of the real code.
     */
    @Test
    void deleteObjects_governanceLockedObjectWithoutBypass_reportsAccessDeniedNotInternalError() {
        String bucket = createLockEnabledBucket();
        String key = "locked.txt";
        putGovernanceLockedObject(bucket, key);

        given()
            .contentType("application/xml")
            .body("<Delete><Object><Key>%s</Key></Object></Delete>".formatted(key))
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Code>AccessDenied</Code>"))
            .body(not(containsString("InternalError")));

        given()
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(200);
    }

    @Test
    void deleteObjects_governanceLockedObjectWithBypassHeader_isDeleted() {
        String bucket = createLockEnabledBucket();
        String key = "locked-bypass.txt";
        putGovernanceLockedObject(bucket, key);

        given()
            .contentType("application/xml")
            .header("x-amz-bypass-governance-retention", "true")
            .body("<Delete><Object><Key>%s</Key></Object></Delete>".formatted(key))
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + key + "</Key>"))
            .body(not(containsString("<Error>")));

        given()
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(404);
    }

    private static String createLockEnabledBucket() {
        String bucket = "delete-objects-lock-" + UUID.randomUUID().toString().substring(0, 8);
        given()
            .header("x-amz-bucket-object-lock-enabled", "true")
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        return bucket;
    }

    private static void putGovernanceLockedObject(String bucket, String key) {
        given()
            .header("x-amz-object-lock-mode", "GOVERNANCE")
            .header("x-amz-object-lock-retain-until-date", "2099-01-01T00:00:00Z")
            .body("locked content")
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);
    }

    private static String createBucket() {
        String bucket = "delete-objects-" + UUID.randomUUID().toString().substring(0, 8);
        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        return bucket;
    }

    private static void putObject(String bucket, String key) {
        given()
            .body("content")
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);
    }

    private static void enableVersioning(String bucket) {
        given()
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + bucket + "?versioning")
        .then()
            .statusCode(200);
    }

    private static String putVersionedObject(String bucket, String key) {
        return given()
            .body("content")
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200)
            .extract()
            .header("x-amz-version-id");
    }
}
