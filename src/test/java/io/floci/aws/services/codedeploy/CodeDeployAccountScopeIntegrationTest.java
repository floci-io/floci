package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A CodeDeploy deployment runs on its own thread after CreateDeployment returns, so it has to
 * act as the account that created it: a Lambda deployment of a non-default account shifts that
 * account's alias, and the deployment is visible only to that account.
 */
@QuarkusTest
class CodeDeployAccountScopeIntegrationTest {

    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String LAMBDA = "/2015-03-31";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void lambdaDeploymentOfANonDefaultAccountShiftsThatAccountsAlias() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String function = "cd-account-fn-" + suffix;
        String application = "cd-account-app-" + suffix;
        String group = "cd-account-dg-" + suffix;

        lambda().contentType("application/json")
                .body(OBJECT_MAPPER.createObjectNode().put("FunctionName", function).put("Runtime", "nodejs20.x")
                        .put("Role", "arn:aws:iam::" + ACCOUNT + ":role/lambda-role").put("Handler", "index.handler")
                        .toString())
                .when().post(LAMBDA + "/functions").then().statusCode(201);
        cleanup.register(() -> lambda().when().delete(LAMBDA + "/functions/" + function).then().statusCode(204));
        publishVersion(function, "1");
        lambda().contentType("application/json").body("{\"Description\":\"second\"}")
                .when().put(LAMBDA + "/functions/" + function + "/configuration").then().statusCode(200);
        publishVersion(function, "2");
        lambda().contentType("application/json").body("{\"Name\":\"live\",\"FunctionVersion\":\"1\"}")
                .when().post(LAMBDA + "/functions/" + function + "/aliases").then().statusCode(201);

        codeDeploy("CreateApplication", OBJECT_MAPPER.createObjectNode()
                .put("applicationName", application).put("computePlatform", "Lambda"));
        cleanup.register(() -> codeDeploy("DeleteApplication",
                OBJECT_MAPPER.createObjectNode().put("applicationName", application)));
        ObjectNode groupRequest = OBJECT_MAPPER.createObjectNode()
                .put("applicationName", application).put("deploymentGroupName", group)
                .put("deploymentConfigName", "CodeDeployDefault.LambdaAllAtOnce")
                .put("serviceRoleArn", "arn:aws:iam::" + ACCOUNT + ":role/codedeploy-role");
        groupRequest.putObject("deploymentStyle")
                .put("deploymentType", "BLUE_GREEN").put("deploymentOption", "WITH_TRAFFIC_CONTROL");
        codeDeploy("CreateDeploymentGroup", groupRequest);
        cleanup.register(() -> codeDeploy("DeleteDeploymentGroup", OBJECT_MAPPER.createObjectNode()
                .put("applicationName", application).put("deploymentGroupName", group)));

        ObjectNode appSpec = OBJECT_MAPPER.createObjectNode().put("version", 0.0);
        ArrayNode resources = appSpec.putArray("Resources");
        resources.addObject().putObject("fn").put("Type", "AWS::Lambda::Function").putObject("Properties")
                .put("Name", function).put("Alias", "live").put("CurrentVersion", "1").put("TargetVersion", "2");
        ObjectNode deploymentRequest = OBJECT_MAPPER.createObjectNode()
                .put("applicationName", application).put("deploymentGroupName", group);
        deploymentRequest.putObject("revision").put("revisionType", "AppSpecContent")
                .putObject("appSpecContent").put("content", appSpec.toString());
        String deploymentId = codeDeploy("CreateDeployment", deploymentRequest).jsonPath().getString("deploymentId");

        ObjectNode getDeployment = OBJECT_MAPPER.createObjectNode().put("deploymentId", deploymentId);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                codeDeploy("GetDeployment", getDeployment)
                        .then().body("deploymentInfo.status", equalTo("Succeeded")));
        lambda().when().get(LAMBDA + "/functions/" + function + "/aliases/live")
                .then().statusCode(200).body("FunctionVersion", equalTo("2"));
        given().header("X-Amz-Target", "CodeDeploy_20141006.GetDeployment")
                .contentType("application/x-amz-json-1.1").body(getDeployment.toString())
                .when().post("/")
                .then().statusCode(400).body("__type", containsString("DeploymentDoesNotExistException"));
    }

    private static void publishVersion(String function, String expected) {
        lambda().contentType("application/json").body("{}")
                .when().post(LAMBDA + "/functions/" + function + "/versions")
                .then().statusCode(201).body("Version", equalTo(expected));
    }

    private static RequestSpecification lambda() {
        return given().header("Authorization", auth(ACCOUNT, REGION, "lambda"));
    }

    private static Response codeDeploy(String operation, ObjectNode body) {
        return given().header("X-Amz-Target", "CodeDeploy_20141006." + operation)
                .header("Authorization", auth(ACCOUNT, REGION, "codedeploy"))
                .contentType("application/x-amz-json-1.1").body(body.toString())
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }
}
