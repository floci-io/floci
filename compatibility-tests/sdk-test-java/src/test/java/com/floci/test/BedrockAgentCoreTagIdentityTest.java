package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore Tagging + Workload Identity")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreTagIdentityTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/agent-runtime";

    private static BedrockAgentCoreControlClient client;
    private static String runtimeArn;
    private static String identityName;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        String name = "tagAgent" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        runtimeArn = client.createAgentRuntime(CreateAgentRuntimeRequest.builder()
                .agentRuntimeName(name)
                .agentRuntimeArtifact(AgentRuntimeArtifact.builder()
                        .containerConfiguration(ContainerConfiguration.builder()
                                .containerUri("public.ecr.aws/x/agent:latest").build())
                        .build())
                .networkConfiguration(NetworkConfiguration.builder().networkMode(NetworkMode.PUBLIC).build())
                .roleArn(ROLE_ARN)
                .build()).agentRuntimeArn();
        identityName = "wid" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void tagRoundTrip() {
        client.tagResource(TagResourceRequest.builder()
                .resourceArn(runtimeArn).tags(Map.of("env", "prod", "team", "core")).build());

        Map<String, String> tags = client.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(runtimeArn).build()).tags();
        assertThat(tags).containsEntry("env", "prod").containsEntry("team", "core");

        client.untagResource(UntagResourceRequest.builder()
                .resourceArn(runtimeArn).tagKeys(List.of("env")).build());

        Map<String, String> after = client.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(runtimeArn).build()).tags();
        assertThat(after).doesNotContainKey("env").containsEntry("team", "core");
    }

    @Test
    @Order(3)
    void gatewayTagRoundTripViaSdk() {
        String gwName = "gwtag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String gatewayArn = client.createGateway(CreateGatewayRequest.builder()
                .name(gwName)
                .protocolType(GatewayProtocolType.MCP)
                .authorizerType(AuthorizerType.AWS_IAM)
                .roleArn("arn:aws:iam::000000000000:role/gw")
                .build()).gatewayArn();
        try {
            client.tagResource(TagResourceRequest.builder()
                    .resourceArn(gatewayArn).tags(Map.of("env", "prod")).build());
            assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder()
                    .resourceArn(gatewayArn).build()).tags()).containsEntry("env", "prod");
            client.untagResource(UntagResourceRequest.builder()
                    .resourceArn(gatewayArn).tagKeys(List.of("env")).build());
            assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder()
                    .resourceArn(gatewayArn).build()).tags()).doesNotContainKey("env");
        } finally {
            // gatewayIdentifier is the id form; derive it from the ARN's last segment.
            String gatewayId = gatewayArn.substring(gatewayArn.lastIndexOf('/') + 1);
            try {
                client.deleteGateway(DeleteGatewayRequest.builder().gatewayIdentifier(gatewayId).build());
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @Order(4)
    void memoryTagRoundTripViaSdk() {
        String memName = "memtag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        CreateMemoryResponse created = client.createMemory(CreateMemoryRequest.builder()
                .name(memName)
                .eventExpiryDuration(30)
                .tags(Map.of("team", "core"))
                .build());
        String memoryArn = created.memory().arn();
        try {
            // tags provided at create time surface through ListTagsForResource only
            assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder()
                    .resourceArn(memoryArn).build()).tags()).containsEntry("team", "core");

            client.tagResource(TagResourceRequest.builder()
                    .resourceArn(memoryArn).tags(Map.of("env", "prod")).build());
            assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder()
                    .resourceArn(memoryArn).build()).tags())
                    .containsEntry("env", "prod").containsEntry("team", "core");

            client.untagResource(UntagResourceRequest.builder()
                    .resourceArn(memoryArn).tagKeys(List.of("env")).build());
            assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder()
                    .resourceArn(memoryArn).build()).tags())
                    .doesNotContainKey("env").containsEntry("team", "core");
        } finally {
            try {
                client.deleteMemory(DeleteMemoryRequest.builder()
                        .memoryId(created.memory().id()).build());
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void nonDefaultAccountMemoryTagsRespectIdentity() {
        try (BedrockAgentCoreControlClient accountClient =
                     TestFixtures.bedrockAgentCoreControlClient("111111111111")) {
            String name = "memtag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            CreateMemoryResponse created = accountClient.createMemory(CreateMemoryRequest.builder()
                    .name(name).eventExpiryDuration(30).build());
            String arn = created.memory().arn();
            try {
                assertThat(arn).contains(":111111111111:");
                accountClient.tagResource(TagResourceRequest.builder()
                        .resourceArn(arn).tags(Map.of("env", "dev")).build());
                assertThat(accountClient.listTagsForResource(ListTagsForResourceRequest.builder()
                        .resourceArn(arn).build()).tags()).containsEntry("env", "dev");
                accountClient.untagResource(UntagResourceRequest.builder()
                        .resourceArn(arn).tagKeys(List.of("env")).build());
                assertThat(accountClient.listTagsForResource(ListTagsForResourceRequest.builder()
                        .resourceArn(arn).build()).tags()).doesNotContainKey("env");

                String[] parts = arn.split(":");
                String foreignAccount = arn.replace(":111111111111:", ":222222222222:");
                String otherRegion = parts[3].equals("eu-west-3") ? "us-east-1" : "eu-west-3";
                String foreignRegion = arn.replace(":" + parts[3] + ":", ":" + otherRegion + ":");
                for (String foreign : List.of(foreignAccount, foreignRegion)) {
                    assertThatThrownBy(() -> accountClient.listTagsForResource(ListTagsForResourceRequest.builder()
                            .resourceArn(foreign).build())).isInstanceOf(ResourceNotFoundException.class);
                    assertThatThrownBy(() -> accountClient.tagResource(TagResourceRequest.builder()
                            .resourceArn(foreign).tags(Map.of("env", "stolen")).build()))
                            .isInstanceOf(ResourceNotFoundException.class);
                    assertThatThrownBy(() -> accountClient.untagResource(UntagResourceRequest.builder()
                            .resourceArn(foreign).tagKeys(List.of("env")).build()))
                            .isInstanceOf(ResourceNotFoundException.class);
                }
            } finally {
                accountClient.deleteMemory(DeleteMemoryRequest.builder().memoryId(created.memory().id()).build());
            }
        }
    }

    @Test
    @Order(2)
    void workloadIdentityCrud() {
        CreateWorkloadIdentityResponse created = client.createWorkloadIdentity(
                CreateWorkloadIdentityRequest.builder().name(identityName).build());
        assertThat(created.name()).isEqualTo(identityName);
        assertThat(created.workloadIdentityArn()).contains(":bedrock-agentcore:");

        GetWorkloadIdentityResponse got = client.getWorkloadIdentity(
                GetWorkloadIdentityRequest.builder().name(identityName).build());
        assertThat(got.name()).isEqualTo(identityName);

        ListWorkloadIdentitiesResponse listed = client.listWorkloadIdentities(
                ListWorkloadIdentitiesRequest.builder().build());
        assertThat(listed.workloadIdentities()).extracting(WorkloadIdentityType::name)
                .contains(identityName);

        client.deleteWorkloadIdentity(DeleteWorkloadIdentityRequest.builder().name(identityName).build());
        assertThatThrownBy(() -> client.getWorkloadIdentity(
                GetWorkloadIdentityRequest.builder().name(identityName).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
