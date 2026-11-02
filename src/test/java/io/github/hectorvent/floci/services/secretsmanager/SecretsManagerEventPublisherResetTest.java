package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.EmulatorInfoController;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecretsManagerEventPublisherResetTest {
    private static final String ACCOUNT = "661100000003";
    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:secretsmanager:us-east-1:661100000003:secret:example";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudTrailService trails = mock(CloudTrailService.class);
    private final EventBridgeService events = mock(EventBridgeService.class);
    private final StorageFactory storage = mock(StorageFactory.class);
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private SecretsManagerEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SecretsManagerEventPublisher(trails, events, new AccountResolver(ACCOUNT), mapper, queued::addLast);
        when(trails.emitManagementEvent(any())).thenAnswer(call -> {
            CloudTrailService.ManagementEventInput input = call.getArgument(0);
            return Optional.of(mapper.createObjectNode().put("recipientAccountId", ACCOUNT).put("awsRegion", REGION)
                    .put("eventID", input.requestParameters().path("clientRequestToken").asText()));
        });
    }

    @AfterEach
    void stop() {
        publisher.shutdown();
    }

    @Test
    void queuedEventsCannotReachRulesCreatedAfterReset() {
        write("before-reset");
        Runnable oldDelivery = queued.removeFirst();

        controller().reset();
        oldDelivery.run();

        verifyNoInteractions(events);
        write("after-reset");
        queued.removeFirst().run();
        verify(events).publishServiceEvent(eq("aws.secretsmanager"), eq("AWS API Call via CloudTrail"),
                contains("after-reset"), eq(ARN), eq(REGION));
    }

    @Test
    void resetDrainsActiveDeliveriesBeforeWipeDiscardsQueuedEventsAndReusesWorkers() throws Exception {
        publisher = new SecretsManagerEventPublisher(trails, events, new AccountResolver(ACCOUNT), mapper);
        CountDownLatch active = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fresh = new CountDownLatch(1);
        List<String> stored = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            String id = mapper.readTree((String) call.getArgument(2)).path("eventID").asText();
            if (id.startsWith("active-")) {
                active.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "blocked targets must be released by the test");
            }
            stored.add(id);
            if ("after-reset".equals(id)) {
                fresh.countDown();
            }
            return null;
        }).when(events).publishServiceEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        doAnswer(call -> { stored.clear(); return null; }).when(storage).clearAll();
        ExecutorService resets = Executors.newSingleThreadExecutor();
        try {
            write("active-first");
            write("active-second");
            assertTrue(active.await(5, TimeUnit.SECONDS));
            write("queued-before-reset");

            Future<?> reset = resets.submit(() -> controller().reset());
            assertThrows(TimeoutException.class, () -> reset.get(200, TimeUnit.MILLISECONDS),
                    "reset must wait for active target deliveries before wiping storage");
            verify(storage, never()).clearAll();
            release.countDown();
            reset.get(5, TimeUnit.SECONDS);
            assertTrue(stored.isEmpty(), "active deliveries must finish before their state is wiped");

            write("after-reset");
            assertTrue(fresh.await(5, TimeUnit.SECONDS), "the same publisher must deliver new events after reset");
            assertEquals(List.of("after-reset"), stored);
            verify(events, times(3)).publishServiceEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        } finally {
            release.countDown();
            resets.shutdownNow();
            assertTrue(resets.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void notificationsAreDroppedDuringWipeAndResumeAfterward() throws Exception {
        CountDownLatch wiping = new CountDownLatch(1);
        CountDownLatch finishWipe = new CountDownLatch(1);
        doAnswer(call -> {
            wiping.countDown();
            assertTrue(finishWipe.await(5, TimeUnit.SECONDS));
            return null;
        }).when(storage).clearAll();
        ExecutorService resets = Executors.newSingleThreadExecutor();
        try {
            Future<?> reset = resets.submit(() -> controller().reset());
            assertTrue(wiping.await(5, TimeUnit.SECONDS));
            write("during-reset");
            assertTrue(queued.isEmpty(), "notifications must not enter the queue during the storage wipe");
            verifyNoInteractions(trails, events);
            finishWipe.countDown();
            reset.get(5, TimeUnit.SECONDS);
            write("after-reset");
            queued.removeFirst().run();
            verify(events).publishServiceEvent(anyString(), anyString(), contains("after-reset"), eq(ARN), eq(REGION));
        } finally {
            finishWipe.countDown();
            resets.shutdownNow();
            assertTrue(resets.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedStorageWipeStillDiscardsOldEventsAndResumesNotifications() {
        write("before-reset");
        Runnable oldDelivery = queued.removeFirst();
        doThrow(new IllegalStateException("example storage failure")).when(storage).clearAll();

        assertThrows(IllegalStateException.class, () -> controller().reset());
        oldDelivery.run();
        verifyNoInteractions(events);
        write("after-failed-reset");
        queued.removeFirst().run();
        verify(events).publishServiceEvent(anyString(), anyString(), contains("after-failed-reset"), eq(ARN), eq(REGION));
    }

    @Test
    void interruptedDrainRefusesTheWipeAndRestoresPublication() throws Exception {
        publisher = new SecretsManagerEventPublisher(trails, events, new AccountResolver(ACCOUNT), mapper);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fresh = new CountDownLatch(1);
        doAnswer(call -> {
            if (((String) call.getArgument(2)).contains("active-before-reset")) {
                active.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } else {
                fresh.countDown();
            }
            return null;
        }).when(events).publishServiceEvent(anyString(), anyString(), anyString(), anyString(), anyString());
        try {
            write("active-before-reset");
            assertTrue(active.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> controller().reset());
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(storage, never()).clearAll();
        } finally {
            Thread.interrupted();
            release.countDown();
        }
        write("after-failed-reset");
        assertTrue(fresh.await(5, TimeUnit.SECONDS));
    }

    @Test
    void resetCannotRestartAShutDownPublisher() {
        write("before-shutdown");
        Runnable oldDelivery = queued.removeFirst();
        publisher.shutdown();
        controller().reset();
        oldDelivery.run();
        write("after-shutdown");
        assertTrue(queued.isEmpty());
        verifyNoInteractions(events);
    }

    private void write(String id) {
        ObjectNode request = mapper.createObjectNode().put("SecretId", ARN).put("ClientRequestToken", id);
        publisher.secretWritten("PutSecretValue", request, mapper.createObjectNode().put("ARN", ARN), REGION, null);
    }

    @SuppressWarnings("unchecked")
    private EmulatorInfoController controller() {
        Instance<Resettable> services = mock(Instance.class);
        when(services.iterator()).thenAnswer(call -> Resettable.class.isInstance(publisher)
                ? List.of(Resettable.class.cast(publisher)).iterator() : List.<Resettable>of().iterator());
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(call -> List.<ContainerTeardown>of().iterator());
        return new EmulatorInfoController(null, null, storage, services, teardowns, null, null, null);
    }
}
