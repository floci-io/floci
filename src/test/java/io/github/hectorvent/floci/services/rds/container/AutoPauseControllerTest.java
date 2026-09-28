package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.services.rds.container.AutoPauseController.State;
import io.github.hectorvent.floci.services.rds.container.AutoPauseListener.Event;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AutoPauseControllerTest {

    private static final Duration IDLE = Duration.ofSeconds(300);

    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakeFreezer freezer = new FakeFreezer();
    private final RecordingListener listener = new RecordingListener();

    @Test
    void pausesOnceIdleForSecondsUntilAutoPause() {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);

        scheduler.advance(Duration.ofSeconds(299));
        assertEquals(State.RUNNING, controller.state());
        assertEquals(0, freezer.pauses.get());

        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(State.PAUSED, controller.state());
        assertTrue(freezer.paused);
        assertEquals(List.of(Event.PAUSE_INITIATED, Event.PAUSED), listener.events);
        assertTrue(listener.times.get(1).isAfter(listener.times.get(0)),
                "events raised within one instant still keep their order");
    }

    @Test
    void openConnectionsKeepItAwakeAndTheIdleClockStartsWhenTheLastOneCloses() throws Exception {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        RdsBackendGate.Lease first = controller.acquire();
        RdsBackendGate.Lease second = controller.acquire();

        scheduler.advance(Duration.ofHours(1));
        first.close();
        scheduler.advance(Duration.ofHours(1));
        assertEquals(State.RUNNING, controller.state(), "one connection is still open");

        second.close();
        scheduler.advance(Duration.ofSeconds(299));
        assertEquals(State.RUNNING, controller.state());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(State.PAUSED, controller.state());
    }

    @Test
    void connectionResumesAPausedContainerBeforeItIsServed() throws Exception {
        AutoPauseController controller = pausedController(Duration.ZERO);

        RdsBackendGate.Lease lease = controller.acquire();

        assertEquals(State.RUNNING, controller.state());
        assertFalse(freezer.paused);
        assertEquals(1, freezer.unpauses.get());
        assertEquals(1, controller.leaseCount());
        assertEquals(List.of(Event.PAUSE_INITIATED, Event.PAUSED, Event.RESUME_INITIATED, Event.RESUMED),
                listener.events);

        lease.close();
        scheduler.advance(IDLE);
        assertEquals(State.PAUSED, controller.state(), "idle again after the connection closed");
    }

    @Test
    void resumeDelayHoldsConnectionsIncludingOnesThatArriveWhileResuming() throws Exception {
        AutoPauseController controller = pausedController(Duration.ofSeconds(15));

        Acquirer first = new Acquirer(controller, freezer);
        awaitCondition(() -> controller.state() == State.RESUMING && first.isWaiting(), "first waits");
        Acquirer second = new Acquirer(controller, freezer);
        second.awaitWaiting();

        scheduler.advance(Duration.ofSeconds(14));
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(State.RESUMING, controller.state());

        scheduler.advance(Duration.ofSeconds(1));
        first.lease();
        second.lease();
        assertFalse(first.sawFrozenContainer);
        assertFalse(second.sawFrozenContainer);
        assertEquals(1, freezer.unpauses.get());
        assertEquals(2, controller.leaseCount());
    }

    @Test
    void connectionDuringPausingCancelsThePause() throws Exception {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        freezer.blockPause();
        Thread pausing = Thread.ofPlatform().daemon().start(() -> scheduler.advance(IDLE));
        freezer.awaitPauseEntered();
        assertEquals(State.PAUSING, controller.state());

        Acquirer client = new Acquirer(controller, freezer);
        client.awaitWaiting();
        freezer.releasePause();
        RdsBackendGate.Lease lease = client.lease();
        pausing.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(client.sawFrozenContainer, "the client must not reach the frozen container");
        assertEquals(State.RUNNING, controller.state());
        assertFalse(freezer.paused);
        assertEquals(1, freezer.unpauses.get());
        assertEquals(List.of(Event.PAUSE_INITIATED, Event.PAUSE_CANCELED), listener.events);
        assertEquals(1, controller.leaseCount());

        lease.close();
        scheduler.advance(IDLE);
        assertEquals(State.PAUSED, controller.state());
    }

    @Test
    void concurrentConnectionsResumeTheContainerOnce() throws Exception {
        AutoPauseController controller = pausedController(Duration.ZERO);
        freezer.blockUnpause();

        List<Acquirer> clients = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            clients.add(new Acquirer(controller, freezer));
        }
        freezer.awaitUnpauseEntered();
        awaitCondition(() -> clients.stream().allMatch(Acquirer::isWaiting), "every client waits");
        freezer.releaseUnpause();
        for (Acquirer client : clients) {
            client.lease();
            assertFalse(client.sawFrozenContainer);
        }

        assertEquals(1, freezer.unpauses.get());
        assertEquals(16, controller.leaseCount());
        assertEquals(1, listener.events.stream().filter(event -> event == Event.RESUMED).count());
    }

    @Test
    void turningAutoPauseOffResumesAPausedContainerAndKeepsItRunning() {
        AutoPauseController controller = pausedController(Duration.ZERO);

        controller.configure(null, listener);
        scheduler.advance(Duration.ZERO);

        assertEquals(State.RUNNING, controller.state());
        assertFalse(freezer.paused);
        scheduler.advance(Duration.ofDays(1));
        assertEquals(State.RUNNING, controller.state());
        assertEquals(1, freezer.pauses.get());
    }

    @Test
    void turningAutoPauseOffWhilePausingCancelsThePause() throws Exception {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        freezer.blockPause();
        Thread pausing = Thread.ofPlatform().daemon().start(() -> scheduler.advance(IDLE));
        freezer.awaitPauseEntered();

        controller.configure(null, listener);
        freezer.releasePause();
        pausing.join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(State.RUNNING, controller.state());
        assertFalse(freezer.paused);
        assertEquals(List.of(Event.PAUSE_INITIATED, Event.PAUSE_CANCELED), listener.events);
        scheduler.advance(Duration.ofDays(1));
        assertEquals(1, freezer.pauses.get());
    }

    @Test
    void turningAutoPauseOffBeforeTheIntervalEndsPreventsThePause() {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        scheduler.advance(Duration.ofSeconds(200));

        controller.configure(null, listener);
        scheduler.advance(Duration.ofDays(1));

        assertEquals(State.RUNNING, controller.state());
        assertEquals(0, freezer.pauses.get());
        assertEquals(0, listener.checks.get());
    }

    @Test
    void changingTheIntervalRestartsTheIdleClock() {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        scheduler.advance(Duration.ofSeconds(200));

        controller.configure(600, listener);
        scheduler.advance(Duration.ofSeconds(599));
        assertEquals(State.RUNNING, controller.state());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(State.PAUSED, controller.state());
    }

    @Test
    void clusterThatMayNotPauseIsAskedAgainAfterAnotherInterval() {
        AutoPauseController controller = controller(Duration.ZERO);
        listener.mayPause = false;
        controller.configure(300, listener);

        scheduler.advance(IDLE);
        assertEquals(State.RUNNING, controller.state());
        assertEquals(1, listener.checks.get());
        assertEquals(0, freezer.pauses.get());

        listener.mayPause = true;
        scheduler.advance(IDLE);
        assertEquals(State.PAUSED, controller.state());
        assertEquals(2, listener.checks.get());
    }

    @Test
    void failedPauseLeavesTheContainerRunningAndTriesAgainLater() {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        freezer.pauseFailure = new IllegalStateException("daemon unavailable");

        scheduler.advance(IDLE);
        assertEquals(State.RUNNING, controller.state());
        assertEquals(List.of(Event.PAUSE_INITIATED, Event.PAUSE_CANCELED), listener.events);

        freezer.pauseFailure = null;
        scheduler.advance(IDLE);
        assertEquals(State.PAUSED, controller.state());
    }

    @Test
    void failedResumeFailsTheConnectionAndLeavesTheContainerPaused() throws Exception {
        AutoPauseController controller = pausedController(Duration.ZERO);
        freezer.unpauseFailure = new IllegalStateException("daemon unavailable");

        assertThrows(IllegalStateException.class, controller::acquire);
        assertEquals(State.PAUSED, controller.state());

        freezer.unpauseFailure = null;
        controller.acquire();
        assertEquals(State.RUNNING, controller.state());
        assertFalse(freezer.paused);
    }

    @Test
    void closeThawsAPausedContainerAndStopsTracking() throws Exception {
        AutoPauseController controller = pausedController(Duration.ZERO);

        controller.close();

        assertFalse(freezer.paused);
        assertSame(RdsBackendGate.Lease.NONE, controller.acquire());
        assertEquals(0, controller.leaseCount());
        scheduler.advance(Duration.ofDays(1));
        assertEquals(1, freezer.pauses.get());
    }

    @Test
    void closeDuringTheResumeDelayReleasesTheWaitingConnection() throws Exception {
        AutoPauseController controller = pausedController(Duration.ofSeconds(15));
        Acquirer client = new Acquirer(controller, freezer);
        awaitCondition(() -> controller.state() == State.RESUMING && client.isWaiting(), "client waits");

        controller.close();

        assertSame(RdsBackendGate.Lease.NONE, client.lease());
        assertFalse(freezer.paused);
    }

    @Test
    void closeWhilePausingThawsTheContainerOnceTheFreezeReturns() throws Exception {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        freezer.blockPause();
        Thread pausing = Thread.ofPlatform().daemon().start(() -> scheduler.advance(IDLE));
        freezer.awaitPauseEntered();

        controller.close();
        freezer.releasePause();
        pausing.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(freezer.paused, "a released container must not stay frozen");
        assertEquals(State.RUNNING, controller.state());
    }

    @Test
    void pausedContainerReportsEveryMinuteUntilItResumes() throws Exception {
        AutoPauseController controller = pausedController(Duration.ZERO);

        scheduler.advance(Duration.ofMinutes(2));
        assertEquals(2, listener.events.stream().filter(event -> event == Event.STILL_PAUSED).count());

        controller.acquire();
        scheduler.advance(Duration.ofMinutes(10));
        assertEquals(2, listener.events.stream().filter(event -> event == Event.STILL_PAUSED).count());
    }

    @Test
    void closingALeaseTwiceCountsOnce() throws Exception {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        RdsBackendGate.Lease first = controller.acquire();
        controller.acquire();

        first.close();
        first.close();

        assertEquals(1, controller.leaseCount());
    }

    @Test
    void pauseIfIdleRunsTheIdleCheckWithoutWaiting() throws Exception {
        AutoPauseController controller = controller(Duration.ZERO);
        controller.configure(300, listener);
        RdsBackendGate.Lease lease = controller.acquire();
        assertFalse(controller.pauseIfIdle(), "a connection is open");

        lease.close();
        assertTrue(controller.pauseIfIdle());
        assertEquals(State.PAUSED, controller.state());
    }

    private AutoPauseController controller(Duration resumeDelay) {
        return new AutoPauseController("container-1", "localhost", 5432, freezer, scheduler,
                Runnable::run, scheduler.clock(), resumeDelay);
    }

    private AutoPauseController pausedController(Duration resumeDelay) {
        AutoPauseController controller = controller(resumeDelay);
        controller.configure(300, listener);
        scheduler.advance(IDLE);
        assertEquals(State.PAUSED, controller.state());
        return controller;
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting until " + description);
            }
            Thread.onSpinWait();
        }
    }

    /** Runs scheduled tasks on whichever thread advances its clock, in the order they fall due. */
    private static final class ManualScheduler implements AutoPauseController.Scheduler {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        private long sequence;
        private final List<Scheduled> tasks = new ArrayList<>();

        @Override
        public synchronized Future<?> schedule(Runnable task, Duration delay) {
            FutureTask<Void> future = new FutureTask<>(task, null);
            tasks.add(new Scheduled(now.plus(delay), sequence++, future));
            return future;
        }

        void advance(Duration duration) {
            Instant target;
            synchronized (this) {
                target = now.plus(duration);
            }
            while (true) {
                Scheduled next;
                synchronized (this) {
                    next = tasks.stream()
                            .filter(task -> !task.due().isAfter(target))
                            .min(Comparator.comparing(Scheduled::due).thenComparingLong(Scheduled::sequence))
                            .orElse(null);
                    if (next == null) {
                        now = target;
                        return;
                    }
                    tasks.remove(next);
                    if (next.due().isAfter(now)) {
                        now = next.due();
                    }
                }
                // Outside the monitor: the task schedules its successors.
                next.future().run();
            }
        }

        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    synchronized (ManualScheduler.this) {
                        return now;
                    }
                }
            };
        }

        private record Scheduled(Instant due, long sequence, FutureTask<Void> future) {
        }
    }

    private static final class FakeFreezer implements AutoPauseController.Freezer {

        final AtomicInteger pauses = new AtomicInteger();
        final AtomicInteger unpauses = new AtomicInteger();
        volatile boolean paused;
        volatile RuntimeException pauseFailure;
        volatile RuntimeException unpauseFailure;
        private volatile CountDownLatch pauseEntered;
        private volatile CountDownLatch pauseRelease;
        private volatile CountDownLatch unpauseEntered;
        private volatile CountDownLatch unpauseRelease;

        @Override
        public void pause(String containerId) {
            pauses.incrementAndGet();
            block(pauseEntered, pauseRelease);
            if (pauseFailure != null) {
                throw pauseFailure;
            }
            paused = true;
        }

        @Override
        public void unpause(String containerId) {
            unpauses.incrementAndGet();
            block(unpauseEntered, unpauseRelease);
            if (unpauseFailure != null) {
                throw unpauseFailure;
            }
            paused = false;
        }

        void blockPause() {
            pauseEntered = new CountDownLatch(1);
            pauseRelease = new CountDownLatch(1);
        }

        void awaitPauseEntered() throws InterruptedException {
            assertTrue(pauseEntered.await(5, TimeUnit.SECONDS), "docker pause was not called");
        }

        void releasePause() {
            pauseRelease.countDown();
        }

        void blockUnpause() {
            unpauseEntered = new CountDownLatch(1);
            unpauseRelease = new CountDownLatch(1);
        }

        void awaitUnpauseEntered() throws InterruptedException {
            assertTrue(unpauseEntered.await(5, TimeUnit.SECONDS), "docker unpause was not called");
        }

        void releaseUnpause() {
            unpauseRelease.countDown();
        }

        private static void block(CountDownLatch entered, CountDownLatch release) {
            if (entered == null) {
                return;
            }
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The test never released the Docker call");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class RecordingListener implements AutoPauseListener {

        final List<Event> events = new CopyOnWriteArrayList<>();
        final List<Instant> times = new CopyOnWriteArrayList<>();
        final AtomicInteger checks = new AtomicInteger();
        volatile boolean mayPause = true;

        @Override
        public boolean mayPause() {
            checks.incrementAndGet();
            return mayPause;
        }

        @Override
        public void onAutoPause(Event event, Instant at) {
            events.add(event);
            times.add(at);
        }
    }

    /** Calls acquire() on a platform thread of its own, so that a test can watch it wait. */
    private static final class Acquirer {

        private final CompletableFuture<RdsBackendGate.Lease> result = new CompletableFuture<>();
        private final Thread thread;
        private volatile boolean sawFrozenContainer;

        Acquirer(AutoPauseController controller, FakeFreezer freezer) {
            thread = Thread.ofPlatform().daemon().start(() -> {
                try {
                    RdsBackendGate.Lease lease = controller.acquire();
                    sawFrozenContainer = freezer.paused;
                    result.complete(lease);
                } catch (InterruptedException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        }

        boolean isWaiting() {
            Thread.State state = thread.getState();
            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        }

        void awaitWaiting() {
            awaitCondition(this::isWaiting, "the client waits");
        }

        boolean isDone() {
            return result.isDone();
        }

        RdsBackendGate.Lease lease() throws Exception {
            return result.get(5, TimeUnit.SECONDS);
        }
    }
}
