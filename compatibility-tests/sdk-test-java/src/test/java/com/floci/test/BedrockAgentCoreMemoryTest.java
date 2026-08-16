package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore Memory")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreMemoryTest {

    private static BedrockAgentCoreControlClient client;
    private static String memoryName;
    private static String memoryId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        memoryName = "mem" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            try {
                client.deleteMemory(DeleteMemoryRequest.builder().memoryId(memoryId).build());
            } catch (Exception ignored) {
            }
            client.close();
        }
    }

    @Test
    @Order(1)
    void createMemory() {
        CreateMemoryResponse response = client.createMemory(CreateMemoryRequest.builder()
                .name(memoryName)
                .eventExpiryDuration(30)
                .description("v1")
                .build());
        memoryId = response.memory().id();
        assertThat(memoryId).isNotBlank();
        assertThat(response.memory().name()).isEqualTo(memoryName);
        assertThat(response.memory().arn()).contains(":memory/");
        assertThat(response.memory().statusAsString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    void getMemory() {
        GetMemoryResponse response = client.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build());
        assertThat(response.memory().name()).isEqualTo(memoryName);
    }

    @Test
    @Order(3)
    void listMemories() {
        ListMemoriesResponse response = client.listMemories(ListMemoriesRequest.builder().build());
        assertThat(response.memories()).extracting(MemorySummary::id).contains(memoryId);
    }

    @Test
    @Order(4)
    void updateMemory() {
        UpdateMemoryResponse response = client.updateMemory(UpdateMemoryRequest.builder()
                .memoryId(memoryId).description("v2").build());
        assertThat(response.memory().id()).isEqualTo(memoryId);
    }

    @Test
    @Order(5)
    void deleteMemory() {
        DeleteMemoryResponse response = client.deleteMemory(DeleteMemoryRequest.builder()
                .memoryId(memoryId).build());
        assertThat(response.statusAsString()).isEqualTo("DELETING");

        assertThatThrownBy(() -> client.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
