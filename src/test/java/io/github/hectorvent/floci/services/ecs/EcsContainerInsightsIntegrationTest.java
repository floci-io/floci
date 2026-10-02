package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * A cluster whose CloudFormation {@code ClusterSettings} enable Container Insights publishes its
 * services' task counts to {@code ECS/ContainerInsights}, which is what an alarm on a stopped
 * service watches. The reconciler ticks every five seconds: one tick launches the task, the next
 * counts it and publishes.
 */
@QuarkusTest
class EcsContainerInsightsIntegrationTest {

    private static final String CLOUDWATCH_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260908/us-east-1/monitoring/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClusterWithInsightsFromCloudFormationPublishesItsRunningTaskCount() {
        String cluster = "insights-" + Long.toString(System.nanoTime(), 36);
        String template = """
            {"Resources": {
              "Cluster": {"Type": "AWS::ECS::Cluster", "Properties": {"ClusterName": "%1$s",
                "ClusterSettings": [{"Name": "containerInsights", "Value": "enabled"}]}},
              "TaskDef": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"Family": "%1$s",
                "NetworkMode": "awsvpc", "RequiresCompatibilities": ["FARGATE"], "Cpu": "256", "Memory": "512",
                "ContainerDefinitions": [{"Name": "app", "Image": "nginx:alpine", "Essential": true}]}},
              "Service": {"Type": "AWS::ECS::Service", "Properties": {"ServiceName": "web",
                "Cluster": {"Ref": "Cluster"}, "TaskDefinition": {"Ref": "TaskDef"},
                "DesiredCount": 1, "LaunchType": "FARGATE", "NetworkConfiguration": {"AwsvpcConfiguration":
                  {"Subnets": ["subnet-default-us-east-1-a"]}}}}}}
            """.formatted(cluster);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", cluster)
                .formParam("TemplateBody", template)
                .post("/").then().statusCode(200);
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", cluster)
                .post("/").then().statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DescribeClusters")
                .body("{\"clusters\": [\"" + cluster + "\"], \"include\": [\"SETTINGS\"]}")
                .post("/").then().statusCode(200)
                .body("clusters[0].settings[0].value", equalTo("enabled"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> given()
                .contentType("application/x-amz-json-1.0")
                .header("Authorization", CLOUDWATCH_AUTH)
                .header("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricStatistics")
                .body("""
                    {"Namespace": "ECS/ContainerInsights", "MetricName": "RunningTaskCount",
                     "Dimensions": [{"Name": "ClusterName", "Value": "%s"}, {"Name": "ServiceName", "Value": "web"}],
                     "StartTime": %d, "EndTime": %d, "Period": 60, "Statistics": ["Maximum"]}
                    """.formatted(cluster, Instant.now().minusSeconds(300).getEpochSecond(),
                        Instant.now().plusSeconds(60).getEpochSecond()))
                .post("/").then().statusCode(200)
                .body("Datapoints.Maximum", hasItem(1.0f)));
    }
}
