package io.floci.aws.services.rds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsLifecycleEventFixtureTest {

    private RdsClient rds;
    private EventBridgeClient events;
    private SqsClient sqs;

    @BeforeEach
    void setUp() {
        rds = mock(RdsClient.class);
        events = mock(EventBridgeClient.class);
        sqs = mock(SqsClient.class);
        when(sqs.createQueue(any(CreateQueueRequest.class)))
                .thenReturn(CreateQueueResponse.builder().queueUrl("http://localhost/queue").build());
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(
                GetQueueAttributesResponse.builder().attributes(Map.of(
                        QueueAttributeName.QUEUE_ARN, "arn:aws:sqs:us-east-1:111122223333:capture")).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rds-client", "events-client", "sqs-client", "queue", "rule", "targets"})
    void constructionFailureClosesEveryClientThatWasCreated(String stage) {
        IllegalStateException failure = new IllegalStateException(stage);
        Supplier<RdsClient> rdsFactory = () -> {
            if ("rds-client".equals(stage)) {
                throw failure;
            }
            return rds;
        };
        Supplier<EventBridgeClient> eventsFactory = () -> {
            if ("events-client".equals(stage)) {
                throw failure;
            }
            return events;
        };
        Supplier<SqsClient> sqsFactory = () -> {
            if ("sqs-client".equals(stage)) {
                throw failure;
            }
            return sqs;
        };
        if ("queue".equals(stage)) {
            when(sqs.createQueue(any(CreateQueueRequest.class))).thenThrow(failure);
        } else if ("rule".equals(stage)) {
            when(events.putRule(any(PutRuleRequest.class))).thenThrow(failure);
        } else if ("targets".equals(stage)) {
            when(events.putTargets(any(PutTargetsRequest.class))).thenThrow(failure);
        }
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new RdsLifecycleEventFixture(
                "111122223333", Region.US_EAST_1, "capture", "{}", rdsFactory, eventsFactory, sqsFactory)));
        if (!"rds-client".equals(stage)) {
            verify(rds).close();
        }
        if (!"rds-client".equals(stage) && !"events-client".equals(stage)) {
            verify(events).close();
        }
        if (!stage.endsWith("-client")) {
            verify(sqs).close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"targets", "rule", "queue"})
    void cleanupFailureStillAttemptsRemainingCleanupAndClosesAllClients(String stage) {
        RdsLifecycleEventFixture fixture = fixture();
        IllegalStateException failure = new IllegalStateException(stage);
        if ("targets".equals(stage)) {
            when(events.removeTargets(any(RemoveTargetsRequest.class))).thenThrow(failure);
        } else if ("rule".equals(stage)) {
            when(events.deleteRule(any(DeleteRuleRequest.class))).thenThrow(failure);
        } else {
            when(sqs.deleteQueue(any(DeleteQueueRequest.class))).thenThrow(failure);
        }
        assertSame(failure, assertThrows(IllegalStateException.class, fixture::close));
        verify(events).removeTargets(any(RemoveTargetsRequest.class));
        verify(events).deleteRule(any(DeleteRuleRequest.class));
        verify(sqs).deleteQueue(any(DeleteQueueRequest.class));
        verify(rds).close();
        verify(events).close();
        verify(sqs).close();
    }

    @Test
    void aClientCloseFailureDoesNotPreventOtherClientsFromClosing() {
        RdsLifecycleEventFixture fixture = fixture();
        doThrow(new IllegalStateException("transport close failed")).when(sqs).close();
        assertThrows(IllegalStateException.class, fixture::close);
        verify(rds).close();
        verify(events).close();
    }

    @Test
    void failedSetupPreservesItsCauseWhenCleanupAlsoFails() {
        IllegalStateException setup = new IllegalStateException("target setup failed");
        IllegalStateException cleanup = new IllegalStateException("queue cleanup failed");
        when(events.putTargets(any(PutTargetsRequest.class))).thenThrow(setup);
        when(sqs.deleteQueue(any(DeleteQueueRequest.class))).thenThrow(cleanup);
        assertSame(setup, assertThrows(IllegalStateException.class, this::fixture));
        assertEquals(1, setup.getSuppressed().length);
        assertSame(cleanup, setup.getSuppressed()[0]);
        verify(rds).close();
        verify(events).close();
        verify(sqs).close();
    }

    private RdsLifecycleEventFixture fixture() {
        return new RdsLifecycleEventFixture("111122223333", Region.US_EAST_1,
                "capture", "{}", () -> rds, () -> events, () -> sqs);
    }
}
