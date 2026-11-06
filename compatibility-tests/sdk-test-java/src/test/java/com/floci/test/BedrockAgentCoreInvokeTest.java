package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeResponse;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore InvokeAgentRuntime (data plane)")
class BedrockAgentCoreInvokeTest {

    private static final String ARN =
            "arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/abcdef12-3456-7890-abcd-ef1234567890:1";
    private static final String SESSION_ID = "session-0000000000000000000000000000abc";

    @Test
    void invokeReturnsCannedBodyAndEchoesSession() {
        try (BedrockAgentCoreClient client = TestFixtures.bedrockAgentCoreClient()) {
            ResponseBytes<InvokeAgentRuntimeResponse> response = client.invokeAgentRuntimeAsBytes(
                    InvokeAgentRuntimeRequest.builder()
                            .agentRuntimeArn(ARN)
                            .runtimeSessionId(SESSION_ID)
                            .contentType("application/json")
                            .payload(SdkBytes.fromUtf8String("{\"prompt\":\"anything\"}"))
                            .build());

            assertThat(response.asUtf8String()).contains("yes");
            assertThat(response.response().runtimeSessionId()).isEqualTo(SESSION_ID);
        }
    }
}
