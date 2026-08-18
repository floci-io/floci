package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AccelerateConfigurationIntegrationTest {

    private static final String BUCKET = "accelerate-config-int-test";
    private static final String ENABLED_XML = """
            <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Status>Enabled</Status>
            </AccelerateConfiguration>
            """;
    private static final String SUSPENDED_XML = """
            <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Status>Suspended</Status>
            </AccelerateConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void getAccelerateConfigurationBeforePutReturnsNoStatus() {
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<AccelerateConfiguration"))
            .body(not(containsString("<Status>")));
    }

    /**
     * Regression test for the Terraform-breaking bug where {@code PUT /{bucket}?accelerate}
     * fell through to the bucket-creation handler and returned {@code 409 BucketAlreadyOwnedByYou}.
     * Real S3 allows {@code PutBucketAccelerateConfiguration} to be called on an existing
     * bucket - and Terraform's {@code aws_s3_bucket_accelerate_configuration} resource relies
     * on this. The sibling {@code ?requestPayment} action had the identical bug, fixed earlier;
     * {@code ?accelerate} was never wired in at all.
     */
    @Test
    @Order(3)
    void putAccelerateConfigurationReturns200() {
        given()
            .body(SUSPENDED_XML)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(4)
    void getAccelerateConfigurationReturnsStoredStatus() {
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Suspended</Status>"));
    }

    @Test
    @Order(5)
    void putAccelerateConfigurationIsIdempotentOnExistingBucket() {
        given()
            .body(ENABLED_XML)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(not(containsString("BucketAlreadyOwnedByYou")));

        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    @Order(6)
    void putAccelerateConfigurationRejectsInvalidStatus() {
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Turbo</Status>
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(7)
    void putAccelerateConfigurationOnMissingBucketReturns404() {
        given()
            .body(SUSPENDED_XML)
        .when()
            .put("/this-bucket-does-not-exist-ac?accelerate")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}
