package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AccelerateConfigurationIntegrationTest {

    private static final String BUCKET = "accelerate-int-test";
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

    /**
     * A bucket that has never had acceleration configured returns an
     * {@code AccelerateConfiguration} with no Status element. Before the accelerate
     * subresource was routed this fell through to ListObjects and returned a
     * {@code ListBucketResult}, which the SDK silently parsed as "not configured".
     */
    @Test
    @Order(2)
    void getAccelerateBeforePutReturnsConfigurationWithoutStatus() {
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<AccelerateConfiguration"))
            .body(not(containsString("<Status>")))
            .body(not(containsString("ListBucketResult")));
    }

    /**
     * Regression test for the Terraform-breaking bug where {@code PUT /{bucket}?accelerate}
     * fell through to the bucket-creation handler: outside the default region it returned
     * {@code BucketAlreadyOwnedByYou}, and inside it the idempotent-create path answered a
     * silent 200 with a {@code Location} header without storing anything — so the absent
     * header is what distinguishes the routed response from the fall-through here.
     */
    @Test
    @Order(3)
    void putAccelerateEnabledOnExistingBucketReturns200() {
        given()
            .body(ENABLED_XML)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .header("Location", nullValue())
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(4)
    void getAccelerateReturnsStoredStatus() {
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    @Order(5)
    void putAccelerateSuspendedOverwritesStoredStatus() {
        given()
            .body(SUSPENDED_XML)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Suspended</Status>"));
    }

    /**
     * The Status element is optional in the AWS schema (Required: No), so a
     * configuration without one is accepted and leaves the stored state unchanged.
     */
    @Test
    @Order(6)
    void putAccelerateWithoutStatusIsAcceptedAndLeavesStateUnchanged() {
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Suspended</Status>"));
    }

    /** The AccelerateConfiguration root is Required: Yes, so a body that does not parse to one is malformed. */
    @Test
    @Order(7)
    void putAccelerateRejectsAnUnparseableBody() {
        for (String body : new String[] {
                "",
                "garbage {} not xml",
                """
                <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Status>Enabled""" }) {
            given()
                .body(body)
            .when()
                .put("/" + BUCKET + "?accelerate")
            .then()
                .statusCode(400)
                .body(containsString("MalformedXML"));
        }
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Suspended</Status>"));
    }

    /** A Status inside the wrong root must not configure anything — the required root is checked on both branches. */
    @Test
    @Order(8)
    void putAccelerateRejectsAWrongRootElement() {
        given()
            .body("""
                    <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Enabled</Status>
                    </VersioningConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Suspended</Status>"));
    }

    /** Same normalization as the requestPayment sibling. */
    @Test
    @Order(9)
    void putAccelerateTrimsWhitespacePaddedStatus() {
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>  Enabled  </Status>
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    @Order(10)
    void putAccelerateRejectsInvalidStatus() {
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Paused</Status>
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(11)
    void putAccelerateOnMissingBucketReturns404() {
        given()
            .body(ENABLED_XML)
        .when()
            .put("/this-bucket-does-not-exist-accel?accelerate")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(12)
    void getAccelerateOnMissingBucketReturns404() {
        given()
        .when()
            .get("/this-bucket-does-not-exist-accel?accelerate")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    /**
     * AWS defines no DELETE for the accelerate subresource. Before it was routed, a
     * {@code DELETE /{bucket}?accelerate} fell through to {@code DeleteBucket} and
     * deleted an empty bucket outright.
     */
    @Test
    @Order(13)
    void deleteAccelerateReturns405AndLeavesTheBucketAlone() {
        given()
        .when()
            .delete("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(405)
            .body(containsString("MethodNotAllowed"));
        given()
        .when()
            .get("/" + BUCKET + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<AccelerateConfiguration"));
    }
}
