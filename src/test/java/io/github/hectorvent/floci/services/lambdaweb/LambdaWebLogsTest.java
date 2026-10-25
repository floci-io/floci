package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LambdaWebLogsTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String GROUP = "/aws/lambda/web/web";
    private static final String STREAM = "2026/10/18/web[rev-abcde]0123456789abcdef0123456789abcdef";
    private static final String CENTRAL_STREAM = STREAM + "-" + ACCOUNT + "-" + REGION;

    @Mock
    CloudWatchLogsService cloudWatchLogs;

    private LambdaWebLogs logs;

    @BeforeEach
    void start() {
        logs = new LambdaWebLogs(cloudWatchLogs);
    }

    @AfterEach
    void stop() {
        logs.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsGoToTheLocalAndTheCentralizedGroupCreatedWhenMissing() {
        doThrow(notFound()).doReturn("token").when(cloudWatchLogs)
                .putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"), eq(STREAM), anyList(), eq(REGION));
        doThrow(notFound()).doReturn("token").when(cloudWatchLogs)
                .putLogEventsForAccount(eq(ACCOUNT), eq(GROUP), eq(CENTRAL_STREAM), anyList(), eq(REGION));

        logs.open(ACCOUNT, REGION, REGION, GROUP, STREAM, "INFO").accept("first\n");

        ArgumentCaptor<List<Map<String, Object>>> local = ArgumentCaptor.forClass(List.class);
        verify(cloudWatchLogs, timeout(5_000).times(2)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"),
                eq(STREAM), local.capture(), eq(REGION));
        verify(cloudWatchLogs, timeout(5_000).times(2)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP),
                eq(CENTRAL_STREAM), anyList(), eq(REGION));
        verify(cloudWatchLogs).createLogGroupForAccount(ACCOUNT, GROUP + ".local", null, null, REGION);
        verify(cloudWatchLogs).createLogStreamForAccount(ACCOUNT, GROUP + ".local", STREAM, REGION);
        verify(cloudWatchLogs).createLogGroupForAccount(ACCOUNT, GROUP, null, null, REGION);
        verify(cloudWatchLogs).createLogStreamForAccount(ACCOUNT, GROUP, CENTRAL_STREAM, REGION);
        assertEquals("first\n", local.getValue().getFirst().get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEnvironmentInAnotherRegionWritesTheLocalGroupThereAndTheCentralizedOneInTheHomeRegion() {
        String replica = "eu-central-1";
        ObjectNode record = new ObjectMapper().createObjectNode().put("functionName", "web");

        logs.open(ACCOUNT, replica, REGION, GROUP, STREAM, "INFO").platform(Instant.now(), "INFO",
                "platform.initStart", record);

        ArgumentCaptor<List<Map<String, Object>>> local = ArgumentCaptor.forClass(List.class);
        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"), eq(STREAM),
                local.capture(), eq(replica));
        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP),
                eq(STREAM + "-" + ACCOUNT + "-" + replica), anyList(), eq(REGION));
        String message = (String) local.getValue().getFirst().get("message");
        assertTrue(message.contains("\"region\":\"eu-central-1\""), message);
    }

    @Test
    void aGroupThatCannotBeCreatedTakesNoEventsAndTheOtherStillDoes() {
        doThrow(notFound()).when(cloudWatchLogs)
                .putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"), eq(STREAM), anyList(), eq(REGION));
        doThrow(new AwsException("InvalidParameterException", "Invalid log group name.", 400)).when(cloudWatchLogs)
                .createLogGroupForAccount(ACCOUNT, GROUP + ".local", null, null, REGION);

        logs.open(ACCOUNT, REGION, REGION, GROUP, STREAM, "INFO").accept("first\n");

        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP), eq(CENTRAL_STREAM),
                anyList(), eq(REGION));
        verify(cloudWatchLogs, never()).createLogStreamForAccount(ACCOUNT, GROUP + ".local", STREAM, REGION);
    }

    @Test
    void eventsQueuedWhileAWriteFailsAreStillDelivered() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch queued = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            queued.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("the store failed");
        }).doReturn("token").when(cloudWatchLogs)
                .putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"), eq(STREAM), anyList(), eq(REGION));
        LambdaWebLogs.Sink sink = logs.open(ACCOUNT, REGION, REGION, GROUP, STREAM, "INFO");

        sink.accept("first\n");
        writing.await(5, TimeUnit.SECONDS);
        sink.accept("second\n");
        queued.countDown();

        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"), eq(STREAM),
                argThat(batch -> batch.size() == 1 && "second\n".equals(batch.getFirst().get("message"))), eq(REGION));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsDroppedWhileTheQueueIsFullAreReportedBeforeTheNextOne() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "token";
        }).doReturn("token").when(cloudWatchLogs)
                .putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"), eq(STREAM), anyList(), eq(REGION));
        LambdaWebLogs.Sink sink = logs.open(ACCOUNT, REGION, REGION, GROUP, STREAM, "INFO");
        sink.accept("first\n");
        writing.await(5, TimeUnit.SECONDS);

        for (int i = 0; i < LambdaWebLogs.MAX_QUEUED_EVENTS + 3; i++) {
            sink.accept("queued\n");
        }
        release.countDown();
        int drained = 1 + LambdaWebLogs.MAX_QUEUED_EVENTS / 1_000;
        verify(cloudWatchLogs, timeout(5_000).times(drained)).putLogEventsForAccount(eq(ACCOUNT), eq(GROUP + ".local"),
                eq(STREAM), anyList(), eq(REGION));
        sink.accept("after\n");

        ArgumentCaptor<List<Map<String, Object>>> batches = ArgumentCaptor.forClass(List.class);
        verify(cloudWatchLogs, timeout(5_000).times(drained + 1)).putLogEventsForAccount(eq(ACCOUNT),
                eq(GROUP + ".local"), eq(STREAM), batches.capture(), eq(REGION));
        List<String> messages = batches.getAllValues().stream().flatMap(List::stream)
                .map(event -> (String) event.get("message")).toList();
        String dropped = messages.get(messages.size() - 2);
        assertTrue(dropped.contains("\"level\":\"WARN\",\"type\":\"platform.logsDropped\""), dropped);
        assertTrue(dropped.endsWith("\"droppedRecords\":3,\"droppedBytes\":21}}"), dropped);
        assertEquals("after\n", messages.getLast());
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException", "The specified log stream does not exist.", 400);
    }
}
