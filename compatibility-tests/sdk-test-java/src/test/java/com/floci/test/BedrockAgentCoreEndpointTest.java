package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore Runtime Endpoints")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreEndpointTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/agent-runtime";

    private static BedrockAgentCoreControlClient client;
    private static String runtimeId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        String name = "epAgent" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        runtimeId = client.createAgentRuntime(CreateAgentRuntimeRequest.builder()
                .agentRuntimeName(name)
                .agentRuntimeArtifact(AgentRuntimeArtifact.builder()
                        .containerConfiguration(ContainerConfiguration.builder()
                                .containerUri("public.ecr.aws/x/agent:latest").build())
                        .build())
                .networkConfiguration(NetworkConfiguration.builder().networkMode(NetworkMode.PUBLIC).build())
                .roleArn(ROLE_ARN)
                .build()).agentRuntimeId();
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

    @Test
    @Order(1)
    void defaultEndpointExists() {
        ListAgentRuntimeEndpointsResponse response = client.listAgentRuntimeEndpoints(
                ListAgentRuntimeEndpointsRequest.builder().agentRuntimeId(runtimeId).build());
        assertThat(response.runtimeEndpoints())
                .anyMatch(e -> "DEFAULT".equals(e.name()));
    }

    @Test
    @Order(2)
    void createEndpoint() {
        CreateAgentRuntimeEndpointResponse response = client.createAgentRuntimeEndpoint(
                CreateAgentRuntimeEndpointRequest.builder()
                        .agentRuntimeId(runtimeId)
                        .name("prod")
                        .agentRuntimeVersion("1")
                        .description("prod endpoint")
                        .build());
        assertThat(response.endpointName()).isEqualTo("prod");
        assertThat(response.targetVersion()).isEqualTo("1");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.agentRuntimeEndpointArn()).contains(":agentEndpoint/");
    }

    @Test
    @Order(3)
    void getEndpoint() {
        GetAgentRuntimeEndpointResponse response = client.getAgentRuntimeEndpoint(
                GetAgentRuntimeEndpointRequest.builder()
                        .agentRuntimeId(runtimeId).endpointName("prod").build());
        assertThat(response.name()).isEqualTo("prod");
        assertThat(response.targetVersion()).isEqualTo("1");
        assertThat(response.description()).isEqualTo("prod endpoint");
    }

    @Test
    @Order(4)
    void listEndpoints() {
        ListAgentRuntimeEndpointsResponse response = client.listAgentRuntimeEndpoints(
                ListAgentRuntimeEndpointsRequest.builder().agentRuntimeId(runtimeId).build());
        assertThat(response.runtimeEndpoints()).extracting(AgentRuntimeEndpoint::name)
                .contains("DEFAULT", "prod");
    }

    @Test
    @Order(5)
    void updateEndpoint() {
        client.updateAgentRuntimeEndpoint(UpdateAgentRuntimeEndpointRequest.builder()
                .agentRuntimeId(runtimeId).endpointName("prod").description("prod v2").build());

        GetAgentRuntimeEndpointResponse response = client.getAgentRuntimeEndpoint(
                GetAgentRuntimeEndpointRequest.builder()
                        .agentRuntimeId(runtimeId).endpointName("prod").build());
        assertThat(response.description()).isEqualTo("prod v2");
    }

    @Test
    @Order(6)
    void deleteEndpoint() {
        DeleteAgentRuntimeEndpointResponse response = client.deleteAgentRuntimeEndpoint(
                DeleteAgentRuntimeEndpointRequest.builder()
                        .agentRuntimeId(runtimeId).endpointName("prod").build());
        assertThat(response.statusAsString()).isEqualTo("DELETING");

        assertThatThrownBy(() -> client.getAgentRuntimeEndpoint(GetAgentRuntimeEndpointRequest.builder()
                .agentRuntimeId(runtimeId).endpointName("prod").build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
