package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployServerIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";

    private static String deploymentId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ── On-premises instance CRUD ──────────────────────────────────────────

    @Test
    @Order(1)
    void registerOnPremisesInstance() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.RegisterOnPremisesInstance")
                .body("""
                        {
                          "instanceName": "on-prem-test-1",
                          "iamUserArn": "arn:aws:iam::000000000000:user/codedeploy-agent"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(2)
    void getOnPremisesInstance() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.GetOnPremisesInstance")
                .body("{\"instanceName\":\"on-prem-test-1\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("instanceInfo.instanceName", equalTo("on-prem-test-1"))
                .body("instanceInfo.registrationStatus", equalTo("Registered"))
                .body("instanceInfo.iamUserArn", equalTo("arn:aws:iam::000000000000:user/codedeploy-agent"));
    }

    @Test
    @Order(3)
    void addTagsToOnPremisesInstance() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.AddTagsToOnPremisesInstances")
                .body("""
                        {
                          "instanceNames": ["on-prem-test-1"],
                          "tags": [{"Key": "Env", "Value": "test"}, {"Key": "Role", "Value": "web"}]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void listOnPremisesInstancesRegistered() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.ListOnPremisesInstances")
                .body("{\"registrationStatus\":\"Registered\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("instanceNames", hasItem("on-prem-test-1"));
    }

    @Test
    @Order(5)
    void batchGetOnPremisesInstances() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetOnPremisesInstances")
                .body("{\"instanceNames\":[\"on-prem-test-1\"]}")
                .when().post("/")
                .then().statusCode(200)
                .body("instanceInfos", hasSize(1))
                .body("instanceInfos[0].instanceName", equalTo("on-prem-test-1"))
                .body("instanceInfos[0].registrationStatus", equalTo("Registered"));
    }

    @Test
    @Order(6)
    void removeTagsFromOnPremisesInstance() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.RemoveTagsFromOnPremisesInstances")
                .body("""
                        {
                          "instanceNames": ["on-prem-test-1"],
                          "tags": [{"Key": "Role", "Value": "web"}]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);
    }

    // ── Server platform deployment ─────────────────────────────────────────

    @Test
    @Order(7)
    void createServerApplication() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
                .body("{\"applicationName\":\"server-test-app\",\"computePlatform\":\"Server\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("applicationId", notNullValue());
    }

    @Test
    @Order(8)
    void createServerDeploymentGroup() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
                .body("""
                        {
                          "applicationName": "server-test-app",
                          "deploymentGroupName": "server-test-group",
                          "deploymentConfigName": "CodeDeployDefault.AllAtOnce",
                          "serviceRoleArn": "arn:aws:iam::000000000000:role/CodeDeployRole",
                          "onPremisesInstanceTagFilters": [{"Key": "Env", "Value": "test", "Type": "KEY_AND_VALUE"}]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("deploymentGroupId", notNullValue());
    }

    @Test
    @Order(9)
    void createServerDeployment() {
        String appSpec = """
                os: linux
                hooks:
                  ApplicationStart:
                    - location: scripts/start_server.sh
                      timeout: 30
                """;

        String escapedAppSpec = appSpec.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");

        deploymentId = given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeployment")
                .body("""
                        {
                          "applicationName": "server-test-app",
                          "deploymentGroupName": "server-test-group",
                          "description": "Test server deployment",
                          "revision": {
                            "revisionType": "AppSpecContent",
                            "appSpecContent": {
                              "content": "%s"
                            }
                          }
                        }
                        """.formatted(escapedAppSpec))
                .when().post("/")
                .then().statusCode(200)
                .body("deploymentId", notNullValue())
                .extract().jsonPath().getString("deploymentId");
    }

    @Test
    @Order(10)
    void getServerDeploymentCompletesSuccessfully() throws InterruptedException {
        // Poll until final state (max 5s)
        String status = "Queued";
        for (int i = 0; i < 50 && !"Succeeded".equals(status) && !"Failed".equals(status); i++) {
            Thread.sleep(100);
            status = given()
                    .contentType(CT)
                    .header("X-Amz-Target", "CodeDeploy_20141006.GetDeployment")
                    .body("{\"deploymentId\":\"" + deploymentId + "\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath().getString("deploymentInfo.status");
        }
        Assertions.assertEquals("Succeeded", status, "Server deployment did not reach Succeeded state");
    }

    @Test
    @Order(11)
    void listDeploymentTargetsForServerDeployment() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentTargets")
                .body("{\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("targetIds", hasItem("on-prem-test-1"));
    }

    @Test
    @Order(12)
    void batchGetDeploymentTargetsForServer() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetDeploymentTargets")
                .body("""
                        {
                          "deploymentId": "%s",
                          "targetIds": ["on-prem-test-1"]
                        }
                        """.formatted(deploymentId))
                .when().post("/")
                .then().statusCode(200)
                .body("deploymentTargets", hasSize(1))
                .body("deploymentTargets[0].deploymentTargetType", equalTo("InstanceTarget"))
                .body("deploymentTargets[0].instanceTarget.targetId", equalTo("on-prem-test-1"))
                .body("deploymentTargets[0].instanceTarget.status", equalTo("Succeeded"));
    }

    @Test
    @Order(13)
    void deregisterOnPremisesInstance() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.DeregisterOnPremisesInstance")
                .body("{\"instanceName\":\"on-prem-test-1\"}")
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.GetOnPremisesInstance")
                .body("{\"instanceName\":\"on-prem-test-1\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("instanceInfo.registrationStatus", equalTo("Deregistered"));
    }

    @Test
    @Order(14)
    void listOnPremisesInstancesDeregistered() {
        given()
                .contentType(CT)
                .header("X-Amz-Target", "CodeDeploy_20141006.ListOnPremisesInstances")
                .body("{\"registrationStatus\":\"Deregistered\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("instanceNames", hasItem("on-prem-test-1"));
    }
}
