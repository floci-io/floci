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

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Lambda - ESM ARN and tags")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaEsmTagsTest {

    private static final String FUNCTION_NAME = "sdk-test-esm-tags-fn";
    private static final String SQS_QUEUE_NAME = "sdk-test-esm-tags-queue";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static LambdaClient lambda;
    private static SqsClient sqs;
    private static String queueUrl;
    private static String esmUuid;
    private static String esmArn;

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
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            if (esmUuid != null) {
                try {
                    lambda.deleteEventSourceMapping(DeleteEventSourceMappingRequest.builder()
                            .uuid(esmUuid).build());
                } catch (Exception ignored) {
                    // Best-effort teardown: the ordered tests delete the mapping themselves,
                    // so a ResourceNotFoundException here is the expected case.
                }
            }
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(FUNCTION_NAME).build());
            } catch (Exception ignored) {
                // Best-effort teardown: a leftover function does not affect other suites.
            }
            lambda.close();
        }
        if (sqs != null) {
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            } catch (Exception ignored) {
                // Best-effort teardown: a leftover queue does not affect other suites.
            }
            sqs.close();
        }
    }

    @Test
    @Order(1)
    void createEsm_returnsArnAndStoresTags() {
        String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        CreateEventSourceMappingResponse created = lambda.createEventSourceMapping(
                CreateEventSourceMappingRequest.builder()
                        .functionName(FUNCTION_NAME)
                        .eventSourceArn(queueArn)
                        .tags(Map.of("Environment", "uat", "ManagedBy", "terraform"))
                        .build());
        esmUuid = created.uuid();
        esmArn = created.eventSourceMappingArn();

        assertThat(esmArn).matches("arn:aws[a-z-]*:lambda:[a-z0-9-]+:\\d{12}:event-source-mapping:" + esmUuid);

        ListTagsResponse tags = lambda.listTags(ListTagsRequest.builder().resource(esmArn).build());
        assertThat(tags.tags()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Environment", "uat", "ManagedBy", "terraform"));
    }

    @Test
    @Order(2)
    void getAndListEsm_returnTheSameArn() {
        GetEventSourceMappingResponse fetched = lambda.getEventSourceMapping(
                GetEventSourceMappingRequest.builder().uuid(esmUuid).build());
        assertThat(fetched.eventSourceMappingArn()).isEqualTo(esmArn);

        ListEventSourceMappingsResponse listed = lambda.listEventSourceMappings(
                ListEventSourceMappingsRequest.builder().functionName(FUNCTION_NAME).build());
        assertThat(listed.eventSourceMappings())
                .filteredOn(m -> esmUuid.equals(m.uuid()))
                .singleElement()
                .extracting(EventSourceMappingConfiguration::eventSourceMappingArn)
                .isEqualTo(esmArn);
    }

    @Test
    @Order(3)
    void tagAndUntagResource_onEsmArn() {
        lambda.tagResource(TagResourceRequest.builder()
                .resource(esmArn)
                .tags(Map.of("Layer", "backend"))
                .build());
        lambda.untagResource(UntagResourceRequest.builder()
                .resource(esmArn)
                .tagKeys("ManagedBy")
                .build());

        ListTagsResponse tags = lambda.listTags(ListTagsRequest.builder().resource(esmArn).build());
        assertThat(tags.tags()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Environment", "uat", "Layer", "backend"));
    }

    @Test
    @Order(4)
    void listTags_afterDelete_throwsResourceNotFound() {
        lambda.deleteEventSourceMapping(DeleteEventSourceMappingRequest.builder().uuid(esmUuid).build());
        esmUuid = null;

        assertThatThrownBy(() -> lambda.listTags(ListTagsRequest.builder().resource(esmArn).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
