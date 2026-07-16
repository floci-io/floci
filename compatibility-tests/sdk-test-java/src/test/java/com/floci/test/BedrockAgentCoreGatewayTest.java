package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore Gateway")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreGatewayTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/gw";

    private static BedrockAgentCoreControlClient client;
    private static String gatewayName;
    private static String gatewayId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        gatewayName = "gw" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            try {
                client.deleteGateway(DeleteGatewayRequest.builder().gatewayIdentifier(gatewayId).build());
            } catch (Exception ignored) {
            }
            client.close();
        }
    }

    @Test
    @Order(1)
    void createGateway() {
        CreateGatewayResponse response = client.createGateway(CreateGatewayRequest.builder()
                .name(gatewayName)
                .protocolType(GatewayProtocolType.MCP)
                .authorizerType(AuthorizerType.AWS_IAM)
                .roleArn(ROLE_ARN)
                .build());
        gatewayId = response.gatewayId();
        assertThat(gatewayId).isNotBlank();
        assertThat(response.gatewayArn()).contains(":gateway/");
        assertThat(response.gatewayUrl()).contains(gatewayId);
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(2)
    void getGateway() {
        GetGatewayResponse response = client.getGateway(GetGatewayRequest.builder()
                .gatewayIdentifier(gatewayId).build());
        assertThat(response.name()).isEqualTo(gatewayName);
        assertThat(response.protocolTypeAsString()).isEqualTo("MCP");
    }

    @Test
    @Order(3)
    void listGateways() {
        ListGatewaysResponse response = client.listGateways(ListGatewaysRequest.builder().build());
        assertThat(response.items()).extracting(GatewaySummary::gatewayId).contains(gatewayId);
    }

    @Test
    @Order(4)
    void deleteGateway() {
        DeleteGatewayResponse response = client.deleteGateway(DeleteGatewayRequest.builder()
                .gatewayIdentifier(gatewayId).build());
        assertThat(response.statusAsString()).isEqualTo("DELETING");

        assertThatThrownBy(() -> client.getGateway(GetGatewayRequest.builder()
                .gatewayIdentifier(gatewayId).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
