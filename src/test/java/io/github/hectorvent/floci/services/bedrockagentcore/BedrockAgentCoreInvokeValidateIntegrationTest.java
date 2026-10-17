package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

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

    @Test
    void invokeKnownRuntimeSucceeds() {
        String arn = given().contentType("application/json").body(CREATE)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .extract().path("agentRuntimeArn");

        given().contentType("application/json").body("{}")
                .when().post("/runtimes/" + arn + "/invocations")
                .then().statusCode(200)
                .body("output", equalTo("yes"));
    }

    @Test
    void invokeUnknownRuntimeReturns404() {
        String unknown =
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/00000000-0000-0000-0000-000000000000:1";
        given().contentType("application/json").body("{}")
                .when().post("/runtimes/" + unknown + "/invocations")
                .then().statusCode(404);
    }
}
