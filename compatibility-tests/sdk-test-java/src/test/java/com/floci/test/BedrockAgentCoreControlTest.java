package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore Control")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreControlTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/agent-runtime";

    private static BedrockAgentCoreControlClient client;
    private static String runtimeName;
    private static String runtimeId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        // AgentCore names must match [a-zA-Z][a-zA-Z0-9_]{0,47} — no hyphens.
        runtimeName = "itAgent" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            try {
                client.deleteAgentRuntime(DeleteAgentRuntimeRequest.builder()
                        .agentRuntimeId(runtimeId).build());
            } catch (Exception ignored) {
            }
            client.close();
        }
    }

    private static AgentRuntimeArtifact artifact(String uri) {
        return AgentRuntimeArtifact.builder()
                .containerConfiguration(ContainerConfiguration.builder().containerUri(uri).build())
                .build();
    }

    private static NetworkConfiguration network() {
        return NetworkConfiguration.builder().networkMode(NetworkMode.PUBLIC).build();
    }

    @Test
    @Order(1)
    void createAgentRuntime() {
        CreateAgentRuntimeResponse response = client.createAgentRuntime(CreateAgentRuntimeRequest.builder()
                .agentRuntimeName(runtimeName)
                .agentRuntimeArtifact(artifact("public.ecr.aws/x/agent:latest"))
                .networkConfiguration(network())
                .roleArn(ROLE_ARN)
                .description("v1")
                .build());

        runtimeId = response.agentRuntimeId();
        assertThat(runtimeId).startsWith(runtimeName + "-");
        assertThat(response.agentRuntimeVersion()).isEqualTo("1");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.agentRuntimeArn()).contains(":bedrock-agentcore:").contains(":agent/");
        assertThat(response.workloadIdentityDetails().workloadIdentityArn()).isNotBlank();
    }

    @Test
    @Order(2)
    void getAgentRuntime() {
        GetAgentRuntimeResponse response = client.getAgentRuntime(GetAgentRuntimeRequest.builder()
                .agentRuntimeId(runtimeId).build());

        assertThat(response.agentRuntimeName()).isEqualTo(runtimeName);
        assertThat(response.agentRuntimeVersion()).isEqualTo("1");
        assertThat(response.description()).isEqualTo("v1");
    }

    @Test
    @Order(3)
    void listAgentRuntimes() {
        ListAgentRuntimesResponse response = client.listAgentRuntimes(ListAgentRuntimesRequest.builder().build());
        assertThat(response.agentRuntimes())
                .anyMatch(r -> runtimeId.equals(r.agentRuntimeId()));
    }

    @Test
    @Order(4)
    void updateAgentRuntimeBumpsVersion() {
        UpdateAgentRuntimeResponse response = client.updateAgentRuntime(UpdateAgentRuntimeRequest.builder()
                .agentRuntimeId(runtimeId)
                .agentRuntimeArtifact(artifact("public.ecr.aws/x/agent:v2"))
                .networkConfiguration(network())
                .roleArn(ROLE_ARN)
                .description("v2")
                .build());

        assertThat(response.agentRuntimeVersion()).isEqualTo("2");
    }

    @Test
    @Order(5)
    void getSpecificVersionReturnsSnapshot() {
        GetAgentRuntimeResponse response = client.getAgentRuntime(GetAgentRuntimeRequest.builder()
                .agentRuntimeId(runtimeId)
                .agentRuntimeVersion("1")
                .build());

        assertThat(response.agentRuntimeVersion()).isEqualTo("1");
        assertThat(response.description()).isEqualTo("v1");
    }

    @Test
    @Order(6)
    void deleteAgentRuntime() {
        DeleteAgentRuntimeResponse response = client.deleteAgentRuntime(DeleteAgentRuntimeRequest.builder()
                .agentRuntimeId(runtimeId).build());
        assertThat(response.statusAsString()).isEqualTo("DELETING");

        assertThatThrownBy(() -> client.getAgentRuntime(GetAgentRuntimeRequest.builder()
                .agentRuntimeId(runtimeId).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(7)
    void createWithInvalidNameThrowsValidation() {
        assertThatThrownBy(() -> client.createAgentRuntime(CreateAgentRuntimeRequest.builder()
                .agentRuntimeName("1-invalid-name")
                .agentRuntimeArtifact(artifact("x:latest"))
                .networkConfiguration(network())
                .roleArn(ROLE_ARN)
                .build()))
                .isInstanceOf(ValidationException.class);
    }
}
