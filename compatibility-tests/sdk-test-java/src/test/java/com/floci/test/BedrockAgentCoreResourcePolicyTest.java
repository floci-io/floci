package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteResourcePolicyRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetResourcePolicyRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.PutResourcePolicyRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bedrock AgentCore resource policy")
class BedrockAgentCoreResourcePolicyTest {

    private static final String RESOURCE_ARN =
            "arn:aws:bedrock-agentcore:us-east-1:000000000000:gateway/example-1234567890";

    @Test
    void getMissingResourcePolicy() {
        try (BedrockAgentCoreControlClient client = TestFixtures.bedrockAgentCoreControlClient()) {
            assertThatThrownBy(() -> client.getResourcePolicy(GetResourcePolicyRequest.builder()
                    .resourceArn(RESOURCE_ARN)
                    .build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void putAndGetResourcePolicy() {
        try (BedrockAgentCoreControlClient client = TestFixtures.bedrockAgentCoreControlClient()) {
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
            var put = client.putResourcePolicy(PutResourcePolicyRequest.builder()
                    .resourceArn(RESOURCE_ARN)
                    .policy(policy)
                    .build());
            assertThat(put.policy()).isEqualTo(policy);

            var get = client.getResourcePolicy(GetResourcePolicyRequest.builder()
                    .resourceArn(RESOURCE_ARN)
                    .build());
            assertThat(get.policy()).isEqualTo(policy);
        }
    }

    @Test
    void deleteResourcePolicy() {
        try (BedrockAgentCoreControlClient client = TestFixtures.bedrockAgentCoreControlClient()) {
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
            client.putResourcePolicy(PutResourcePolicyRequest.builder()
                    .resourceArn(RESOURCE_ARN)
                    .policy(policy)
                    .build());

            var deleted = client.deleteResourcePolicy(DeleteResourcePolicyRequest.builder()
                    .resourceArn(RESOURCE_ARN)
                    .build());
            assertThat(deleted.sdkHttpResponse().statusCode()).isEqualTo(204);

            assertThatThrownBy(() -> client.getResourcePolicy(GetResourcePolicyRequest.builder()
                    .resourceArn(RESOURCE_ARN)
                    .build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
