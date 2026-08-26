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
class S3ReplicationConfigurationIntegrationTest {

    private static final String BUCKET = "replication-config-int-test";
    private static final String REPLICATION_XML = """
            <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                <Rule>
                    <ID>rule1</ID>
                    <Status>Enabled</Status>
                    <Destination>
                        <Bucket>arn:aws:s3:::replication-config-int-test-target</Bucket>
                    </Destination>
                </Rule>
            </ReplicationConfiguration>
            """;
    private static final String REPLACEMENT_XML = """
            <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Role>arn:aws:iam::000000000000:role/replication-role-two</Role>
                <Rule>
                    <ID>rule2</ID>
                    <Status>Disabled</Status>
                    <Destination>
                        <Bucket>arn:aws:s3:::replication-config-int-test-other</Bucket>
                    </Destination>
                </Rule>
            </ReplicationConfiguration>
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
     * A bucket without a replication configuration answers
     * {@code ReplicationConfigurationNotFoundError} (404) — what the SDK and the
     * Terraform provider treat as "not configured". Before the replication
     * subresource was routed this fell through to ListObjects and returned a
     * {@code ListBucketResult}, which the SDK parsed as an empty configuration —
     * a silent wrong answer instead of the 404.
     */
    @Test
    @Order(2)
    void getReplicationBeforePutReturnsNotFoundError() {
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"))
            .body(not(containsString("ListBucketResult")));
    }

    /**
     * Regression test for the fall-through to the bucket-creation handler: outside the
     * default region it returned {@code BucketAlreadyOwnedByYou}, and inside it the
     * idempotent-create path answered a silent 200 with a {@code Location} header
     * without storing a replication configuration — the absent header
     * distinguishes the routed response.
     */
    @Test
    @Order(3)
    void putReplicationOnExistingBucketReturns200() {
        given()
            .body(REPLICATION_XML)
        .when()
            .put("/" + BUCKET + "?replication")
        .then()
            .statusCode(200)
            .header("Location", nullValue())
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    /** The stored configuration is echoed back verbatim. */
    @Test
    @Order(4)
    void getReplicationEchoesTheStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("<ReplicationConfiguration"))
            .body(containsString("role/replication-role"))
            .body(containsString("<ID>rule1</ID>"));
    }

    @Test
    @Order(5)
    void putReplicationReplacesTheStoredConfiguration() {
        given()
            .body(REPLACEMENT_XML)
        .when()
            .put("/" + BUCKET + "?replication")
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("<ID>rule2</ID>"))
            .body(not(containsString("<ID>rule1</ID>")));
    }

    /** The ReplicationConfiguration root is Required: Yes, so a body that does not parse to one is malformed. */
    @Test
    @Order(6)
    void putReplicationRejectsAnUnparseableBody() {
        for (String body : new String[] {
                "",
                "garbage {} not xml",
                """
                <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Role>arn:aws:iam::000000000000:role/replication-role""" }) {
            given()
                .body(body)
            .when()
                .put("/" + BUCKET + "?replication")
            .then()
                .statusCode(400)
                .body(containsString("MalformedXML"));
        }
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("<ID>rule2</ID>"));
    }

    /** A configuration inside the wrong root must not store anything. */
    @Test
    @Order(7)
    void putReplicationRejectsAWrongRootElement() {
        given()
            .body("""
                    <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Enabled</Status>
                    </VersioningConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?replication")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("<ID>rule2</ID>"));
    }

    @Test
    @Order(8)
    void putReplicationOnMissingBucketReturns404() {
        given()
            .body(REPLICATION_XML)
        .when()
            .put("/this-bucket-does-not-exist-repl?replication")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(9)
    void getReplicationOnMissingBucketReturns404() {
        given()
        .when()
            .get("/this-bucket-does-not-exist-repl?replication")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    /**
     * DeleteBucketReplication removes the stored configuration and answers 204;
     * a later GET is back to the not-configured 404. The bucket itself must
     * survive — before the subresource was handled a {@code DELETE
     * /{bucket}?replication} would have fallen through to {@code DeleteBucket}.
     */
    @Test
    @Order(10)
    void deleteReplicationClearsTheStoredConfiguration() {
        given()
        .when()
            .delete("/" + BUCKET + "?replication")
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));
        given()
        .when()
            .get("/" + BUCKET + "?location")
        .then()
            .statusCode(200);
    }

    /** Matching real S3, deleting an already-absent configuration still answers 204. */
    @Test
    @Order(11)
    void deleteReplicationWithoutConfigurationStillReturns204() {
        given()
        .when()
            .delete("/" + BUCKET + "?replication")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(12)
    void deleteReplicationOnMissingBucketReturns404() {
        given()
        .when()
            .delete("/this-bucket-does-not-exist-repl?replication")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    /**
     * Pins the per-method precedence at the wire: the PUT and GET chains dispatch
     * accelerate ahead of replication, while the DELETE chain routes replication —
     * accelerate's DELETE arm is a 405 and must not preempt it. The IAM mapping
     * mirrors exactly this order, so a silent controller reorder would desynchronize
     * the two.
     */
    @Test
    @Order(13)
    void accelerateAndReplicationPrecedenceFollowsEachMethodsDispatchOrder() {
        String bucket = "replication-precedence-test";
        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        // PUT ?accelerate&replication executes accelerate: the accelerate body is
        // accepted, and nothing lands in the replication store.
        given()
            .body("""
                    <AccelerateConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Enabled</Status>
                    </AccelerateConfiguration>
                    """)
        .when()
            .put("/" + bucket + "?accelerate&replication")
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/" + bucket + "?accelerate")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Enabled</Status>"));
        given()
        .when()
            .get("/" + bucket + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));

        // GET ?accelerate&replication executes accelerate: an AccelerateConfiguration
        // comes back, not the replication 404.
        given()
        .when()
            .get("/" + bucket + "?accelerate&replication")
        .then()
            .statusCode(200)
            .body(containsString("<AccelerateConfiguration"));

        // DELETE ?accelerate&replication executes replication: the stored
        // configuration is cleared at 204 — not accelerate's 405.
        given()
            .body(REPLICATION_XML)
        .when()
            .put("/" + bucket + "?replication")
        .then()
            .statusCode(200);
        given()
        .when()
            .delete("/" + bucket + "?accelerate&replication")
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + bucket + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));
    }
}
