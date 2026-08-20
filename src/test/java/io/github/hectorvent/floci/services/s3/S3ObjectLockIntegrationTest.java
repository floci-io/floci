package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
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
                  <Mode>GOVERNANCE</Mode>
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
    void nonExistentBucketReturnsNoSuchBucket() {
        given()
        .when()
            .get("/does-not-exist-object-lock-test?object-lock")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}
