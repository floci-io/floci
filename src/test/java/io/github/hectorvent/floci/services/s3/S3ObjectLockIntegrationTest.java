package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ObjectLockIntegrationTest {

    private static final String PLAIN_BUCKET = "object-lock-plain";
    private static final String NO_LOCK_BUCKET = "object-lock-disabled";
    private static final String LOCK_BUCKET = "object-lock-enabled";

    // --- plain bucket (no object-lock header) ---

    @Test
    @Order(1)
    void createPlainBucket() {
        given().when().put("/" + PLAIN_BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void plainBucketReturnsObjectLockConfigurationNotFoundError() {
        given()
        .when()
            .get("/" + PLAIN_BUCKET + "?object-lock")
        .then()
            .statusCode(404)
            .body(containsString("ObjectLockConfigurationNotFoundError"))
            .body(containsString("Object Lock configuration does not exist for this bucket"));
    }

    // --- bucket created with x-amz-bucket-object-lock-enabled: false ---

    @Test
    @Order(3)
    void createBucketWithObjectLockExplicitlyDisabled() {
        given()
            .header("x-amz-bucket-object-lock-enabled", "false")
        .when()
            .put("/" + NO_LOCK_BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void explicitlyDisabledBucketReturnsObjectLockConfigurationNotFoundError() {
        given()
        .when()
            .get("/" + NO_LOCK_BUCKET + "?object-lock")
        .then()
            .statusCode(404)
            .body(containsString("ObjectLockConfigurationNotFoundError"));
    }

    // --- bucket created with x-amz-bucket-object-lock-enabled: true ---

    @Test
    @Order(5)
    void createBucketWithObjectLockEnabled() {
        given()
            .header("x-amz-bucket-object-lock-enabled", "true")
        .when()
            .put("/" + LOCK_BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void lockEnabledBucketReturnsConfigWithNoRule() {
        given()
        .when()
            .get("/" + LOCK_BUCKET + "?object-lock")
        .then()
            .statusCode(200)
            .body(containsString("<ObjectLockEnabled>Enabled</ObjectLockEnabled>"))
            .body(not(containsString("<Rule>")));
    }

    @Test
    @Order(7)
    void putObjectLockConfigurationWithRetentionRule() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ObjectLockConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <ObjectLockEnabled>Enabled</ObjectLockEnabled>
                  <Rule>
                    <DefaultRetention>
                      <Mode>COMPLIANCE</Mode>
                      <Days>30</Days>
                    </DefaultRetention>
                  </Rule>
                </ObjectLockConfiguration>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "?object-lock")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void getObjectLockConfigurationReturnsRetentionRule() {
        given()
        .when()
            .get("/" + LOCK_BUCKET + "?object-lock")
        .then()
            .statusCode(200)
            .body(containsString("<ObjectLockEnabled>Enabled</ObjectLockEnabled>"))
            .body(containsString("<Mode>COMPLIANCE</Mode>"))
            .body(containsString("<Days>30</Days>"));
    }

    // --- PutObjectRetention RetainUntilDate parsing ---

    private static final String RETENTION_KEY = "retained-object.txt";

    @Test
    @Order(10)
    void putObjectForRetention() {
        given()
            .body("hello")
        .when()
            .put("/" + LOCK_BUCKET + "/" + RETENTION_KEY)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void putObjectRetentionWithValidIsoDateReturns200() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>COMPLIANCE</Mode>
                  <RetainUntilDate>2030-01-01T00:00:00Z</RetainUntilDate>
                </Retention>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "/" + RETENTION_KEY + "?retention")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(12)
    void putObjectRetentionWithEpochSecondsReturns400MalformedXml() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>GOVERNANCE</Mode>
                  <RetainUntilDate>1577836800</RetainUntilDate>
                </Retention>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "/" + RETENTION_KEY + "?retention")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"))
            .body(not(containsString("Internal Server Error")));
    }

    @Test
    @Order(13)
    void putObjectRetentionWithGarbageDateReturns400MalformedXml() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>GOVERNANCE</Mode>
                  <RetainUntilDate>notadate</RetainUntilDate>
                </Retention>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "/" + RETENTION_KEY + "?retention")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"))
            .body(not(containsString("Internal Server Error")));
    }

    // --- non-existent bucket ---

    @Test
    @Order(14)
    void putGovernanceObjectForRetentionUpgrade() {
        given()
            .header("x-amz-object-lock-mode", "GOVERNANCE")
            .header("x-amz-object-lock-retain-until-date", "2030-01-01T00:00:00Z")
            .body("governance object")
        .when()
            .put("/" + LOCK_BUCKET + "/governance-object.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(15)
    void governanceRetentionCanBeUpgradedToCompliance() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>COMPLIANCE</Mode>
                  <RetainUntilDate>2030-01-01T00:00:00Z</RetainUntilDate>
                </Retention>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "/governance-object.txt?retention")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + LOCK_BUCKET + "/governance-object.txt?retention")
        .then()
            .statusCode(200)
            .body(containsString("<Mode>COMPLIANCE</Mode>"));
    }

    @Test
    @Order(16)
    void complianceRetentionCannotBeDowngradedToGovernance() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>GOVERNANCE</Mode>
                  <RetainUntilDate>2030-01-01T00:00:00Z</RetainUntilDate>
                </Retention>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "/" + RETENTION_KEY + "?retention")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
        .when()
            .get("/" + LOCK_BUCKET + "/" + RETENTION_KEY + "?retention")
        .then()
            .statusCode(200)
            .body(containsString("<Mode>COMPLIANCE</Mode>"));
    }

    @Test
    @Order(17)
    void putObjectWithExpiredComplianceRetention() {
        given()
            .header("x-amz-object-lock-mode", "COMPLIANCE")
            .header("x-amz-object-lock-retain-until-date", "2020-01-01T00:00:00Z")
            .body("expired compliance object")
        .when()
            .put("/" + LOCK_BUCKET + "/expired-compliance-object.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(18)
    void expiredComplianceRetentionCanChangeMode() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Retention xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Mode>GOVERNANCE</Mode>
                  <RetainUntilDate>2030-01-01T00:00:00Z</RetainUntilDate>
                </Retention>
                """;
        given()
            .body(body)
        .when()
            .put("/" + LOCK_BUCKET + "/expired-compliance-object.txt?retention")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + LOCK_BUCKET + "/expired-compliance-object.txt?retention")
        .then()
            .statusCode(200)
            .body(containsString("<Mode>GOVERNANCE</Mode>"));
    }

    @Test
    @Order(19)
    void nonExistentBucketReturnsNoSuchBucket() {
        given()
        .when()
            .get("/does-not-exist-object-lock-test?object-lock")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    // --- locks protect versions, not keys ---

    @Test
    @Order(20)
    void simpleDeleteOfLegalHoldObjectCreatesDeleteMarker() {
        String versionId = given()
            .header("x-amz-object-lock-legal-hold", "ON")
            .body("held")
        .when()
            .put("/" + LOCK_BUCKET + "/held-object.txt")
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        given()
        .when()
            .delete("/" + LOCK_BUCKET + "/held-object.txt")
        .then()
            .statusCode(204)
            .header("x-amz-delete-marker", "true");

        given()
        .when()
            .get("/" + LOCK_BUCKET + "/held-object.txt?legal-hold&versionId=" + versionId)
        .then()
            .statusCode(200)
            .body(containsString("<Status>ON</Status>"));

        given()
            .header("x-amz-bypass-governance-retention", "true")
        .when()
            .delete("/" + LOCK_BUCKET + "/held-object.txt?versionId=" + versionId)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(21)
    void putAndCopyOverGovernanceObjectCreateNewVersions() {
        String versionId = given()
            .header("x-amz-object-lock-mode", "GOVERNANCE")
            .header("x-amz-object-lock-retain-until-date", "2099-01-01T00:00:00Z")
            .body("v1")
        .when()
            .put("/" + LOCK_BUCKET + "/governed-object.txt")
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        given()
            .body("v2")
        .when()
            .put("/" + LOCK_BUCKET + "/governed-object.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", not(equalTo(versionId)));

        given()
            .header("x-amz-copy-source", "/" + LOCK_BUCKET + "/governed-object.txt?versionId=" + versionId)
        .when()
            .put("/" + LOCK_BUCKET + "/governed-object.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", not(equalTo(versionId)));

        given()
        .when()
            .get("/" + LOCK_BUCKET + "/governed-object.txt?retention&versionId=" + versionId)
        .then()
            .statusCode(200)
            .body(containsString("<Mode>GOVERNANCE</Mode>"));

        given()
        .when()
            .delete("/" + LOCK_BUCKET + "/governed-object.txt?versionId=" + versionId)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    // --- Object Lock headers on CopyObject and CreateMultipartUpload ---

    @Test
    @Order(22)
    void copyObjectAppliesItsObjectLockHeaders() {
        given().body("source").when().put("/" + LOCK_BUCKET + "/copy-lock-source.txt").then().statusCode(200);

        given()
            .header("x-amz-copy-source", "/" + LOCK_BUCKET + "/copy-lock-source.txt")
            .header("x-amz-object-lock-mode", "GOVERNANCE")
            .header("x-amz-object-lock-retain-until-date", "2099-01-01T00:00:00Z")
            .header("x-amz-object-lock-legal-hold", "ON")
        .when()
            .put("/" + LOCK_BUCKET + "/copy-lock-target.txt")
        .then()
            .statusCode(200);

        assertLockedUntil2099("/" + LOCK_BUCKET + "/copy-lock-target.txt");
    }

    @Test
    @Order(23)
    void multipartUploadAppliesItsObjectLockHeadersOnCompletion() {
        String path = "/" + LOCK_BUCKET + "/multipart-lock.txt";
        String uploadId = given()
            .header("x-amz-object-lock-mode", "GOVERNANCE")
            .header("x-amz-object-lock-retain-until-date", "2099-01-01T00:00:00Z")
            .header("x-amz-object-lock-legal-hold", "ON")
        .when()
            .post(path + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String partETag = given()
            .body("part-one")
        .when()
            .put(path + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        given()
            .contentType("application/xml")
            .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + partETag
                    + "</ETag></Part></CompleteMultipartUpload>")
        .when()
            .post(path + "?uploadId=" + uploadId)
        .then()
            .statusCode(200);

        assertLockedUntil2099(path);
    }

    @Test
    @Order(24)
    void objectLockHeadersAreCheckedOnEveryObjectCreation() {
        // The errors S3 was recorded answering: a mode and a date come together, and the mode is one
        // S3 knows. The unparseable date was not recorded, and is refused the same way.
        String copySource = "/" + LOCK_BUCKET + "/copy-lock-source.txt";
        for (String[] request : new String[][] {{"put", null}, {"copy", copySource}, {"upload", null}}) {
            assertInvalidLockHeaders(request[0], request[1], "GOVERNANCE", null,
                    "x-amz-object-lock-retain-until-date", null, "must both be supplied");
            assertInvalidLockHeaders(request[0], request[1], null, "2099-01-01T00:00:00Z",
                    "x-amz-object-lock-mode", null, "must both be supplied");
            assertInvalidLockHeaders(request[0], request[1], "BAD-VALUE", "2099-01-01T00:00:00Z",
                    "x-amz-object-lock-mode", "BAD-VALUE", "Unknown wormMode directive.");
            assertInvalidLockHeaders(request[0], request[1], "GOVERNANCE", "next-week",
                    "x-amz-object-lock-retain-until-date", "next-week", "ISO 8601");
        }
    }

    /** A PutObject, a CopyObject of {@code copySource} or a CreateMultipartUpload refused for its lock headers. */
    private static void assertInvalidLockHeaders(String operation, String copySource, String mode, String until,
                                                 String argumentName, String argumentValue, String message) {
        RequestSpecification request = given();
        if (mode != null) {
            request = request.header("x-amz-object-lock-mode", mode);
        }
        if (until != null) {
            request = request.header("x-amz-object-lock-retain-until-date", until);
        }
        String path = "/" + LOCK_BUCKET + "/invalid-lock-" + operation + ".txt";
        Response response = switch (operation) {
            case "put" -> request.body("x").when().put(path);
            case "copy" -> request.header("x-amz-copy-source", copySource).when().put(path);
            default -> request.when().post(path + "?uploads");
        };
        ValidatableResponse then = response.then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"))
            .body(containsString(message))
            .body(containsString("<ArgumentName>" + argumentName + "</ArgumentName>"));
        if (argumentValue != null) {
            then.body(containsString("<ArgumentValue>" + argumentValue + "</ArgumentValue>"));
        }
    }

    /** The object holds the GOVERNANCE retention until 2099 and the legal hold its request set. */
    private static void assertLockedUntil2099(String path) {
        given()
        .when()
            .get(path + "?retention")
        .then()
            .statusCode(200)
            .body(containsString("<Mode>GOVERNANCE</Mode>"))
            .body(containsString("<RetainUntilDate>2099-01-01T00:00:00"));
        given()
        .when()
            .get(path + "?legal-hold")
        .then()
            .statusCode(200)
            .body(containsString("<Status>ON</Status>"));
    }
}
