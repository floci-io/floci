package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@Tag("docker")
class RdsAwsIntegrationTest {

    private static final String RDS_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rds/aws4_request";

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
    void createDescribeRestoreRoundTrip() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String dbId = "test-db-" + suffix;
        String snapshotId = "test-snap-" + suffix;
        String restoreId = "test-restore-" + suffix;

        // 1. Create DB instance
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
            .statusCode(200)
            .body(containsString(dbId));

        // 2. Wait for it to be available
        waitForDb(dbId);

        // 3. Create snapshot
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

        // 4. Restore DB instance
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

        // 5. Wait for it to be available
        waitForDb(restoreId);

        // 6. Describe restored DB instance to ensure it exists
        given()
            .header("Authorization", RDS_AUTH)
            .formParam("Action", "DescribeDBInstances")
            .formParam("DBInstanceIdentifier", restoreId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(restoreId));
    }

    private void waitForDb(String dbId) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = given()
                .header("Authorization", RDS_AUTH)
                .formParam("Action", "DescribeDBInstances")
                .formParam("DBInstanceIdentifier", dbId)
            .when()
                .post("/")
            .then()
                .extract().xmlPath().getString("DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.DBInstanceStatus");
            
            if ("available".equals(status) || status == null) {
                return; // mock mode might return available immediately or not have status depending on delay config
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB instance " + dbId + " did not become available.");
    }
}
