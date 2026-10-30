package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class CloudFormationEventPublisherTest {

    private static final String STACK_ID = "arn:aws:cloudformation:us-east-1:000000000000:stack/s/1";
    private static final long DELIVERY_TIMEOUT_MILLIS = 5_000;

    private final EventBridgeService eventBridge = mock(EventBridgeService.class);
    private final CloudFormationEventPublisher publisher =
            new CloudFormationEventPublisher(eventBridge, new ObjectMapper());

    @BeforeEach
    void start() {
        publisher.start();
    }

    @AfterEach
    void stop() {
        publisher.stop();
    }

    private static Stack stackInStatus(String status) {
        Stack stack = new Stack();
        stack.setStackId(STACK_ID);
        stack.setRegion("us-east-1");
        stack.setStatus(status);
        return stack;
    }

    private static String detailWith(String status) {
        return contains("\"status\":\"" + status + "\"");
    }

    @Test
    void anEventRecordedBeforeItsStatusWriteWaitsForThatWrite() {
        Stack stack = stackInStatus("UPDATE_IN_PROGRESS");

        publisher.stackStatusChanged(stack, "000000000000", "UPDATE_COMPLETE", null);

        verify(eventBridge, after(200).never()).publishServiceEvent(anyString(), anyString(), anyString(),
                anyString(), anyString());
        stack.setStatus("UPDATE_COMPLETE");
        verify(eventBridge, timeout(DELIVERY_TIMEOUT_MILLIS)).publishServiceEvent(
                eq(CloudFormationEventPublisher.SOURCE), eq(CloudFormationEventPublisher.DETAIL_TYPE),
                detailWith("UPDATE_COMPLETE"), eq(STACK_ID), eq("us-east-1"));
    }

    @Test
    void anEventStillQueuedWhenFlociStopsIsDropped() {
        Stack stack = stackInStatus("UPDATE_IN_PROGRESS");
        publisher.stackStatusChanged(stack, "000000000000", "UPDATE_COMPLETE", null);

        publisher.stop();
        stack.setStatus("UPDATE_COMPLETE");

        verify(eventBridge, after(200).never()).publishServiceEvent(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void aStatusLeftUnwrittenDoesNotHoldUpTheEventsBehindIt() {
        Stack stack = stackInStatus("UPDATE_IN_PROGRESS");

        publisher.stackStatusChanged(stack, "000000000000", "UPDATE_COMPLETE", null);

        verify(eventBridge, timeout(DELIVERY_TIMEOUT_MILLIS)).publishServiceEvent(anyString(), anyString(),
                detailWith("UPDATE_COMPLETE"), anyString(), anyString());
    }

    @Test
    void eventsAreDeliveredInTheOrderRecordedAndAFailureStopsNothing() {
        doThrow(new IllegalStateException("bus is down")).when(eventBridge).publishServiceEvent(
                anyString(), anyString(), detailWith("CREATE_IN_PROGRESS"), anyString(), anyString());
        Stack stack = stackInStatus("CREATE_IN_PROGRESS");

        publisher.stackStatusChanged(stack, "000000000000", "CREATE_IN_PROGRESS", null);
        stack.setStatus("CREATE_COMPLETE");
        publisher.stackStatusChanged(stack, "000000000000", "CREATE_COMPLETE", null);

        verify(eventBridge, timeout(DELIVERY_TIMEOUT_MILLIS)).publishServiceEvent(anyString(), anyString(),
                detailWith("CREATE_COMPLETE"), anyString(), anyString());
        InOrder order = inOrder(eventBridge);
        order.verify(eventBridge).publishServiceEvent(anyString(), anyString(), detailWith("CREATE_IN_PROGRESS"),
                anyString(), anyString());
        order.verify(eventBridge).publishServiceEvent(anyString(), anyString(), detailWith("CREATE_COMPLETE"),
                anyString(), anyString());
    }
}
