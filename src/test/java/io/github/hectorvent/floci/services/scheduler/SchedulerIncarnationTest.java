package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerIncarnationTest {

    private static final String REGION = "us-east-1";

    @Test
    void incarnationSurvivesUpdatesAndPersistenceButChangesOnRecreation() throws Exception {
        SchedulerService service = service(new InMemoryStorage<>());
        Schedule created = service.createSchedule(request("owned", "first"), REGION);
        assertTrue(service.isScheduleIncarnationCurrent("owned", "default", REGION, created.getIncarnationId()));
        Schedule updated = service.updateSchedule(request("owned", "updated"), REGION, created.getIncarnationId());
        assertEquals(created.getIncarnationId(), updated.getIncarnationId());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Schedule restored = mapper.readValue(mapper.writeValueAsString(updated), Schedule.class);
        assertEquals(updated.getIncarnationId(), restored.getIncarnationId());
        assertNull(mapper.readValue("{}", Schedule.class).getIncarnationId());

        service.deleteSchedule("owned", "default", REGION);
        assertFalse(service.isScheduleIncarnationCurrent("owned", "default", REGION, created.getIncarnationId()));
        Schedule recreated = service.createSchedule(request("owned", "foreign"), REGION);
        assertNotEquals(created.getIncarnationId(), recreated.getIncarnationId());
        assertEquals(created.getArn(), recreated.getArn());
        assertFalse(service.isScheduleIncarnationCurrent("owned", "default", REGION, created.getIncarnationId()));
        service.deleteSchedule("owned", "default", REGION, created.getIncarnationId());
        assertEquals("foreign", service.getSchedule("owned", "default", REGION).getTarget().getInput());
        assertThrows(AwsException.class, () ->
                service.updateSchedule(request("owned", "wrong"), REGION, created.getIncarnationId()));
        service.deleteSchedule("owned", "default", REGION, recreated.getIncarnationId());
        assertThrows(AwsException.class, () -> service.getSchedule("owned", "default", REGION));
    }

    @Test
    void unknownOwnershipCannotAdoptAnExistingSchedule() {
        SchedulerService service = service(new InMemoryStorage<>());
        service.createSchedule(request("unknown", "first"), REGION);
        assertThrows(AwsException.class, () -> service.deleteSchedule("unknown", "default", REGION, null));
        assertThrows(AwsException.class, () -> service.updateSchedule(request("unknown", "wrong"), REGION, null));
        assertEquals("first", service.getSchedule("unknown", "default", REGION).getTarget().getInput());
    }

    @Test
    void aPersistedScheduleWithoutIncarnationProofFailsClosed() throws Exception {
        InMemoryStorage<String, Schedule> store = new InMemoryStorage<>();
        Schedule legacy = new ObjectMapper().readValue("""
                {"name":"legacy","groupName":"default","state":"DISABLED"}
                """, Schedule.class);
        store.put("schedule:" + REGION + ":default:legacy", legacy);
        SchedulerService service = service(store);

        assertThrows(AwsException.class, () ->
                service.deleteSchedule("legacy", "default", REGION, "previous-proof"));
        assertThrows(AwsException.class, () ->
                service.updateSchedule(request("legacy", "wrong"), REGION, "previous-proof"));
        assertThrows(AwsException.class, () ->
                service.isScheduleIncarnationCurrent("legacy", "default", REGION, "previous-proof"));
        assertEquals("DISABLED", service.getSchedule("legacy", "default", REGION).getState());
        assertNull(service.getSchedule("legacy", "default", REGION).getIncarnationId());
    }

    @Test
    void checkedDeletionAndPublicRecreationCannotInterleave() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        InMemoryStorage<String, Schedule> store = new InMemoryStorage<>() {
            @Override
            public Optional<Schedule> get(String key) {
                Optional<Schedule> value = super.get(key);
                if (Thread.currentThread().getName().equals("owned-cleanup") && blockOnce.getAndSet(false)) {
                    checked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Cleanup test barrier timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Cleanup test interrupted", e);
                    }
                }
                return value;
            }
        };
        SchedulerService service = service(store);
        String incarnation = service.createSchedule(request("racing", "first"), REGION).getIncarnationId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanup = executor.submit(() -> {
                Thread.currentThread().setName("owned-cleanup");
                service.deleteSchedule("racing", "default", REGION, incarnation);
            });
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            CountDownLatch recreationStarted = new CountDownLatch(1);
            Future<?> recreation = executor.submit(() -> {
                recreationStarted.countDown();
                try {
                    service.deleteSchedule("racing", "default", REGION);
                } catch (AwsException expected) {
                    assertEquals("ResourceNotFoundException", expected.getErrorCode());
                }
                service.createSchedule(request("racing", "foreign"), REGION);
            });
            assertTrue(recreationStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> recreation.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            cleanup.get(5, TimeUnit.SECONDS);
            recreation.get(5, TimeUnit.SECONDS);
            Schedule foreign = service.getSchedule("racing", "default", REGION);
            assertEquals("foreign", foreign.getTarget().getInput());
            assertNotEquals(incarnation, foreign.getIncarnationId());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static SchedulerService service(InMemoryStorage<String, Schedule> store) {
        return new SchedulerService(new InMemoryStorage<>(), store,
                new RegionResolver(REGION, "000000000000"));
    }

    private static ScheduleRequest request(String name, String input) {
        ScheduleRequest request = new ScheduleRequest();
        request.setName(name);
        request.setGroupName("default");
        request.setScheduleExpression("rate(5 minutes)");
        request.setState("DISABLED");
        FlexibleTimeWindow window = new FlexibleTimeWindow();
        window.setMode("OFF");
        request.setFlexibleTimeWindow(window);
        Target target = new Target();
        target.setArn("arn:aws:sqs:us-east-1:000000000000:queue");
        target.setRoleArn("arn:aws:iam::000000000000:role/scheduler");
        target.setInput(input);
        request.setTarget(target);
        return request;
    }
}
