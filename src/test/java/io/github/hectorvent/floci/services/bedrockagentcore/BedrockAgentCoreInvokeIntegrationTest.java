package io.github.hectorvent.floci.services.bedrockagentcore;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class BedrockAgentCoreInvokeIntegrationTest {

    private static final String ARN =
            "arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/abcdef12-3456-7890-abcd-ef1234567890:1";
    private static final String SESSION_ID = "session-0000000000000000000000000000abc";
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    @Test
    void invokeReturnsCannedBodyAndEchoesSession() {
        given()
                .header(SESSION_HEADER, SESSION_ID)
                .contentType("application/json")
                .body("{\"prompt\":\"anything\"}")
                .when().post("/runtimes/" + ARN + "/invocations")
                .then().statusCode(200)
                .header(SESSION_HEADER, SESSION_ID)
                .body("output", equalTo("yes"));
    }

    @Test
    void invokeAcceptsLargeUnparsedPayload() {
        byte[] big = new byte[1_000_000];
        java.util.Arrays.fill(big, (byte) 'x');
        given()
                .contentType("application/octet-stream")
                .body(big)
                .when().post("/runtimes/" + ARN + "/invocations")
                .then().statusCode(200)
                .body("output", equalTo("yes"));
    }
}
