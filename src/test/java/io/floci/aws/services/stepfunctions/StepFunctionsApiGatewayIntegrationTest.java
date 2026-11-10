package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normal HTTP listener, real IAM storage/policies and actual execute-api SigV4 authorization. */
@QuarkusTest
class StepFunctionsApiGatewayIntegrationTest {
    @Inject
    IamService iam;
    @Inject
    EmulatorConfig config;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void contentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void roleAuthorizedPostRespectsConfiguredIamEnforcement() throws Exception {
        String name = "sfn-api-" + UUID.randomUUID();
        String api = null;
        String machine = null;
        boolean policy = false;
        IamRole taskRole = null;
        boolean taskPolicy = false;
        boolean assumePolicy = false;
        IamRole role = iam.createRole(name, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 3600, Map.of());
        try {
            api = given().contentType(ContentType.JSON).body(Map.of("name", name)).post("/restapis")
                    .then().statusCode(201).extract().path("id");
            String root = given().get("/restapis/" + api + "/resources")
                    .then().statusCode(200).extract().path("item[0].id");
            String resource = given().contentType(ContentType.JSON).body(Map.of("pathPart", "invoke"))
                    .post("/restapis/" + api + "/resources/" + root)
                    .then().statusCode(201).extract().path("id");
            String method = "/restapis/" + api + "/resources/" + resource + "/methods/POST";
            given().contentType(ContentType.JSON).body(Map.of("authorizationType", "AWS_IAM"))
                    .put(method).then().statusCode(201);
            given().contentType(ContentType.JSON).body(Map.of("responseParameters", Map.of()))
                    .put(method + "/responses/200").then().statusCode(201);
            given().contentType(ContentType.JSON).body(Map.of("type", "MOCK", "requestTemplates",
                    Map.of("application/json", "{\"statusCode\":200}")))
                    .put(method + "/integration").then().statusCode(201);
            given().contentType(ContentType.JSON).body(Map.of("selectionPattern", "", "responseTemplates",
                    Map.of("application/json", "{\"owned\":true}")))
                    .put(method + "/integration/responses/200").then().statusCode(201);
            String deployment = given().contentType(ContentType.JSON).body(Map.of("description", "owned"))
                    .post("/restapis/" + api + "/deployments").then().statusCode(201).extract().path("id");
            given().contentType(ContentType.JSON).body(Map.of("stageName", "gamma", "deploymentId", deployment))
                    .post("/restapis/" + api + "/stages").then().statusCode(201);
            iam.putRolePolicy(name, "invoke", MAPPER.writeValueAsString(Map.of("Version", "2012-10-17",
                    "Statement", List.of(Map.of("Effect", "Allow", "Action", "execute-api:Invoke", "Resource",
                            "arn:aws:execute-api:us-east-1:000000000000:" + api + "/gamma/POST/invoke")))));
            policy = true;
            String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Invoke", "States", Map.of("Invoke",
                    Map.of("Type", "Task", "Resource", "arn:aws:states:::apigateway:invoke", "End", true,
                            "Parameters", Map.of("ApiEndpoint", api + ".execute-api.us-east-1.amazonaws.com",
                                    "Stage", "gamma", "Path", "invoke", "Method", "POST", "AuthType", "IAM_ROLE",
                                    "RequestBody", Map.of("owned", true))))));
            machine = sfn("CreateStateMachine", Map.of("name", name, "roleArn", role.getArn(), "definition", definition))
                    .then().statusCode(200).extract().path("stateMachineArn");
            Response success = execute(machine);
            assertEquals("SUCCEEDED", success.jsonPath().getString("status"), success.asString());
            assertTrue(MAPPER.readTree(success.jsonPath().getString("output")).path("ResponseBody").path("owned").asBoolean());
            iam.deleteRolePolicy(name, "invoke");
            policy = false;
            Response stillAllowed = execute(machine);
            assertPolicyResult(stillAllowed);
            String taskName = name + "-task";
            taskRole = iam.createRole(taskName, "/", MAPPER.writeValueAsString(Map.of("Statement", List.of(Map.of(
                    "Effect", "Allow", "Principal", Map.of("AWS", role.getArn()), "Action", "sts:AssumeRole")))),
                    null, 3600, Map.of());
            iam.putRolePolicy(name, "assume", MAPPER.writeValueAsString(Map.of("Statement", List.of(Map.of(
                    "Effect", "Allow", "Action", "sts:AssumeRole", "Resource", taskRole.getArn())))));
            assumePolicy = true;
            iam.putRolePolicy(taskName, "invoke", MAPPER.writeValueAsString(Map.of("Statement", List.of(Map.of(
                    "Effect", "Allow", "Action", "execute-api:Invoke", "Resource",
                    "arn:aws:execute-api:us-east-1:000000000000:" + api + "/gamma/POST/invoke")))));
            taskPolicy = true;
            ObjectNode taskDefinition = (ObjectNode) MAPPER.readTree(definition);
            ((ObjectNode) taskDefinition.path("States").path("Invoke")).putObject("Credentials")
                    .put("RoleArn", taskRole.getArn());
            sfn("UpdateStateMachine", Map.of("stateMachineArn", machine, "definition", taskDefinition.toString()))
                    .then().statusCode(200);
            Response selectedRole = execute(machine);
            assertEquals("SUCCEEDED", selectedRole.jsonPath().getString("status"), selectedRole.asString());
            iam.deleteRolePolicy(taskName, "invoke");
            taskPolicy = false;
            assertPolicyResult(execute(machine));
            sfn("UpdateStateMachine", Map.of("stateMachineArn", machine, "definition", definition))
                    .then().statusCode(200);
            iam.deleteRolePolicy(name, "assume");
            assumePolicy = false;
            iam.deleteRole(name);
            Response absentRole = execute(machine);
            assertPolicyResult(absentRole);
        } finally {
            if (machine != null) {
                sfn("DeleteStateMachine", Map.of("stateMachineArn", machine)).then().statusCode(200);
            }
            if (api != null) {
                given().delete("/restapis/" + api).then().statusCode(202);
            }
            if (taskRole != null) {
                if (taskPolicy) {
                    iam.deleteRolePolicy(taskRole.getRoleName(), "invoke");
                }
                iam.deleteRole(taskRole.getRoleName());
            }
            if (assumePolicy) {
                iam.deleteRolePolicy(name, "assume");
            }
            if (policy) {
                iam.deleteRolePolicy(name, "invoke");
            }
            if (iam.findRole("000000000000", name).isPresent()) {
                iam.deleteRole(name);
            }
        }
    }

    private void assertPolicyResult(Response response) {
        if (config.services().iam().enforcementEnabled()) {
            assertEquals("FAILED", response.jsonPath().getString("status"), response.asString());
            assertEquals("ApiGateway.403", response.jsonPath().getString("error"));
        } else {
            assertEquals("SUCCEEDED", response.jsonPath().getString("status"), response.asString());
        }
    }

    private static Response execute(String machine) throws Exception {
        String arn = sfn("StartExecution", Map.of("stateMachineArn", machine, "input", "{}"))
                .then().statusCode(200).extract().path("executionArn");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            Response response = sfn("DescribeExecution", Map.of("executionArn", arn));
            response.then().statusCode(200);
            if (!"RUNNING".equals(response.jsonPath().getString("status"))) {
                return response;
            }
            assertTrue(System.nanoTime() < deadline, "API workflow did not terminate");
            Thread.sleep(25);
        }
    }

    private static Response sfn(String action, Map<String, String> body) throws Exception {
        return given().filter(AwsRequestSigner.signedAs("000000000000", "test", "states"))
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AWSStepFunctions." + action)
                .body(MAPPER.writeValueAsString(body)).post("/");
    }
}
