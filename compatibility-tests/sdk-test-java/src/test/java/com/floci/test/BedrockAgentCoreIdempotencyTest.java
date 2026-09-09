package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore clientToken idempotency")
class BedrockAgentCoreIdempotencyTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/agent-runtime";

    private static CreateAgentRuntimeRequest req(String name, String token) {
        return CreateAgentRuntimeRequest.builder()
                .agentRuntimeName(name)
                .agentRuntimeArtifact(AgentRuntimeArtifact.builder()
                        .containerConfiguration(ContainerConfiguration.builder()
                                .containerUri("public.ecr.aws/x/agent:latest").build())
                        .build())
                .networkConfiguration(NetworkConfiguration.builder().networkMode(NetworkMode.PUBLIC).build())
                .roleArn(ROLE_ARN)
                .clientToken(token)
                .build();
    }

    @Test
    void createRuntimeWithSameClientTokenReturnsSameRuntime() {
        BedrockAgentCoreControlClient client = TestFixtures.bedrockAgentCoreControlClient();
        String name = "idem" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // clientToken must be >= 33 chars of the allowed alphabet.
        String token = "idemtoken" + UUID.randomUUID().toString().replace("-", "");
        String id1 = null;
        try {
            id1 = client.createAgentRuntime(req(name, token)).agentRuntimeId();
            String id2 = client.createAgentRuntime(req(name, token)).agentRuntimeId();
            assertThat(id2).isEqualTo(id1);
        } finally {
            if (id1 != null) {
                try {
                    client.deleteAgentRuntime(DeleteAgentRuntimeRequest.builder()
                            .agentRuntimeId(id1).clientToken(token).build());
                } catch (Exception ignored) {
                }
            }
            client.close();
        }
    }
}
