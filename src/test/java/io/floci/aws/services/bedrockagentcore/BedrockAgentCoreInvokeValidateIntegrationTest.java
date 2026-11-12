package io.floci.aws.services.bedrockagentcore;

import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the opt-in existence check (issue #11). With
 * {@code validate-runtime-exists=true}, invoking an unknown runtime ARN returns 404
 * while invoking a real runtime's ARN still returns the canned body.
 *
 * <p>The setting comes from {@link IndependentServiceSettingsProfile}, which this class shares
 * with other classes that each call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class BedrockAgentCoreInvokeValidateIntegrationTest {

    private static final String CREATE = """
            {
              "agentRuntimeName": "invAgent",
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "roleArn": "arn:aws:iam::000000000000:role/agent"
            }""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void invokeKnownRuntimeSucceeds() {
        JsonPath runtime = given().contentType("application/json").body(CREATE)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .extract().jsonPath();
        String runtimeId = runtime.getString("agentRuntimeId");
        cleanup.register(() -> given().when().delete("/runtimes/" + runtimeId + "/").then().statusCode(202));

        given().contentType("application/json").body("{}")
                .when().post("/runtimes/" + runtime.getString("agentRuntimeArn") + "/invocations")
                .then().statusCode(200)
                .body("output", equalTo("yes"));
    }

    @Test
    void invokeUnknownRuntimeReturns404() {
        String unknown =
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/missing-AbCdEfGhIj";
        given().contentType("application/json").body("{}")
                .when().post("/runtimes/" + unknown + "/invocations")
                .then().statusCode(404);
    }

    @Test
    void invokeUnknownLegacyRuntimeReturns404() {
        given().contentType("application/json").body("{}")
                .post("/runtimes/arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/unknown:1/invocations")
                .then().statusCode(404);
    }

    @Test
    void harnessRuntimeExistsOnlyWhileItsHarnessIsRegistered() {
        String name = "invoke" + UUID.randomUUID().toString().replace("-", "");
        JsonPath harness = given().contentType("application/json").body("""
                {"harnessName":"%s","executionRoleArn":"arn:aws:iam::000000000000:role/harness",
                 "model":{"bedrockModelConfig":{"modelId":"test-model"}}}
                """.formatted(name)).post("/harnesses").then().statusCode(201).extract().jsonPath();
        String id = harness.getString("harness.harnessId");
        String arn = harness.getString("harness.environment.agentCoreRuntimeEnvironment.agentRuntimeArn");
        cleanup.register(() -> given().delete("/harnesses/" + id));
        given().contentType("application/json").body("{}").post("/runtimes/" + arn + "/invocations")
                .then().statusCode(200);
        given().delete("/harnesses/" + id).then().statusCode(200);
        given().contentType("application/json").body("{}").post("/runtimes/" + arn + "/invocations")
                .then().statusCode(404);
    }

    @Test
    void anInventedHarnessRuntimeDoesNotPassValidation() {
        given().contentType("application/json").body("{}")
                .post("/runtimes/arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/harness_missing-AbCdEfGhIj/invocations")
                .then().statusCode(404);
    }
}
