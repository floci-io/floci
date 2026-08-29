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
class S3ReplicationIntegrationTest {

    private static final String BUCKET = "replication-int-test";
    private static final String WEST_BUCKET = "replication-int-test-west";
    private static final String REPLICATION_XML = """
            <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Role>arn:aws:iam::000000000000:role/replication-role</Role>
                <Rule>
                    <ID>rule-1</ID>
                    <Status>Enabled</Status>
                    <Destination>
                        <Bucket>arn:aws:s3:::replication-dest</Bucket>
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

    @Test
    @Order(2)
    void getReplicationBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));
    }

    /**
     * Regression test for the bucket-destroying bug where {@code DELETE /{bucket}?replication}
     * (DeleteBucketReplication) was not handled and fell through to the unqualified
     * {@code DeleteBucket}, silently deleting the entire bucket. Real S3 removes only the
     * replication configuration and returns 204.
     */
    @Test
    @Order(3)
    void deleteReplicationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?replication")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(4)
    void bucketStillExistsAfterReplicationDelete() {
        // A sub-resource-qualified DELETE must never remove the bucket itself.
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void putVersioningAfterReplicationDeleteSucceeds() {
        given()
            .body("""
                    <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Enabled</Status>
                    </VersioningConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void putReplicationStoresConfiguration() {
        given()
            .body(REPLICATION_XML)
        .when()
            .put("/" + BUCKET + "?replication")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void getReplicationRoundTripsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("<Role>arn:aws:iam::000000000000:role/replication-role</Role>"))
            .body(containsString("<ID>rule-1</ID>"))
            .body(containsString("<Status>Enabled</Status>"))
            .body(containsString("<Bucket>arn:aws:s3:::replication-dest</Bucket>"));
    }

    @Test
    @Order(8)
    void putReplicationWithoutRoleReturnsMalformedXml() {
        given()
            .body("""
                    <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Rule>
                            <Status>Enabled</Status>
                            <Destination>
                                <Bucket>arn:aws:s3:::replication-dest</Bucket>
                            </Destination>
                        </Rule>
                    </ReplicationConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?replication")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(9)
    void deleteReplicationRemovesConfiguration() {
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
    }

    /**
     * Regression test for {@code PUT /{bucket}?replication} falling through to CreateBucket:
     * outside us-east-1 the fall-through surfaced as {@code 409 BucketAlreadyOwnedByYou}
     * (and in us-east-1 as a silent false success).
     */
    @Test
    @Order(10)
    void putReplicationOnNonUsEast1BucketDoesNot409() {
        given()
            .body("""
                    <CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <LocationConstraint>us-west-2</LocationConstraint>
                    </CreateBucketConfiguration>
                    """)
        .when()
            .put("/" + WEST_BUCKET)
        .then()
            .statusCode(200);

        given()
            .body(REPLICATION_XML)
        .when()
            .put("/" + WEST_BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(not(containsString("BucketAlreadyOwnedByYou")));

        given()
        .when()
            .get("/" + WEST_BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("<ID>rule-1</ID>"));

        given()
        .when()
            .delete("/" + WEST_BUCKET)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(11)
    void unqualifiedDeleteStillRemovesBucket() {
        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
        // Bucket is gone now: a sub-resource GET should report NoSuchBucket.
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}
