package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A snapshot restores on an image that creates a template database at startup, such as PostGIS's
 * {@code template_postgis}, which the snapshot's dump creates again.
 */
@QuarkusTest
@Tag("docker")
@TestProfile(RdsTemplateDatabaseRestoreIntegrationTest.PostgisImageProfile.class)
class RdsTemplateDatabaseRestoreIntegrationTest {

    private static final String RDS_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rds/aws4_request";

    public static class PostgisImageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.default-postgres-image", "postgis/postgis:17-3.5-alpine");
        }
    }

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    @Test
    void aSnapshotOfAnImageWithATemplateDatabaseRestores() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String dbId = "postgis-src-" + suffix;
        String snapshotId = "postgis-snap-" + suffix;
        String restoreId = "postgis-restore-" + suffix;

        try {
            given()
                .header("Authorization", RDS_AUTH)
                .formParam("Action", "CreateDBInstance")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret123")
                .formParam("AllocatedStorage", "5")
                .formParam("DBInstanceClass", "db.t3.micro")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("available", waitForDb(dbId));

            given()
                .header("Authorization", RDS_AUTH)
                .formParam("Action", "CreateDBSnapshot")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("DBSnapshotIdentifier", snapshotId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(snapshotId));

            given()
                .header("Authorization", RDS_AUTH)
                .formParam("Action", "RestoreDBInstanceFromDBSnapshot")
                .formParam("DBInstanceIdentifier", restoreId)
                .formParam("DBSnapshotIdentifier", snapshotId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(restoreId));
            assertEquals("available", waitForDb(restoreId));
        } finally {
            deleteInstance(restoreId);
            deleteInstance(dbId);
            given()
                .header("Authorization", RDS_AUTH)
                .formParam("Action", "DeleteDBSnapshot")
                .formParam("DBSnapshotIdentifier", snapshotId)
            .when()
                .post("/");
        }
    }

    private void deleteInstance(String id) {
        given()
            .header("Authorization", RDS_AUTH)
            .formParam("Action", "DeleteDBInstance")
            .formParam("DBInstanceIdentifier", id)
            .formParam("SkipFinalSnapshot", "true")
        .when()
            .post("/");
    }

    private String waitForDb(String dbId) throws Exception {
        String status = null;
        for (int i = 0; i < 120; i++) {
            status = given()
                .header("Authorization", RDS_AUTH)
                .formParam("Action", "DescribeDBInstances")
                .formParam("DBInstanceIdentifier", dbId)
            .when()
                .post("/")
            .then()
                .extract().xmlPath().getString(
                    "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.DBInstanceStatus");
            if ("available".equals(status)) {
                return status;
            }
            Thread.sleep(1000);
        }
        return status;
    }
}
