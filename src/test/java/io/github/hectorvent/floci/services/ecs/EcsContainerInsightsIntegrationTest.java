package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * A cluster whose CloudFormation {@code ClusterSettings} enable Container Insights publishes its
 * services' task counts to {@code ECS/ContainerInsights}, which is what an alarm on a stopped
 * service watches. The test drives the reconciler itself, as the other ECS integration tests do,
 * rather than waiting on its five-second ticks: one pass launches the task, the next counts it.
 */
@QuarkusTest
class EcsContainerInsightsIntegrationTest {

    @InjectSpy
    EcsService ecsService;

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
        awaitStackStatus(cluster, "CREATE_COMPLETE");
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

        ecsService.reconcileServices();
        ecsService.reconcileServices();

        given()
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
                .body("Datapoints.Maximum", hasItem(1.0f));
    }

    /**
     * The update's settings land and the call still fails, and the provisioner's own restore fails
     * too, so the stack ends in UPDATE_ROLLBACK_FAILED with the new setting live. The snapshot
     * survives on the resource the stack keeps, so ContinueUpdateRollback puts the prior setting
     * back rather than reporting a rollback with nothing left to do.
     */
    @Test
    void continueUpdateRollbackRetriesASettingsRestoreThatFailed() {
        String cluster = "insights-rb-" + Long.toString(System.nanoTime(), 36);
        String template = """
            {"Resources": {"Cluster": {"Type": "AWS::ECS::Cluster", "Properties": {"ClusterName": "%s",
              "ClusterSettings": [{"Name": "containerInsights", "Value": "%s"}]}}}}
            """;
        cloudFormation("CreateStack", cluster, template.formatted(cluster, "disabled"));
        awaitStackStatus(cluster, "CREATE_COMPLETE");

        List<ClusterSetting> enabled = List.of(new ClusterSetting("containerInsights", "enabled"));
        doAnswer(invocation -> {
            if (enabled.equals(invocation.getArgument(1))) {
                invocation.callRealMethod();
            }
            throw new IllegalStateException("simulated settings write failure");
        }).when(ecsService).updateClusterSettings(anyString(), anyList(), anyString());
        try {
            cloudFormation("UpdateStack", cluster, template.formatted(cluster, "enabled"));
            awaitStackStatus(cluster, "UPDATE_ROLLBACK_FAILED");
        } finally {
            doCallRealMethod().when(ecsService).updateClusterSettings(anyString(), anyList(), anyString());
        }
        assertInsightsSetting(cluster, "enabled");

        CfnStackWaits.continueUpdateRollback(cluster, null);
        awaitStackStatus(cluster, "UPDATE_ROLLBACK_COMPLETE");
        assertInsightsSetting(cluster, "disabled");
    }

    /**
     * An update that adds a cluster under the name of one created outside the stack gets that
     * cluster back from the idempotent create. When its settings then land and the call still
     * fails, the cluster gets its own settings back, and the update rollback, which deletes what
     * the update added, leaves the cluster in place because the resource never claimed it.
     */
    @Test
    void aFailedUpdateLeavesAClusterItFoundAsItWas() {
        String name = "insights-pre-" + Long.toString(System.nanoTime(), 36);
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.CreateCluster")
                .body("{\"clusterName\": \"" + name + "\", "
                        + "\"settings\": [{\"name\": \"containerInsights\", \"value\": \"disabled\"}]}")
                .post("/").then().statusCode(200);
        String parameter = """
            "Param": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "v"}}""";
        cloudFormation("CreateStack", name, "{\"Resources\": {" + parameter + "}}");
        awaitStackStatus(name, "CREATE_COMPLETE");

        List<ClusterSetting> enabled = List.of(new ClusterSetting("containerInsights", "enabled"));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (enabled.equals(invocation.getArgument(1))) {
                throw new IllegalStateException("simulated settings write failure");
            }
            return result;
        }).when(ecsService).updateClusterSettings(anyString(), anyList(), anyString());
        try {
            cloudFormation("UpdateStack", name, """
                {"Resources": {%s, "Cluster": {"Type": "AWS::ECS::Cluster", "Properties": {"ClusterName": "%s",
                  "ClusterSettings": [{"Name": "containerInsights", "Value": "enabled"}]}}}}
                """.formatted(parameter, name));
            awaitStackStatus(name, "UPDATE_ROLLBACK_COMPLETE");
        } finally {
            doCallRealMethod().when(ecsService).updateClusterSettings(anyString(), anyList(), anyString());
        }

        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DescribeClusters")
                .body("{\"clusters\": [\"" + name + "\"], \"include\": [\"SETTINGS\"]}")
                .post("/").then().statusCode(200)
                .body("clusters[0].status", equalTo("ACTIVE"))
                .body("clusters[0].settings[0].value", equalTo("disabled"));
    }

    private static void cloudFormation(String action, String stack, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.post("/").then().statusCode(200);
    }

    private static void awaitStackStatus(String stack, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .post("/").then().statusCode(200)
                .body(containsString("<StackStatus>" + status + "</StackStatus>")));
    }

    private static void assertInsightsSetting(String cluster, String value) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DescribeClusters")
                .body("{\"clusters\": [\"" + cluster + "\"], \"include\": [\"SETTINGS\"]}")
                .post("/").then().statusCode(200)
                .body("clusters[0].settings[0].value", equalTo(value));
    }
}
