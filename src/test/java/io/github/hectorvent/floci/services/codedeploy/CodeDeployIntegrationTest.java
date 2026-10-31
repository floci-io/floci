package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void listDeploymentConfigsIncludesBuiltIns() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentConfigs")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.AllAtOnce"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.HalfAtATime"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.OneAtATime"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.LambdaAllAtOnce"))
            .body("deploymentConfigsList", hasItem("CodeDeployDefault.ECSAllAtOnce"));
    }

    @Test
    @Order(2)
    void getBuiltInDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "CodeDeployDefault.LambdaAllAtOnce"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigInfo.deploymentConfigName", equalTo("CodeDeployDefault.LambdaAllAtOnce"))
            .body("deploymentConfigInfo.computePlatform", equalTo("Lambda"))
            .body("deploymentConfigInfo.trafficRoutingConfig.type", equalTo("AllAtOnce"));
    }

    @Test
    @Order(3)
    void createApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "computePlatform": "Lambda"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationId", notNullValue());
    }

    @Test
    @Order(4)
    void createDuplicateApplicationFails() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app", "computePlatform": "Lambda"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ApplicationAlreadyExistsException"));
    }

    @Test
    @Order(5)
    void getApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("application.applicationName", equalTo("my-lambda-app"))
            .body("application.computePlatform", equalTo("Lambda"))
            .body("application.linkedToGitHub", equalTo(false));
    }

    @Test
    @Order(6)
    void listApplications() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListApplications")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applications", hasItem("my-lambda-app"));
    }

    @Test
    @Order(7)
    void batchGetApplications() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetApplications")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationNames": ["my-lambda-app"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationsInfo", hasSize(1))
            .body("applicationsInfo[0].applicationName", equalTo("my-lambda-app"));
    }

    @Test
    @Order(8)
    void createDeploymentGroup() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupName": "my-lambda-dg",
                    "deploymentConfigName": "CodeDeployDefault.LambdaAllAtOnce",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy-role",
                    "deploymentStyle": {
                        "deploymentType": "BLUE_GREEN",
                        "deploymentOption": "WITH_TRAFFIC_CONTROL"
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupId", notNullValue());
    }

    @Test
    @Order(9)
    void getDeploymentGroup() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupName": "my-lambda-dg"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupInfo.deploymentGroupName", equalTo("my-lambda-dg"))
            .body("deploymentGroupInfo.applicationName", equalTo("my-lambda-app"))
            .body("deploymentGroupInfo.deploymentConfigName", equalTo("CodeDeployDefault.LambdaAllAtOnce"))
            .body("deploymentGroupInfo.serviceRoleArn", equalTo("arn:aws:iam::000000000000:role/codedeploy-role"));
    }

    @Test
    @Order(10)
    void listDeploymentGroups() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentGroups")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("applicationName", equalTo("my-lambda-app"))
            .body("deploymentGroups", hasItem("my-lambda-dg"));
    }

    @Test
    @Order(11)
    void batchGetDeploymentGroups() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.BatchGetDeploymentGroups")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupNames": ["my-lambda-dg"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentGroupsInfo", hasSize(1))
            .body("deploymentGroupsInfo[0].deploymentGroupName", equalTo("my-lambda-dg"));
    }

    @Test
    @Order(12)
    void createCustomDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "deploymentConfigName": "MyCustomConfig",
                    "minimumHealthyHosts": {
                        "type": "FLEET_PERCENT",
                        "value": 75
                    },
                    "computePlatform": "Server"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigId", notNullValue());
    }

    @Test
    @Order(13)
    void getCustomDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "MyCustomConfig"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("deploymentConfigInfo.deploymentConfigName", equalTo("MyCustomConfig"))
            .body("deploymentConfigInfo.computePlatform", equalTo("Server"))
            .body("deploymentConfigInfo.minimumHealthyHosts.type", equalTo("FLEET_PERCENT"))
            .body("deploymentConfigInfo.minimumHealthyHosts.value", equalTo(75));
    }

    @Test
    @Order(14)
    void cannotCreateDeploymentConfigWithBuiltInPrefix() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "deploymentConfigName": "CodeDeployDefault.MyCustom",
                    "minimumHealthyHosts": {"type": "FLEET_PERCENT", "value": 50}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidDeploymentConfigNameException"));
    }

    @Test
    @Order(15)
    void tagAndUntagResource() {
        String appArn = "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app";

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app",
                    "Tags": [
                        {"Key": "env", "Value": "test"},
                        {"Key": "team", "Value": "platform"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.Key", hasItems("env", "team"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.UntagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app",
                    "TagKeys": ["team"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "arn:aws:codedeploy:us-east-1:000000000000:application:my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));
    }

    @Test
    @Order(16)
    void deleteDeploymentGroupAndApplication() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "my-lambda-app",
                    "deploymentGroupName": "my-lambda-dg"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"applicationName": "my-lambda-app"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(17)
    void deleteCustomDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "MyCustomConfig"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(18)
    void cannotDeleteBuiltInDeploymentConfig() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentConfig")
            .contentType(CONTENT_TYPE)
            .body("""
                {"deploymentConfigName": "CodeDeployDefault.AllAtOnce"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidDeploymentConfigNameException"));
    }

    @Test
    @Order(19)
    void listDeploymentGroupsRequiresApplicationName() {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListDeploymentGroups")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ApplicationNameRequiredException"));
    }
}
