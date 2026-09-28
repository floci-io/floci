package io.github.hectorvent.floci.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Aurora Serverless v2 auto-pause against a real PostgreSQL container: an idle cluster with
 * MinCapacity 0 is frozen with docker pause while it reports "available", zero capacity and the
 * pause events, and the next connection through its endpoint resumes it and finds its data. The
 * API does not accept a SecondsUntilAutoPause below 300, so the test runs the idle check directly
 * instead of waiting for it.
 */
@QuarkusTest
@Tag("docker")
class RdsAuroraAutoPauseIntegrationTest {

    private static final String RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260923/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";
    private static final String OWNER_RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=222222222222/20260923/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";
    private static final String CLOUDWATCH_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260923/us-east-1/monitoring/aws4_request";
    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";

    @Inject
    DockerClient dockerClient;

    @Inject
    RdsService rdsService;

    @Inject
    RdsContainerManager containerManager;

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

    @Test
    void idleClusterPausesAndItsNextConnectionResumesIt() throws Exception {
        String clusterId = "auto-pause-" + Long.toString(System.nanoTime(), 36);
        String instanceId = clusterId + "-1";
        Instant start = Instant.now();
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", clusterId)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", MASTER_USER)
                .formParam("MasterUserPassword", MASTER_PASSWORD)
                .formParam("DatabaseName", "appdb")
                .formParam("ServerlessV2ScalingConfiguration.MinCapacity", "0")
                .formParam("ServerlessV2ScalingConfiguration.MaxCapacity", "2")
                .formParam("ServerlessV2ScalingConfiguration.SecondsUntilAutoPause", "300")
        .when().post("/").then().statusCode(200);
        try {
            rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBInstanceClass", "db.serverless")
            .when().post("/").then().statusCode(200);
            DbCluster cluster = rdsService.getDbCluster(clusterId);
            String containerId = cluster.getContainerId();
            String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().port()
                    + "/appdb?sslmode=disable";
            try (Connection connection = DriverManager.getConnection(jdbcUrl, MASTER_USER, MASTER_PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE marker(v text); INSERT INTO marker VALUES ('before-pause');");
            }

            // The proxy lets go of the closed connection on its own thread.
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> containerManager.pauseIfIdle(cluster.getDbClusterArn()));
            assertTrue(isPaused(containerId), "the idle cluster's container is frozen");
            rds("DescribeDBClusters").formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then().statusCode(200)
                    .body(containsString("<Status>available</Status>"));
            rds("DescribeDBInstances").formParam("DBInstanceIdentifier", instanceId)
            .when().post("/").then().statusCode(200)
                    .body(containsString("<DBInstanceStatus>available</DBInstanceStatus>"));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    events(instanceId).then().statusCode(200)
                            .body(containsString("<Message>Initiated pause for the DB instance.</Message>"))
                            .body(containsString("<Message>Successfully paused the DB instance.</Message>")));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                for (String metricName : List.of("ServerlessDatabaseCapacity", "CPUUtilization")) {
                    List<Float> minimums = metricMinimums(metricName, clusterId, start);
                    assertFalse(minimums.isEmpty(), "a paused cluster reports its " + metricName);
                    assertEquals(0.0f, minimums.get(minimums.size() - 1), metricName);
                }
            });

            try (Connection connection = DriverManager.getConnection(jdbcUrl, MASTER_USER, MASTER_PASSWORD);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT v FROM marker")) {
                assertTrue(rows.next(), "the data written before the pause is still there");
                assertEquals("before-pause", rows.getString(1));
            }
            assertFalse(isPaused(containerId), "the connection resumed the container");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    events(instanceId).then().statusCode(200)
                            .body(containsString("<Message>Initiated resume for the DB instance.</Message>"))
                            .body(containsString("<Message>Successfully resumed the DB instance.</Message>")));

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> containerManager.pauseIfIdle(cluster.getDbClusterArn()));
            rds("ModifyDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("ServerlessV2ScalingConfiguration.MinCapacity", "0.5")
                    .formParam("ServerlessV2ScalingConfiguration.MaxCapacity", "2")
            .when().post("/").then().statusCode(200);
            await().atMost(Duration.ofSeconds(10)).until(() -> !isPaused(containerId));
            assertFalse(containerManager.pauseIfIdle(cluster.getDbClusterArn()),
                    "a nonzero MinCapacity keeps the cluster running");
        } finally {
            rds("DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
        }
    }

    @Test
    void pauseEventsBelongToTheAccountThatOwnsTheCluster() throws Exception {
        String clusterId = "auto-pause-owner-" + Long.toString(System.nanoTime(), 36);
        String instanceId = clusterId + "-1";
        XmlPath created = rds(OWNER_RDS_AUTH, "CreateDBCluster")
                .formParam("DBClusterIdentifier", clusterId)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", MASTER_USER)
                .formParam("MasterUserPassword", MASTER_PASSWORD)
                .formParam("DatabaseName", "appdb")
                .formParam("ServerlessV2ScalingConfiguration.MinCapacity", "0")
                .formParam("ServerlessV2ScalingConfiguration.MaxCapacity", "2")
                .formParam("ServerlessV2ScalingConfiguration.SecondsUntilAutoPause", "300")
        .when().post("/").then().statusCode(200).extract().xmlPath();
        String clusterArn = created.getString("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DBClusterArn");
        int port = created.getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");
        try {
            rds(OWNER_RDS_AUTH, "CreateDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBInstanceClass", "db.serverless")
            .when().post("/").then().statusCode(200);

            // The pause and resume events reach RDS on a background thread, outside any request.
            await().atMost(Duration.ofSeconds(10)).until(() -> containerManager.pauseIfIdle(clusterArn));
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:" + port + "/appdb?sslmode=disable", MASTER_USER, MASTER_PASSWORD)) {
                assertTrue(connection.isValid(5), "the connection resumed the cluster");
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    events(OWNER_RDS_AUTH, instanceId).then().statusCode(200)
                            .body(containsString("<Message>Initiated pause for the DB instance.</Message>"))
                            .body(containsString("<Message>Successfully paused the DB instance.</Message>"))
                            .body(containsString("<Message>Initiated resume for the DB instance.</Message>"))
                            .body(containsString("<Message>Successfully resumed the DB instance.</Message>")));
            events(RDS_AUTH, instanceId).then().statusCode(200)
                    .body(not(containsString("<Event>")));
        } finally {
            rds(OWNER_RDS_AUTH, "DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds(OWNER_RDS_AUTH, "DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
        }
    }

    private static RequestSpecification rds(String action) {
        return rds(RDS_AUTH, action);
    }

    private static RequestSpecification rds(String authorization, String action) {
        return given().header("Authorization", authorization)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static Response events(String instanceId) {
        return events(RDS_AUTH, instanceId);
    }

    private static Response events(String authorization, String instanceId) {
        return rds(authorization, "DescribeEvents")
                .formParam("SourceType", "db-instance")
                .formParam("SourceIdentifier", instanceId)
                .formParam("Duration", "60")
        .when().post("/");
    }

    private static List<Float> metricMinimums(String metricName, String clusterId, Instant from) {
        String body = """
                {"Namespace":"AWS/RDS","MetricName":"%s",
                 "Dimensions":[{"Name":"DBClusterIdentifier","Value":"%s"}],
                 "StartTime":%d,"EndTime":%d,"Period":60,"Statistics":["Minimum"]}
                """.formatted(metricName, clusterId, from.minusSeconds(60).getEpochSecond(),
                Instant.now().plusSeconds(60).getEpochSecond());
        return given().contentType("application/x-amz-json-1.0")
                .header("Authorization", CLOUDWATCH_AUTH)
                .header("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricStatistics")
                .body(body)
                .post("/")
                .then().statusCode(200)
                .extract().jsonPath().getList("Datapoints.Minimum", Float.class);
    }

    private boolean isPaused(String containerId) {
        return Boolean.TRUE.equals(dockerClient.inspectContainerCmd(containerId).exec().getState().getPaused());
    }
}
