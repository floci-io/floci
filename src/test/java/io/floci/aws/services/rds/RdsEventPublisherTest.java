package io.floci.aws.services.rds;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.testutil.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RdsEventPublisherTest {

    @Test
    void reentrantDeliveryPreservesSnapshotsAndDrainsWithoutRecursing() {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        RdsEventPublisher publisher = new RdsEventPublisher(eventBridge, new ObjectMapper());
        String arn = "arn:aws-cn:rds:cn-north-1:111122223333:db:one";
        List<String> details = new ArrayList<>();
        AtomicBoolean delivering = new AtomicBoolean();
        doAnswer(invocation -> {
            assertTrue(delivering.compareAndSet(false, true));
            details.add(invocation.getArgument(2));
            assertEquals(arn, invocation.getArgument(3));
            assertEquals("cn-north-1", invocation.getArgument(4));
            if (details.size() == 1) {
                publisher.queue(arn, "one", false, true);
                publisher.publishQueued();
                assertEquals(1, details.size());
            }
            delivering.set(false);
            return null;
        }).when(eventBridge).publishServiceEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        publisher.queue(arn, "one", false, false);
        verifyNoInteractions(eventBridge);
        publisher.publishQueued();
        publisher.publishQueued();
        assertEquals(2, details.size());
        assertTrue(details.getFirst().contains("RDS-EVENT-0087"));
        assertTrue(details.getLast().contains("RDS-EVENT-0088"));
        assertFalse(delivering.get());
    }

    @Test
    void aDeliveryFailureDoesNotLoseLaterEventsOrEscapeToTheRdsCaller() {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        RdsEventPublisher publisher = new RdsEventPublisher(eventBridge, new ObjectMapper());
        List<String> details = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("target unavailable");
        doAnswer(invocation -> {
            details.add(invocation.getArgument(2));
            if (details.size() == 1) {
                throw failure;
            }
            return null;
        }).when(eventBridge).publishServiceEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        String arn = "arn:aws:rds:eu-west-2:111122223333:cluster:one";
        publisher.queue(arn, "one", true, false);
        publisher.queue(arn, "one", true, true);
        List<LogRecord> records = LogCapture.capture(RdsEventPublisher.class, publisher::publishQueued);
        assertEquals(2, details.size());
        assertTrue(details.getFirst().contains("RDS-EVENT-0150"));
        assertTrue(details.getLast().contains("RDS-EVENT-0151"));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING.intValue(), records.getFirst().getLevel().intValue());
        assertSame(failure, records.getFirst().getThrown());
        assertTrue(LogCapture.formatted(records.getFirst()).contains(arn));
    }
}
