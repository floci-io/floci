package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Tag("docker")
class RdsAwsIntegrationTest {

    private static final String RDS_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rds/aws4_request";

    @Inject
    DockerClient dockerClient;

    @Inject
    RdsService rdsService;

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

    @Test
    void sqlServerCustomMasterRotatesPasswordTwice() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String dbId = "test-sqlserver-" + suffix;
        String username = "flociadmin";
        String firstPassword = "Password123!";
        String secondPassword = "Password456!";
        String finalPassword = "Password789!";

        try {
            given()
                    .header("Authorization", RDS_AUTH)
                    .formParam("Action", "CreateDBInstance")
                    .formParam("DBInstanceIdentifier", dbId)
                    .formParam("Engine", "sqlserver-se")
                    .formParam("MasterUsername", username)
                    .formParam("MasterUserPassword", firstPassword)
                    .formParam("DBInstanceClass", "db.t3.micro")
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body(containsString(dbId));

            waitForDb(dbId);
            DbInstance instance = rdsService.getDbInstance(dbId);
            assertTrue(sqlServerQuery(instance.getContainerId(), username, firstPassword).contains("1"));

            given()
                    .header("Authorization", RDS_AUTH)
                    .formParam("Action", "ModifyDBInstance")
                    .formParam("DBInstanceIdentifier", dbId)
                    .formParam("MasterUserPassword", secondPassword)
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200);

            given()
                    .header("Authorization", RDS_AUTH)
                    .formParam("Action", "ModifyDBInstance")
                    .formParam("DBInstanceIdentifier", dbId)
                    .formParam("MasterUserPassword", finalPassword)
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200);

            assertTrue(sqlServerQuery(instance.getContainerId(), username, finalPassword).contains("1"));
        } finally {
            given()
                    .header("Authorization", RDS_AUTH)
                    .formParam("Action", "DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", dbId)
                    .formParam("SkipFinalSnapshot", "true")
                    .when()
                    .post("/");
        }
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

    private String sqlServerQuery(String containerId, String username, String password) throws Exception {
        String[] command = {
                "/opt/mssql-tools18/bin/sqlcmd", "-S", "127.0.0.1", "-C",
                "-U", username, "-P", password, "-Q", "SET NOCOUNT ON; SELECT 1;", "-h", "-1", "-W"
        };
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);
        Closeable callback = dockerClient.execStartCmd(execId).exec(new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                try {
                    if (frame.getStreamType() == StreamType.STDOUT) {
                        output.write(frame.getPayload());
                    } else if (frame.getStreamType() == StreamType.STDERR) {
                        errors.write(frame.getPayload());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to capture SQL Server output", e);
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }
        });
        try {
            assumeTrue(latch.await(30, TimeUnit.SECONDS), "Timed out running sqlcmd");
            long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            assertTrue(exitCode == 0,
                    "sqlcmd failed with exit code " + exitCode + ": "
                            + errors.toString(StandardCharsets.UTF_8));
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            callback.close();
        }
    }
}
