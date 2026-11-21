package io.floci.aws.services.s3;

import io.floci.aws.testing.AwsTestClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClientBuilder;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class S3EventBridgeFixtureCleanupTest {

    @ParameterizedTest
    @ValueSource(strings = {"queue", "attributes", "rule"})
    void setupFailureStillRemovesCreatedResources(String failureAt) throws Exception {
        S3Client s3 = mock(S3Client.class);
        SqsClient sqs = mock(SqsClient.class);
        EventBridgeClient events = mock(EventBridgeClient.class);
        IllegalStateException failure = new IllegalStateException("setup failed at " + failureAt);
        when(sqs.createQueue(any(Consumer.class))).thenReturn(CreateQueueResponse.builder().queueUrl("queue-url").build());
        when(sqs.getQueueAttributes(any(Consumer.class))).thenReturn(GetQueueAttributesResponse.builder()
                .attributes(Map.of(QueueAttributeName.QUEUE_ARN, "arn:aws:sqs:us-east-1:000000000000:q")).build());
        switch (failureAt) {
            case "queue" -> when(sqs.createQueue(any(Consumer.class))).thenThrow(failure);
            case "attributes" -> when(sqs.getQueueAttributes(any(Consumer.class))).thenThrow(failure);
            case "rule" -> when(events.putRule(any(Consumer.class))).thenThrow(failure);
            default -> throw new AssertionError(failureAt);
        }
        try (MockedStatic<AwsTestClients> clients = clients(s3, sqs, events)) {
            S3EventBridgeEventShapeIntegrationTest fixture = new S3EventBridgeEventShapeIntegrationTest();
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> fixture.creationEventCarriesApiReasonAndBucketArn("us-east-1", "aws", "PutObject")));
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
            verify(s3).deleteBucket(any(Consumer.class));
            if (!failureAt.equals("queue")) {
                verify(sqs).deleteQueue(any(Consumer.class));
            }
        }
    }

    @Test
    void failedTargetCleanupDoesNotSkipRuleQueueOrBucketDeletion() throws Exception {
        S3Client s3 = mock(S3Client.class);
        SqsClient sqs = mock(SqsClient.class);
        EventBridgeClient events = mock(EventBridgeClient.class);
        IllegalStateException failure = new IllegalStateException("seed upload failed");
        when(sqs.createQueue(any(Consumer.class))).thenReturn(CreateQueueResponse.builder().queueUrl("queue-url").build());
        when(sqs.getQueueAttributes(any(Consumer.class))).thenReturn(GetQueueAttributesResponse.builder()
                .attributes(Map.of(QueueAttributeName.QUEUE_ARN, "arn:aws:sqs:us-east-1:000000000000:q")).build());
        when(events.putTargets(any(Consumer.class))).thenReturn(PutTargetsResponse.builder().failedEntryCount(0).build());
        when(s3.putObject(any(Consumer.class), any(RequestBody.class))).thenThrow(failure);
        when(events.removeTargets(any(Consumer.class))).thenThrow(new IllegalStateException("target cleanup failed"));
        try (MockedStatic<AwsTestClients> clients = clients(s3, sqs, events)) {
            S3EventBridgeEventShapeIntegrationTest fixture = new S3EventBridgeEventShapeIntegrationTest();
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> fixture.creationEventCarriesApiReasonAndBucketArn("us-east-1", "aws", "PutObject")));
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
            verify(events).deleteRule(any(Consumer.class));
            verify(sqs).deleteQueue(any(Consumer.class));
            verify(s3).deleteBucket(any(Consumer.class));
        }
    }

    private static MockedStatic<AwsTestClients> clients(S3Client s3, SqsClient sqs, EventBridgeClient events) {
        MockedStatic<AwsTestClients> clients = mockStatic(AwsTestClients.class, withSettings().mockMaker(MockMakers.INLINE));
        clients.when(() -> AwsTestClients.create(any(S3ClientBuilder.class), eq("000000000000"), eq(Region.US_EAST_1)))
                .thenReturn(s3);
        clients.when(() -> AwsTestClients.create(any(SqsClientBuilder.class), eq("000000000000"), eq(Region.US_EAST_1)))
                .thenReturn(sqs);
        clients.when(() -> AwsTestClients.create(any(EventBridgeClientBuilder.class), eq("000000000000"), eq(Region.US_EAST_1)))
                .thenReturn(events);
        return clients;
    }
}
