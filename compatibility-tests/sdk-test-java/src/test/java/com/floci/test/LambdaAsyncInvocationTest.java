package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateAliasRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DestinationConfig;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.OnFailure;
import software.amazon.awssdk.services.lambda.model.PublishVersionRequest;
import software.amazon.awssdk.services.lambda.model.PutFunctionEventInvokeConfigRequest;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Lambda asynchronous invocation")
class LambdaAsyncInvocationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final Duration RECEIVE_DEADLINE = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LambdaClient lambda;
    private static SqsClient sqs;
    private static String functionName;
    private static String queueUrl;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
        sqs = TestFixtures.sqsClient();
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            if (functionName != null) {
                try {
                    lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(functionName).build());
                } catch (Exception e) {
                    System.err.println("Best-effort Lambda async invocation test cleanup failed: " + e.getMessage());
                }
            }
            lambda.close();
        }
        if (sqs != null) {
            if (queueUrl != null) {
                try {
                    sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
                } catch (Exception e) {
                    System.err.println("Best-effort SQS async invocation test cleanup failed: " + e.getMessage());
                }
            }
            sqs.close();
        }
    }

    @Test
    @DisplayName("Invoke with Qualifier applies the alias's OnFailure destination")
    void invokeWithQualifierAppliesTheAliasOnFailureDestination() throws Exception {
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(TestFixtures.uniqueName("sdk-async-onfailure"))
                .build()).queueUrl();
        String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build()).attributes().get(QueueAttributeName.QUEUE_ARN);

        functionName = TestFixtures.uniqueName("sdk-async-fn");
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(functionName)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(10)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.failingZip()))
                        .build())
                .build());
        String version = lambda.publishVersion(PublishVersionRequest.builder()
                .functionName(functionName)
                .build()).version();
        lambda.createAlias(CreateAliasRequest.builder()
                .functionName(functionName)
                .name("live")
                .functionVersion(version)
                .build());
        lambda.putFunctionEventInvokeConfig(PutFunctionEventInvokeConfigRequest.builder()
                .functionName(functionName)
                .qualifier("live")
                .maximumRetryAttempts(0)
                .destinationConfig(DestinationConfig.builder()
                        .onFailure(OnFailure.builder().destination(queueArn).build())
                        .build())
                .build());

        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .qualifier("live")
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String("{\"probe\":\"sdk\"}"))
                .build());
        assertThat(response.statusCode()).isEqualTo(202);

        JsonNode record = MAPPER.readTree(awaitMessage().body());
        assertThat(record.path("requestContext").path("approximateInvokeCount").asInt()).isEqualTo(1);
        assertThat(record.path("requestContext").path("condition").asText()).isEqualTo("RetriesExhausted");
        assertThat(record.path("requestPayload").path("probe").asText()).isEqualTo("sdk");
        assertThat(record.path("responseContext").path("executedVersion").asText()).isEqualTo(version);
    }

    private static Message awaitMessage() {
        Instant deadline = Instant.now().plus(RECEIVE_DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .waitTimeSeconds(20)
                    .maxNumberOfMessages(1)
                    .build()).messages();
            if (!messages.isEmpty()) {
                return messages.get(0);
            }
        }
        throw new AssertionError("no OnFailure record arrived within " + RECEIVE_DEADLINE.toSeconds() + " s");
    }
}
