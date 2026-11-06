package io.floci.aws.services.codedeploy;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployEcsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ELBV2_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private static String lbArn;
    private static String blueTgArn;
    private static String greenTgArn;
    private static String listenerArn;
    private static String deploymentId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ── ELB v2 setup ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createLoadBalancer() {
        lbArn = given()
            .contentType(ELBV2_CONTENT_TYPE)
            .formParam("Action", "CreateLoadBalancer")
            .formParam("Version", "2015-12-01")
            .formParam("Name", "ecs-alb")
            .formParam("Type", "application")
            .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
            .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    @Test
    @Order(2)
    void createBlueTargetGroup() {
        blueTgArn = given()
            .contentType(ELBV2_CONTENT_TYPE)
            .formParam("Action", "CreateTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("Name", "ecs-blue-tg")
            .formParam("Protocol", "HTTP")
            .formParam("Port", "80")
            .formParam("VpcId", "vpc-00000001")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(3)
    void createGreenTargetGroup() {
        greenTgArn = given()
            .contentType(ELBV2_CONTENT_TYPE)
            .formParam("Action", "CreateTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("Name", "ecs-green-tg")
            .formParam("Protocol", "HTTP")
            .formParam("Port", "80")
            .formParam("VpcId", "vpc-00000001")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(4)
    void createListenerPointingToBlue() {
        Assumptions.assumeTrue(lbArn != null, "lbArn must be set");
        Assumptions.assumeTrue(blueTgArn != null, "blueTgArn must be set");
        listenerArn = given()
            .contentType(ELBV2_CONTENT_TYPE)
            .formParam("Action", "CreateListener")
            .formParam("Version", "2015-12-01")
            .formParam("LoadBalancerArn", lbArn)
            .formParam("Protocol", "HTTP")
            .formParam("Port", "18080")
            .formParam("DefaultActions.member.1.Type", "forward")
            .formParam("DefaultActions.member.1.TargetGroupArn", blueTgArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    // ── ECS setup ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void createEcsCluster() {
        given()
            .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {"clusterName": "ecs-deploy-cluster"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo("ecs-deploy-cluster"));
    }

    @Test
    @Order(6)
    void registerTaskDefinition() {
        given()
            .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.RegisterTaskDefinition")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "family": "ecs-deploy-task",
                    "containerDefinitions": [{
                        "name": "app",
                        "image": "nginx:latest",
                        "portMappings": [{"containerPort": 80}]
                    }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo("ecs-deploy-task"));
    }

    @Test
    @Order(7)
    void createEcsService() {
        given()
            .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.CreateService")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "cluster": "ecs-deploy-cluster",
                    "serviceName": "ecs-deploy-service",
                    "taskDefinition": "ecs-deploy-task:1",
                    "desiredCount": 1,
                    "deploymentController": {"type": "EXTERNAL"}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.serviceName", equalTo("ecs-deploy-service"));
    }

    // ── CodeDeploy ECS deployment ─────────────────────────────────────────────

    @Test
    @Order(8)
    void createEcsApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "ecs-deploy-app",
                    "computePlatform": "ECS"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationId", notNullValue());
    }

    @Test
    @Order(9)
    void createEcsDeploymentGroup() {
        Assumptions.assumeTrue(blueTgArn != null, "blueTgArn must be set");
        Assumptions.assumeTrue(greenTgArn != null, "greenTgArn must be set");
        Assumptions.assumeTrue(listenerArn != null, "listenerArn must be set");

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body(String.format("""
                {
                    "applicationName": "ecs-deploy-app",
                    "deploymentGroupName": "ecs-deploy-dg",
                    "deploymentConfigName": "CodeDeployDefault.ECSAllAtOnce",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy-role",
                    "computePlatform": "ECS",
                    "deploymentStyle": {
                        "deploymentType": "BLUE_GREEN",
                        "deploymentOption": "WITH_TRAFFIC_CONTROL"
                    },
                    "ecsServices": [{
                        "clusterName": "ecs-deploy-cluster",
                        "serviceName": "ecs-deploy-service"
                    }],
                    "loadBalancerInfo": {
                        "targetGroupPairInfoList": [{
                            "targetGroups": [
                                {"name": "ecs-blue-tg"},
                                {"name": "ecs-green-tg"}
                            ],
                            "prodTrafficRoute": {
                                "listenerArns": ["%s"]
                            }
                        }]
                    }
                }
                """, listenerArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupId", notNullValue());
    }

    @Test
    @Order(10)
    void createEcsDeployment() {
        Assumptions.assumeTrue(listenerArn != null, "listenerArn must be set");
        String appSpec = """
            {
                "version": 0.0,
                "Resources": [{
                    "TargetService": {
                        "Type": "AWS::ECS::Service",
                        "Properties": {
                            "TaskDefinition": "ecs-deploy-task:1",
                            "LoadBalancerInfo": {
                                "ContainerName": "app",
                                "ContainerPort": 80
                            }
                        }
                    }
                }]
            }
            """;

        // Escape the appSpec for embedding in JSON
        String escapedAppSpec = appSpec.replace("\"", "\\\"").replace("\n", "\\n");

        deploymentId = given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeployment")
            .contentType(CONTENT_TYPE)
            .body(String.format("""
                {
                    "applicationName": "ecs-deploy-app",
                    "deploymentGroupName": "ecs-deploy-dg",
                    "revision": {
                        "revisionType": "AppSpecContent",
                        "appSpecContent": {
                            "content": "%s"
                        }
                    }
                }
                """, escapedAppSpec))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentId", notNullValue())
            .extract().path("deploymentId");
    }

    @Test
    @Order(11)
    void deploymentCompletesSuccessfully() throws InterruptedException {
        Assumptions.assumeTrue(deploymentId != null, "deploymentId must be set");

        String status = null;
        for (int i = 0; i < 20; i++) {
            status = given()
                .header("X-Amz-Target", "CodeDeploy_20141006.GetDeployment")
                .contentType(CONTENT_TYPE)
                .body(String.format("{\"deploymentId\": \"%s\"}", deploymentId))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("deploymentInfo.status");

            if ("Succeeded".equals(status) || "Failed".equals(status) || "Stopped".equals(status)) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }

        Assertions.assertEquals("Succeeded", status, "ECS deployment should succeed");
    }

    @Test
    @Order(12)
    void listDeploymentTargetsReturnsEcsTarget() {
        Assumptions.assumeTrue(deploymentId != null, "deploymentId must be set");

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentTargets")
            .contentType(CONTENT_TYPE)
            .body(String.format("{\"deploymentId\": \"%s\"}", deploymentId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("targetIds", hasItem(containsString("ecs-deploy-cluster")));
    }

    @Test
    @Order(13)
    void batchGetDeploymentTargetsReturnsEcsInfo() {
        Assumptions.assumeTrue(deploymentId != null, "deploymentId must be set");

        String targetId = given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentTargets")
            .contentType(CONTENT_TYPE)
            .body(String.format("{\"deploymentId\": \"%s\"}", deploymentId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("targetIds[0]");

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetDeploymentTargets")
            .contentType(CONTENT_TYPE)
            .body(String.format("""
                {
                    "deploymentId": "%s",
                    "targetIds": ["%s"]
                }
                """, deploymentId, targetId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentTargets[0].deploymentTargetType", equalTo("ECSTarget"))
            .body("deploymentTargets[0].ecsTarget.status", equalTo("Succeeded"));
    }

    @Test
    @Order(14)
    void listenerNowPointsToGreen() {
        Assumptions.assumeTrue(listenerArn != null, "listenerArn must be set");
        Assumptions.assumeTrue(greenTgArn != null, "greenTgArn must be set");

        given()
            .contentType(ELBV2_CONTENT_TYPE)
            .formParam("Action", "DescribeRules")
            .formParam("Version", "2015-12-01")
            .formParam("ListenerArn", listenerArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRulesResponse.DescribeRulesResult.Rules.member.Actions.member.TargetGroupArn",
                    equalTo(greenTgArn));
    }
}
