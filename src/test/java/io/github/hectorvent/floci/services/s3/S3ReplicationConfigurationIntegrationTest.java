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

    /**
     * {@code ReplicationRule/Status} is Required: Yes with enum {@code Enabled|Disabled}.
     * Storing a rule with any other status — or with none — makes the emulator answer a
     * later GetBucketReplication with a document AWS would never have accepted.
     */
    @Test
    @Order(8)
    void putReplicationRejectsARuleStatusOutsideTheEnum() {
        given()
            .body("""
                    <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                        <Rule>
                            <ID>bad-status</ID>
                            <Status>enabled</Status>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target</Bucket>
                            </Destination>
                        </Rule>
                    </ReplicationConfiguration>
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
            .body(not(containsString("bad-status")));
    }

    @Test
    @Order(9)
    void putReplicationRejectsARuleWithNoStatus() {
        given()
            .body("""
                    <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                        <Rule>
                            <ID>no-status</ID>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target</Bucket>
                            </Destination>
                        </Rule>
                    </ReplicationConfiguration>
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
            .body(not(containsString("no-status")));
    }

    /**
     * Each {@code Rule} requires its own {@code Destination/Bucket}; a document-wide check that
     * only looks for one {@code Bucket} anywhere in the XML is satisfied when just one of several
     * rules has a destination, so a rule missing one round-trips as a configuration AWS would
     * have rejected as {@code MalformedXML}.
     */
    @Test
    @Order(10)
    void putReplicationRejectsARuleWithoutItsOwnDestinationBucket() {
        given()
            .body("""
                    <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                        <Rule>
                            <ID>has-destination</ID>
                            <Status>Enabled</Status>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target</Bucket>
                            </Destination>
                        </Rule>
                        <Rule>
                            <ID>missing-destination</ID>
                            <Status>Enabled</Status>
                        </Rule>
                    </ReplicationConfiguration>
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
            .body(not(containsString("missing-destination")));
    }

    @Test
    @Order(11)
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
    @Order(12)
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
    @Order(13)
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
    @Order(14)
    void deleteReplicationWithoutConfigurationStillReturns204() {
        given()
        .when()
            .delete("/" + BUCKET + "?replication")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(15)
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
    @Order(16)
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

    /**
     * A document-wide count of Rule elements against Destination elements can't distinguish a
     * well-formed document from one where a rule improperly carries two Destinations and another
     * rule carries none — the totals still balance (2 rules, 2 destinations) even though the
     * second rule has no destination of its own and the first has one too many.
     */
    @Test
    @Order(17)
    void putReplicationRejectsWhenDestinationsAreNotOnePerRule() {
        given()
            .body("""
                    <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                        <Rule>
                            <ID>two-destinations</ID>
                            <Status>Enabled</Status>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target</Bucket>
                            </Destination>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target-2</Bucket>
                            </Destination>
                        </Rule>
                        <Rule>
                            <ID>zero-destinations</ID>
                            <Status>Enabled</Status>
                        </Rule>
                    </ReplicationConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?replication")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
        // BUCKET has no configuration at this point in the ordered sequence (cleared by
        // deleteReplicationClearsTheStoredConfiguration, @Order(13)), so the rejected PUT above
        // must not have stored anything either.
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));
    }

    /**
     * {@code Destination.Bucket} is a required <em>scalar</em> member (botocore
     * s3/2006-03-01/service-2.json: {@code "Bucket":{"shape":"BucketName"}}, not a list) — a
     * Destination with two Bucket children is not a valid alternative encoding, it is malformed.
     * Selecting only the first Bucket child would silently accept the second as if it never
     * existed and store a document AWS would reject.
     */
    @Test
    @Order(18)
    void putReplicationRejectsADestinationWithMultipleBucketElements() {
        given()
            .body("""
                    <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                        <Rule>
                            <ID>two-buckets</ID>
                            <Status>Enabled</Status>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target</Bucket>
                                <Bucket>arn:aws:s3:::replication-config-int-test-target-2</Bucket>
                            </Destination>
                        </Rule>
                    </ReplicationConfiguration>
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
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));
    }
}
