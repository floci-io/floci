package io.floci.aws.services.rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EventRuleQueue;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stop and start against real Postgres containers: a stopped instance has no container and
 * its endpoint refuses connections, a started one has the same endpoint and the row written
 * before the stop, and a cluster carries its member along. Statuses follow the user guide:
 * "stopping" in the stop response, "stopped" afterwards, "starting" then "available".
 */
@QuarkusTest
@Tag("docker")
class RdsStopStartDockerNightlyIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    @Inject
    DockerClient dockerClient;

    @Inject
    RdsService rdsService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

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
    void realClusterRecoveryEmitsMemberEventsAndKeepsSqlData() throws Exception {
        String id = "native-events-" + Long.toUnsignedString(System.nanoTime(), 36);
        String member = id + "-member";
        String account = "000000000000";
        String memberArn = "arn:aws:rds:us-east-1:" + account + ":db:" + member;
        String pattern = "{\"source\":[\"aws.rds\"],\"resources\":[\"" + memberArn + "\"]}";
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), account, Region.US_EAST_1);
             EventRuleQueue queue = EventRuleQueue.route(account, Region.US_EAST_1.id(), id, pattern)) {
            rds.createDBCluster(r -> r.dbClusterIdentifier(id).engine("aurora-postgresql").engineVersion("16.3")
                    .masterUsername("admin").masterUserPassword("password123").databaseName("appdb"));
            rds.createDBInstance(r -> r.dbInstanceIdentifier(member).dbClusterIdentifier(id)
                    .engine("aurora-postgresql").dbInstanceClass("db.r6g.large"));
            try {
                psql(rdsService.getDbCluster(id).getContainerId(),
                        "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('native-event-row');");
                rds.stopDBCluster(r -> r.dbClusterIdentifier(id));
                assertEquals("stopped", rds.describeDBClusters(r -> r.dbClusterIdentifier(id))
                        .dbClusters().getFirst().status());
                rds.startDBCluster(r -> r.dbClusterIdentifier(id));
                assertTrue(psql(rdsService.getDbCluster(id).getContainerId(), "SELECT v FROM marker;")
                        .contains("native-event-row"));
                List<JsonNode> messages = queue.drain();
                assertEquals(2, messages.size());
                for (int i = 0; i < messages.size(); i++) {
                    JsonNode event = messages.get(i);
                    assertEquals("RDS DB Instance Event", event.path("detail-type").asText());
                    assertEquals(memberArn, event.path("resources").get(0).asText());
                    assertEquals(i == 0 ? "RDS-EVENT-0087" : "RDS-EVENT-0088",
                            event.path("detail").path("EventID").asText());
                }
            } finally {
                rds.deleteDBInstance(r -> r.dbInstanceIdentifier(member).skipFinalSnapshot(true));
                rds.deleteDBCluster(r -> r.dbClusterIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    @Test
    void stoppedInstanceKeepsItsDataAndEndpointForTheStart() throws Exception {
        String id = "stop-db-" + Long.toString(System.nanoTime(), 36);
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBName", "appdb")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "5")
        .when().post("/").then().statusCode(200);
        waitForInstance(id, "available");
        DbInstance running = rdsService.getDbInstance(id);
        String containerId = running.getContainerId();
        int port = running.getEndpoint().port();
        psql(containerId, "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('before-stop');");
        assertTrue(portOpen(port), "the endpoint must accept connections while available");

        query("StopDBInstance")
                .formParam("DBInstanceIdentifier", id)
        .when().post("/").then().statusCode(200)
                .body(containsString("<StopDBInstanceResult>"))
                .body(containsString("<DBInstanceStatus>stopping</DBInstanceStatus>"));
        waitForInstance(id, "stopped");
        DbInstance stopped = rdsService.getDbInstance(id);
        assertNull(stopped.getContainerId(), "a stopped instance has no container");
        assertFalse(containerExists(containerId), "the container is gone while stopped");
        assertFalse(portOpen(port), "the endpoint refuses connections while stopped");

        query("ModifyDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("MasterUserPassword", "changed")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceState</Code>"));
        query("StopDBInstance")
                .formParam("DBInstanceIdentifier", id)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceState</Code>"));

        query("StartDBInstance")
                .formParam("DBInstanceIdentifier", id)
        .when().post("/").then().statusCode(200)
                .body(containsString("<StartDBInstanceResult>"))
                .body(containsString("<DBInstanceStatus>starting</DBInstanceStatus>"));
        waitForInstance(id, "available");
        DbInstance started = rdsService.getDbInstance(id);
        assertTrue(started.getEndpoint().port() == port, "the endpoint keeps its port");
        assertTrue(portOpen(port), "the endpoint accepts connections again");
        assertTrue(psql(started.getContainerId(), "SELECT v FROM marker;").contains("before-stop"),
                "the row written before the stop must survive on the volume");

        query("DeleteDBInstance").formParam("DBInstanceIdentifier", id).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
    }

    @Test
    void stoppedClusterCarriesItsMemberAndRebootKeepsTheData() throws Exception {
        String cluster = "stop-cluster-" + Long.toString(System.nanoTime(), 36);
        String member = cluster + "-1";
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", cluster)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DatabaseName", "appdb")
        .when().post("/").then().statusCode(200);
        waitForCluster(cluster, "available");
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", member)
                .formParam("DBClusterIdentifier", cluster)
                .formParam("Engine", "aurora-postgresql")
                .formParam("DBInstanceClass", "db.r6g.large")
        .when().post("/").then().statusCode(200);
        waitForInstance(member, "available");
        DbCluster running = rdsService.getDbCluster(cluster);
        psql(running.getContainerId(), "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('cluster-row');");

        query("StopDBCluster")
                .formParam("DBClusterIdentifier", cluster)
        .when().post("/").then().statusCode(200)
                .body(containsString("<StopDBClusterResult>"))
                .body(containsString("<Status>stopping</Status>"));
        waitForCluster(cluster, "stopped");
        waitForInstance(member, "stopped");
        assertNull(rdsService.getDbCluster(cluster).getContainerId());

        query("StartDBCluster")
                .formParam("DBClusterIdentifier", cluster)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>starting</Status>"));
        waitForCluster(cluster, "available");
        waitForInstance(member, "available");
        assertTrue(psql(rdsService.getDbCluster(cluster).getContainerId(), "SELECT v FROM marker;").contains("cluster-row"));

        query("RebootDBCluster")
                .formParam("DBClusterIdentifier", cluster)
        .when().post("/").then().statusCode(200)
                .body(containsString("<RebootDBClusterResult>"))
                .body(containsString("<Status>rebooting</Status>"));
        waitForCluster(cluster, "available");
        assertTrue(psql(rdsService.getDbCluster(cluster).getContainerId(), "SELECT v FROM marker;").contains("cluster-row"));

        query("DeleteDBInstance").formParam("DBInstanceIdentifier", member).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
        query("DeleteDBCluster").formParam("DBClusterIdentifier", cluster).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket("localhost", port)) {
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containerExists(String containerId) {
        try {
            dockerClient.inspectContainerCmd(containerId).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForInstance(String id, String expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = query("DescribeDBInstances").formParam("DBInstanceIdentifier", id)
                    .when().post("/").then().extract().xmlPath().getString(
                            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.DBInstanceStatus");
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB instance " + id + " did not reach " + expected + ".");
    }

    private void waitForCluster(String id, String expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = query("DescribeDBClusters").formParam("DBClusterIdentifier", id)
                    .when().post("/").then().extract().xmlPath().getString(
                            "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.Status");
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB cluster " + id + " did not reach " + expected + ".");
    }

    private String psql(String containerId, String sql) throws Exception {
        String[] command = {"psql", "-U", "admin", "-d", "appdb", "-tAc", sql};
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(command).withAttachStdout(true).withAttachStderr(true).exec().getId();
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
