package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
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
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The manual snapshot lifecycle (#4066) against a real Postgres container: a row written
 * before the snapshot comes back from an instance restored from a copy of that snapshot, the
 * source snapshot can be deleted without touching the copy, and each refusal carries the AWS
 * error. Requested in #4083.
 */
@QuarkusTest
@Tag("docker")
class RdsSnapshotLifecycleIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

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

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    void copyRestoresTheSourcesDataAndDeleteRemovesOnlyItsOwnSnapshot() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String source = "snap-src-" + suffix;
        String snapshot = "snap-" + suffix;
        String copy = "snap-copy-" + suffix;
        String restored = "snap-restored-" + suffix;

        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", source)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBName", "appdb")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "5")
        .when().post("/").then().statusCode(200);
        waitForDb(source);
        DbInstance instance = rdsService.getDbInstance(source);
        psql(instance.getContainerId(), "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('before-snapshot');");

        query("CreateDBSnapshot")
                .formParam("DBInstanceIdentifier", source)
                .formParam("DBSnapshotIdentifier", snapshot)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "platform")
        .when().post("/").then().statusCode(200)
                .body(containsString("<SnapshotType>manual</SnapshotType>"))
                .body(not(containsString("<SourceDBSnapshotIdentifier>")));

        // A copy by ARN, carrying the tags over and adding one.
        String sourceArn = "arn:aws:rds:us-east-1:000000000000:snapshot:" + snapshot;
        query("CopyDBSnapshot")
                .formParam("SourceDBSnapshotIdentifier", sourceArn)
                .formParam("TargetDBSnapshotIdentifier", copy)
                .formParam("CopyTags", "true")
                .formParam("Tags.Tag.1.Key", "stage")
                .formParam("Tags.Tag.1.Value", "test")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CopyDBSnapshotResult>"))
                .body(containsString("<DBSnapshotIdentifier>" + copy + "</DBSnapshotIdentifier>"))
                .body(containsString("<Status>available</Status>"))
                .body(containsString("<SnapshotType>manual</SnapshotType>"))
                // SourceDBSnapshotIdentifier is not asserted: the reference says it "only has a
                // value in the case of a cross-account or cross-Region copy", which this is not.
                .body(containsString("<Value>platform</Value>"))
                .body(containsString("<Value>test</Value>"));

        query("CopyDBSnapshot")
                .formParam("SourceDBSnapshotIdentifier", snapshot)
                .formParam("TargetDBSnapshotIdentifier", copy)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>DBSnapshotAlreadyExists</Code>"));

        // Modify the source, which is deleted below and never restored, so the restore keeps
        // using the image already pulled for the source instance.
        query("ModifyDBSnapshot")
                .formParam("DBSnapshotIdentifier", snapshot)
                .formParam("EngineVersion", "16.4")
        .when().post("/").then().statusCode(200)
                .body(containsString("<ModifyDBSnapshotResult>"))
                .body(containsString("<EngineVersion>16.4</EngineVersion>"));
        query("DescribeDBSnapshots")
                .formParam("DBSnapshotIdentifier", copy)
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<EngineVersion>16.4</EngineVersion>")));

        // Deleting the source leaves the copy and its data behind.
        query("DeleteDBSnapshot")
                .formParam("DBSnapshotIdentifier", snapshot)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DeleteDBSnapshotResult>"))
                .body(containsString("<Status>deleted</Status>"));
        query("DescribeDBSnapshots")
                .formParam("DBSnapshotIdentifier", snapshot)
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBSnapshotNotFound</Code>"));
        query("DeleteDBSnapshot")
                .formParam("DBSnapshotIdentifier", snapshot)
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBSnapshotNotFound</Code>"));

        query("RestoreDBInstanceFromDBSnapshot")
                .formParam("DBInstanceIdentifier", restored)
                .formParam("DBSnapshotIdentifier", copy)
        .when().post("/").then().statusCode(200);
        waitForDb(restored);
        DbInstance restoredInstance = rdsService.getDbInstance(restored);
        assertTrue(psql(restoredInstance.getContainerId(), "SELECT v FROM marker;").contains("before-snapshot"),
                "the row written before the snapshot must come back from the copy");

        query("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", copy)
                .when().post("/").then().statusCode(200);
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", restored)
                .when().post("/").then().statusCode(200);
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", source)
                .when().post("/").then().statusCode(200);
    }

    private void waitForDb(String dbId) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = query("DescribeDBInstances")
                    .formParam("DBInstanceIdentifier", dbId)
            .when().post("/").then()
                    .extract().xmlPath().getString(
                            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.DBInstanceStatus");
            if ("available".equals(status)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB instance " + dbId + " did not become available.");
    }

    private String psql(String containerId, String sql) throws Exception {
        String[] command = {"psql", "-U", "admin", "-d", "appdb", "-tAc", sql};
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
                    throw new IllegalStateException("Failed to capture psql output", e);
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
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out running psql");
            long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            assertTrue(exitCode == 0, "psql failed with exit code " + exitCode + ": " + errors.toString(StandardCharsets.UTF_8));
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            callback.close();
        }
    }
}
