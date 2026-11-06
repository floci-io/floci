package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda - ESM ScalingConfig")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaEsmScalingConfigTest {

    private static final String FUNCTION_NAME = "sdk-test-esm-scaling-fn";
    private static final String SQS_QUEUE_NAME = "sdk-test-esm-scaling-queue";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static LambdaClient lambda;
    private static SqsClient sqs;
    private static String queueArn;
    private static String queueUrl;
    private static final Set<String> createdEsmUuids = new HashSet<>();

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
        sqs = TestFixtures.sqsClient();

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION_NAME)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());

        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(SQS_QUEUE_NAME)
                .build())
                .queueUrl();
        queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            for (String uuid : createdEsmUuids) {
                try {
                    lambda.deleteEventSourceMapping(DeleteEventSourceMappingRequest.builder()
                            .uuid(uuid).build());
                } catch (Exception ignored) {}
            }
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(FUNCTION_NAME).build());
            } catch (Exception ignored) {}
            lambda.close();
        }
        if (sqs != null) {
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            } catch (Exception ignored) {}
            sqs.close();
        }
    }

    @Test
    @Order(1)
    void createEsm_withScalingConfig_roundTrips() {
        CreateEventSourceMappingResponse created = lambda.createEventSourceMapping(
                CreateEventSourceMappingRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .eventSourceArn(queueArn)
                        .batchSize(5)
                        .scalingConfig(ScalingConfig.builder().maximumConcurrency(7).build())
                        .build());
        createdEsmUuids.add(created.uuid());

        assertThat(created.scalingConfig()).isNotNull();
        assertThat(created.scalingConfig().maximumConcurrency()).isEqualTo(7);

        GetEventSourceMappingResponse fetched = lambda.getEventSourceMapping(
                GetEventSourceMappingRequest.builder().uuid(created.uuid()).build());
        assertThat(fetched.scalingConfig()).isNotNull();
        assertThat(fetched.scalingConfig().maximumConcurrency()).isEqualTo(7);
    }

    @Test
    @Order(2)
    void createEsm_withoutScalingConfig_doesNotExposeIt() {
        CreateEventSourceMappingResponse created = lambda.createEventSourceMapping(
                CreateEventSourceMappingRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .eventSourceArn(queueArn)
                        .batchSize(2)
                        .build());
        createdEsmUuids.add(created.uuid());

        // SDK models an omitted object as null (or an unset builder); both are acceptable
        assertThat(created.scalingConfig() == null
                || created.scalingConfig().maximumConcurrency() == null).isTrue();
    }

    @Test
    @Order(3)
    void createEsm_withMaximumConcurrencyBelowTwo_throwsInvalidParameter() {
        assertThatThrownBy(() -> lambda.createEventSourceMapping(
                CreateEventSourceMappingRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .eventSourceArn(queueArn)
                        .scalingConfig(ScalingConfig.builder().maximumConcurrency(1).build())
                        .build()))
                .isInstanceOf(InvalidParameterValueException.class);
    }

    @Test
    @Order(4)
    void createEsm_withMaximumConcurrencyAboveThousand_throwsInvalidParameter() {
        assertThatThrownBy(() -> lambda.createEventSourceMapping(
                CreateEventSourceMappingRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .eventSourceArn(queueArn)
                        .scalingConfig(ScalingConfig.builder().maximumConcurrency(1001).build())
                        .build()))
                .isInstanceOf(InvalidParameterValueException.class);
    }

    @Test
    @Order(5)
    void updateEsm_addsThenClearsScalingConfig() {
        CreateEventSourceMappingResponse created = lambda.createEventSourceMapping(
                CreateEventSourceMappingRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .eventSourceArn(queueArn)
                        .batchSize(4)
                        .build());
        createdEsmUuids.add(created.uuid());

        UpdateEventSourceMappingResponse added = lambda.updateEventSourceMapping(
                UpdateEventSourceMappingRequest.builder()
                        .uuid(created.uuid())
                        .scalingConfig(ScalingConfig.builder().maximumConcurrency(3).build())
                        .build());
        assertThat(added.scalingConfig()).isNotNull();
        assertThat(added.scalingConfig().maximumConcurrency()).isEqualTo(3);

        // Clear by sending an empty ScalingConfig — AWS treats this as "reset".
        UpdateEventSourceMappingResponse cleared = lambda.updateEventSourceMapping(
                UpdateEventSourceMappingRequest.builder()
                        .uuid(created.uuid())
                        .scalingConfig(ScalingConfig.builder().build())
                        .build());
        assertThat(cleared.scalingConfig() == null
                || cleared.scalingConfig().maximumConcurrency() == null).isTrue();
    }
}
