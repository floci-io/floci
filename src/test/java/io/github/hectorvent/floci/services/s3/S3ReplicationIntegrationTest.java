package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ReplicationIntegrationTest {

    private static final String BUCKET = "replication-int-test";

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
     * Regression test for the bucket-destroying bug where {@code DELETE /{bucket}?replication}
     * (DeleteBucketReplication) was not handled and fell through to the unqualified
     * {@code DeleteBucket}, silently deleting the entire bucket. Real S3 removes only the
     * replication configuration and returns 204.
     */
    @Test
    @Order(2)
    void deleteReplicationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?replication")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(3)
    void bucketStillExistsAfterReplicationDelete() {
        // A sub-resource-qualified DELETE must never remove the bucket itself.
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
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
    @Order(5)
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
