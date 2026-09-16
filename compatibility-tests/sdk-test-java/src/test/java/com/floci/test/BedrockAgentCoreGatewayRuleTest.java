package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bedrock AgentCore gateway rules")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreGatewayRuleTest {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreGatewayRuleTest.class);
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/gw";
    private static BedrockAgentCoreControlClient client;
    private static String gatewayId;
    private static String ruleId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        String name = "gw" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        gatewayId = client.createGateway(CreateGatewayRequest.builder()
                .name(name)
                .protocolType(GatewayProtocolType.MCP)
                .authorizerType(AuthorizerType.AWS_IAM)
                .roleArn(ROLE_ARN)
                .build()).gatewayId();
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            try {
                client.deleteGateway(DeleteGatewayRequest.builder().gatewayIdentifier(gatewayId).build());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to delete AgentCore gateway %s during test cleanup", gatewayId);
            }
            client.close();
        }
    }

    @Test
    @Order(1)
    void createGatewayRule() {
        Action action = Action.fromRouteToTarget(RouteToTargetAction.fromStaticRoute(
                StaticRoute.builder().targetName("target").build()));
        CreateGatewayRuleResponse response = client.createGatewayRule(CreateGatewayRuleRequest.builder()
                .gatewayIdentifier(gatewayId)
                .priority(1)
                .actions(action)
                .description("route")
                .build());

        ruleId = response.ruleId();
        assertThat(ruleId).matches("[0-9a-f-]{36}");
        assertThat(response.gatewayArn()).contains(":gateway/");
        assertThat(response.statusAsString()).isEqualTo("ACTIVE");
        assertThat(response.priority()).isEqualTo(1);
        assertThat(response.actions()).hasSize(1);
    }

    @Test
    @Order(2)
    void getGatewayRule() {
        GetGatewayRuleResponse response = client.getGatewayRule(GetGatewayRuleRequest.builder()
                .gatewayIdentifier(gatewayId)
                .ruleId(ruleId)
                .build());

        assertThat(response.ruleId()).isEqualTo(ruleId);
        assertThat(response.priority()).isEqualTo(1);
        assertThat(response.description()).isEqualTo("route");
        assertThat(response.statusAsString()).isEqualTo("ACTIVE");
        assertThat(response.actions()).hasSize(1);
    }

    @Test
    @Order(3)
    void listGatewayRules() {
        ListGatewayRulesResponse response = client.listGatewayRules(ListGatewayRulesRequest.builder()
                .gatewayIdentifier(gatewayId)
                .maxResults(100)
                .build());

        assertThat(response.gatewayRules())
                .anyMatch(rule -> ruleId.equals(rule.ruleId()));
    }

    @Test
    @Order(4)
    void updateGatewayRule() {
        Action action = Action.fromRouteToTarget(RouteToTargetAction.fromStaticRoute(
                StaticRoute.builder().targetName("updated-target").build()));
        UpdateGatewayRuleResponse response = client.updateGatewayRule(UpdateGatewayRuleRequest.builder()
                .gatewayIdentifier(gatewayId)
                .ruleId(ruleId)
                .priority(2)
                .actions(action)
                .description("updated route")
                .build());

        assertThat(response.ruleId()).isEqualTo(ruleId);
        assertThat(response.priority()).isEqualTo(2);
        assertThat(response.description()).isEqualTo("updated route");
        assertThat(response.actions()).hasSize(1);
        assertThat(response.statusAsString()).isEqualTo("ACTIVE");
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    @Order(5)
    void createGatewayRuleRejectsInvalidNestedAction() {
        Action invalidAction = Action.fromRouteToTarget(RouteToTargetAction.fromStaticRoute(
                StaticRoute.builder().build()));

        assertThatThrownBy(() -> client.createGatewayRule(CreateGatewayRuleRequest.builder()
                .gatewayIdentifier(gatewayId)
                .priority(3)
                .actions(invalidAction)
                .build()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @Order(6)
    void deleteGatewayRule() {
        DeleteGatewayRuleResponse response = client.deleteGatewayRule(DeleteGatewayRuleRequest.builder()
                .gatewayIdentifier(gatewayId)
                .ruleId(ruleId)
                .build());

        assertThat(response.ruleId()).isEqualTo(ruleId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
    }
}
