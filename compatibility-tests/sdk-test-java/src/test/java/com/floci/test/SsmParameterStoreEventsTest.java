package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DeleteParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SSM Parameter Store Change events")
class SsmParameterStoreEventsTest {

    @Test
    @DisplayName("PutParameter publishes a Parameter Store Change event to the default bus")
    void putParameterPublishesParameterStoreChangeEvent() throws Exception {
        String name = "/" + TestFixtures.uniqueName("sdk-ssm-events") + "/param";
        String rule = TestFixtures.uniqueName("ssm-events");
        try (SsmClient ssm = TestFixtures.ssmClient();
             EventBridgeClient eventBridge = TestFixtures.eventBridgeClient();
             SqsClient sqs = TestFixtures.sqsClient()) {
            String queueUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(rule).build()).queueUrl();
            String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build())
                    .attributes().get(QueueAttributeName.QUEUE_ARN);
            eventBridge.putRule(PutRuleRequest.builder().name(rule)
                    .eventPattern("{\"source\":[\"aws.ssm\"],\"detail\":{\"name\":[\"" + name + "\"]}}")
                    .build());
            eventBridge.putTargets(PutTargetsRequest.builder().rule(rule)
                    .targets(Target.builder().id("1").arn(queueArn).build()).build());
            try {
                ssm.putParameter(PutParameterRequest.builder()
                        .name(name).value("v").type(ParameterType.STRING).build());

                List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(5).build()).messages();
                assertThat(messages).hasSize(1);
                JsonNode event = new ObjectMapper().readTree(messages.get(0).body());
                assertThat(event.path("source").asText()).isEqualTo("aws.ssm");
                assertThat(event.path("detail-type").asText()).isEqualTo("Parameter Store Change");
                assertThat(event.path("detail").path("operation").asText()).isEqualTo("Create");
                assertThat(event.path("detail").path("name").asText()).isEqualTo(name);
                assertThat(event.path("detail").path("type").asText()).isEqualTo("String");
            } finally {
                eventBridge.removeTargets(RemoveTargetsRequest.builder().rule(rule).ids("1").build());
                eventBridge.deleteRule(DeleteRuleRequest.builder().name(rule).build());
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
                ssm.deleteParameter(DeleteParameterRequest.builder().name(name).build());
            }
        }
    }
}
