package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.SparkConnectTargets.Route;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The servers {@link SessionServers} runs, read from its owner's side: what reaches the owner, in what order, and
 * what a call is answered, as servers start, take calls and stop.
 */
class SessionServersTest {

    private static final String SESSION = "session-1";
    private static final String TOKEN = "a-token";

    private final ServerRunner runner = new ServerRunner();
    private final SessionServers servers = new SessionServers(SessionServers.Kind.SPARK_CONNECT, runner,
            task -> Thread.ofPlatform().daemon().start(task), null, (host, port) -> null, Clock.systemUTC());
    private final RecordingOwner owner = new RecordingOwner();

    @Test
    void aCallIsRefusedWhenItsServerIsStoppedAfterItsOwnerLetItThrough() {
        answeringServer();
        // The session is terminated between the owner's check and the server's.
        owner.refusal = () -> {
            servers.stop(SESSION);
            return null;
        };

        Route route = servers.route(TOKEN, owner).orElseThrow();

        assertEquals(Route.UNAVAILABLE, route.status());
        assertEquals(SessionServers.Kind.SPARK_CONNECT.noRunningServer(SESSION), route.refusal());
    }

    @Test
    void sparkConnectServersRefuseACallInTheWordsTheyAlwaysHad() {
        assertEquals("The authentication token has expired. Call GetSessionEndpoint for a new one.",
                SessionServers.Kind.SPARK_CONNECT.tokenExpired());
        assertEquals("Session " + SESSION + " has no Spark Connect server running.",
                SessionServers.Kind.SPARK_CONNECT.noRunningServer(SESSION));
    }

    @Test
    void aCallsBusyReachesTheOwnerOnlyAfterTheIdleOfTheCallThatEndedBeforeIt() throws Exception {
        answeringServer();
        Route first = servers.route(TOKEN, owner).orElseThrow();
        CountDownLatch idleEntered = new CountDownLatch(1);
        CountDownLatch idleMayEnd = new CountDownLatch(1);
        owner.idleWait = () -> {
            idleEntered.countDown();
            await(idleMayEnd);
        };

        CompletableFuture<Void> ending = CompletableFuture.runAsync(first.ended());
        await(idleEntered);
        CompletableFuture<Route> next = CompletableFuture.supplyAsync(() -> servers.route(TOKEN, owner).orElseThrow());
        // The next call waits for the idle in progress, rather than going BUSY under it.
        assertThrows(TimeoutException.class, () -> next.get(500, TimeUnit.MILLISECONDS));
        idleMayEnd.countDown();
        ending.get(10, TimeUnit.SECONDS);
        next.get(10, TimeUnit.SECONDS);

        assertEquals(List.of("answering", "busy", "idle", "busy"), owner.events);
    }

    @Test
    void aServersEntryGoesWhenItStopsEvenWhereItsOwnerFailsToHearIt() {
        answeringServer();
        owner.stoppedFails = true;

        servers.stop(SESSION);

        awaitTrue(() -> owner.events.contains("gone"));
        assertFalse(servers.runs(SESSION));
        assertTrue(servers.route(TOKEN, owner).isEmpty());
    }

    // A session's server, running and answering, with a token for it.
    private void answeringServer() {
        servers.launch(new SparkJobRunner.Job("test", SESSION, "000000000000", "us-east-1", "emr-7.13.0",
                SparkJobRunner.Engine.SPARK, null, null, null, null, Map.of(), List.of(), null, Duration.ZERO, null,
                null, Map.of(), List.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null), owner);
        awaitTrue(() -> owner.events.contains("answering"));
        servers.grant(TOKEN, SESSION, "000000000000", "us-east-1", Instant.now().plus(Duration.ofHours(1)));
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("not reached in time");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** An owner that records what reaches it, the idle as it ends, and answers as the test sets it to. */
    private static final class RecordingOwner implements SessionServers.Owner {
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile Supplier<String> refusal = () -> null;
        volatile Runnable idleWait = () -> { };
        volatile boolean stoppedFails;
        private volatile boolean answered;

        @Override
        public void running(String sessionId) {
        }

        @Override
        public void answering(String sessionId) {
            answered = true;
            events.add("answering");
        }

        @Override
        public void silent(String sessionId, String why) {
            events.add("silent");
        }

        @Override
        public void stopped(String sessionId, SparkJobRunner.Outcome outcome) {
            if (stoppedFails) {
                throw new IllegalStateException("could not store the session");
            }
            events.add("stopped");
        }

        @Override
        public boolean starting(String sessionId) {
            return !answered;
        }

        @Override
        public boolean going(String sessionId) {
            return true;
        }

        @Override
        public String refusal(String sessionId) {
            return refusal.get();
        }

        @Override
        public void busy(String sessionId) {
            events.add("busy");
        }

        @Override
        public void idle(String sessionId) {
            idleWait.run();
            events.add("idle");
        }

        @Override
        public void gone(String sessionId) {
            events.add("gone");
        }
    }

    /** Runs a server until it is stopped, listening at once. */
    private static final class ServerRunner implements SparkJobRunner {
        private final CompletableFuture<Outcome> end = new CompletableFuture<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            progress.started();
            progress.listening("127.0.0.1", 15002);
            return end.join();
        }

        @Override
        public void stop(String runId) {
            end.complete(Outcome.halted());
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }
    }
}
