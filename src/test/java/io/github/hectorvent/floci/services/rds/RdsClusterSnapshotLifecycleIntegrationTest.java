package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
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
 * The cluster snapshot lifecycle against real Postgres containers: a row written before the
 * snapshot comes back from a cluster restored from a copy of that snapshot, the source snapshot
 * can be deleted without touching the copy, and each refusal carries the AWS error.
 */
@QuarkusTest
@Tag("docker")
class RdsClusterSnapshotLifecycleIntegrationTest {

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
    void clusterSnapshotRoundTripsTheClustersDataIntoARestoredCluster() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String cluster = "csnap-src-" + suffix;
        String snapshot = "csnap-" + suffix;
        String copy = "csnap-copy-" + suffix;
        String restored = "csnap-restored-" + suffix;

        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", cluster)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DatabaseName", "appdb")
        .when().post("/").then().statusCode(200);
        waitForCluster(cluster);
        DbCluster source = rdsService.getDbCluster(cluster);
        psql(source.getContainerId(), "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('cluster-row');");

        query("CreateDBClusterSnapshot")
                .formParam("DBClusterSnapshotIdentifier", snapshot)
                .formParam("DBClusterIdentifier", cluster)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "platform")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CreateDBClusterSnapshotResult>"))
                .body(containsString("<Engine>aurora-postgresql</Engine>"))
                .body(containsString("<Status>available</Status>"))
                .body(containsString("<SnapshotType>manual</SnapshotType>"))
                .body(containsString("<PercentProgress>100</PercentProgress>"))
                .body(containsString(":cluster-snapshot:" + snapshot + "</DBClusterSnapshotArn>"));

        query("DescribeDBClusterSnapshots")
                .formParam("DBClusterIdentifier", cluster)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterSnapshotIdentifier>" + snapshot + "</DBClusterSnapshotIdentifier>"));

        String sourceArn = "arn:aws:rds:us-east-1:000000000000:cluster-snapshot:" + snapshot;
        query("CopyDBClusterSnapshot")
                .formParam("SourceDBClusterSnapshotIdentifier", sourceArn)
                .formParam("TargetDBClusterSnapshotIdentifier", copy)
                .formParam("CopyTags", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<SourceDBClusterSnapshotArn>" + sourceArn + "</SourceDBClusterSnapshotArn>"))
                .body(containsString("<Value>platform</Value>"));

        query("ModifyDBClusterSnapshotAttribute")
                .formParam("DBClusterSnapshotIdentifier", copy)
                .formParam("AttributeName", "restore")
                .formParam("ValuesToAdd.member.1", "all")
        .when().post("/").then().statusCode(200)
                .body(containsString("<AttributeValue>all</AttributeValue>"));

        query("DeleteDBClusterSnapshot")
                .formParam("DBClusterSnapshotIdentifier", snapshot)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>deleted</Status>"));
        query("DescribeDBClusterSnapshots")
                .formParam("DBClusterSnapshotIdentifier", snapshot)
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBClusterSnapshotNotFoundFault</Code>"));

        // Restore by ARN, the form aws_rds_cluster's snapshot_identifier is commonly given.
        query("RestoreDBClusterFromSnapshot")
                .formParam("DBClusterIdentifier", restored)
                .formParam("SnapshotIdentifier", "arn:aws:rds:us-east-1:000000000000:cluster-snapshot:" + copy)
                .formParam("Engine", "aurora-postgresql")
        .when().post("/").then().statusCode(200)
                .body(containsString("<RestoreDBClusterFromSnapshotResult>"))
                .body(containsString("<DBClusterIdentifier>" + restored + "</DBClusterIdentifier>"));
        waitForCluster(restored);
        DbCluster restoredCluster = rdsService.getDbCluster(restored);
        assertTrue(psql(restoredCluster.getContainerId(), "SELECT v FROM marker;").contains("cluster-row"),
                "the row written before the cluster snapshot must come back from the copy");

        query("DeleteDBClusterSnapshot").formParam("DBClusterSnapshotIdentifier", copy)
                .when().post("/").then().statusCode(200);
        query("DeleteDBCluster").formParam("DBClusterIdentifier", restored).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
        query("DeleteDBCluster").formParam("DBClusterIdentifier", cluster).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
    }

    private void waitForCluster(String clusterId) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = query("DescribeDBClusters")
                    .formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then()
                    .extract().xmlPath().getString(
                            "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.Status");
            if ("available".equals(status)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB cluster " + clusterId + " did not become available.");
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
