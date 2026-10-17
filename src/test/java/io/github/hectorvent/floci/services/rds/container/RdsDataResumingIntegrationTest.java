package io.github.hectorvent.floci.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(RdsDataResumingIntegrationTest.ResumingProfile.class)
@Tag("docker")
class RdsDataResumingIntegrationTest {

    private static final String RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/rds/aws4_request";

    @Inject
    DockerClient dockerClient;

    @Inject
    RdsService rdsService;

    @Inject
    RdsContainerManager containerManager;

    @Test
    void pausedAuroraReturnsTypedHttpErrorsAndExecutesTheRetry() {
        dockerClient.pingCmd().exec();
        RestAssuredJsonUtils.configureAwsContentTypes();
        String clusterId = "data-resuming-" + Long.toString(System.nanoTime(), 36);
        String instanceId = clusterId + "-1";
        String secretArn = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "secretsmanager.CreateSecret")
                .body(Map.of("Name", clusterId, "SecretString", "{\"username\":\"master\",\"password\":\"Master123!\"}"))
                .post("/").then().statusCode(200).extract().path("ARN");
        try {
            rds("CreateDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("MasterUsername", "master").formParam("MasterUserPassword", "Master123!")
                    .formParam("DatabaseName", "appdb")
                    .formParam("ServerlessV2ScalingConfiguration.MinCapacity", "0")
                    .formParam("ServerlessV2ScalingConfiguration.MaxCapacity", "2")
                    .formParam("ServerlessV2ScalingConfiguration.SecondsUntilAutoPause", "300")
                    .post("/").then().statusCode(200);
            rds("CreateDBInstance").formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBClusterIdentifier", clusterId).formParam("Engine", "aurora-postgresql")
                    .formParam("DBInstanceClass", "db.serverless").post("/").then().statusCode(200);
            String clusterArn = rdsService.getDbCluster(clusterId).getDbClusterArn();
            await().atMost(Duration.ofSeconds(10)).until(() -> containerManager.pauseIfIdle(clusterArn));
            Map<String, Object> request = Map.of("resourceArn", clusterArn, "secretArn", secretArn,
                    "database", "appdb", "sql", "select 1", "parameterSets", List.of(List.of()));

            for (String operation : List.of("Execute", "BeginTransaction", "BatchExecute")) {
                given().contentType("application/json").body(request).post("/" + operation)
                        .then().statusCode(400)
                        .header("X-Amzn-Errortype", "DatabaseResumingException")
                        .body("__type", equalTo("DatabaseResumingException"));
            }

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    given().contentType("application/json").body(request).post("/Execute")
                            .then().statusCode(200).body("records[0][0].longValue", equalTo(1)));
            given().contentType("application/json").body(request).post("/Execute")
                    .then().statusCode(200).body("records[0][0].longValue", equalTo(1));
        } finally {
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", instanceId)
                    .formParam("SkipFinalSnapshot", "true").post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true").post("/");
            given().contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "secretsmanager.DeleteSecret")
                    .body(Map.of("SecretId", secretArn, "ForceDeleteWithoutRecovery", true)).post("/");
        }
    }

    private static RequestSpecification rds(String action) {
        return given().header("Authorization", RDS_AUTH).contentType(URLENC)
                .formParam("Action", action).formParam("Version", "2014-10-31");
    }

    public static class ResumingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds-data.database-resuming-exception-enabled", "true",
                    "floci.services.rds.aurora-resume-delay-millis", "5000");
        }
    }
}
