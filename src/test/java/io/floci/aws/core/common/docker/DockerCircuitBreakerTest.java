package io.floci.aws.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient.Request;
import com.github.dockerjava.transport.DockerHttpClient.Response;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DockerCircuitBreakerTest {

    private static final Request GET_INFO = Request.builder().method(Request.Method.GET).path("/info").build();

    private DockerCircuitBreaker breaker;

    @AfterEach
    void closeBreaker() {
        if (breaker != null) {
            breaker.close();
        }
    }

    // Catches: a dead daemon still costing every call its own connect, response and retry timeouts.
    @Test
    void opensAfterConsecutiveTransportFailuresAndAFailedPing_thenFailsCallsWithoutTheTransport() {
        breaker = new DockerCircuitBreaker(3, Duration.ofMillis(50));
        AtomicInteger pings = new AtomicInteger();
        breaker.useProbe(() -> {
            pings.incrementAndGet();
            return false;
        }, true);
        CountingTransport transport = CountingTransport.failing(() -> true);
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(transport, breaker);

        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        }
        awaitTrue(breaker::isOpen, "three transport failures and a failed ping should open the breaker");

        RuntimeException failFast = assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        assertInstanceOf(DockerUnavailableException.class, failFast.getCause());
        assertTrue(failFast.getCause().getMessage().contains("Broken pipe"),
                "the fail-fast message should carry the last transport failure: " + failFast.getCause().getMessage());
        assertEquals(3, transport.calls.get(), "an open breaker must not reach the transport");
        assertTrue(pings.get() >= 1);
    }

    // Catches: a burst of broken pipes during a fan-out build taking Docker away from every service.
    @Test
    void staysClosedWhenThePingAnswers() throws InterruptedException {
        breaker = new DockerCircuitBreaker(3, Duration.ofMillis(50));
        AtomicInteger pings = new AtomicInteger();
        breaker.useProbe(() -> {
            pings.incrementAndGet();
            return true;
        }, true);
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(CountingTransport.failing(() -> true), breaker);

        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        }
        awaitTrue(() -> pings.get() == 1, "the third failure should ping the daemon");
        Thread.sleep(200);

        assertFalse(breaker.isOpen(), "a daemon that answers the ping must not open the breaker");
        assertEquals(1, pings.get(), "a closed breaker should not keep pinging");
    }

    // Catches: a daemon that answers with an error status (a 500, a 404) being treated as gone.
    @Test
    void anHttpErrorResponseResetsTheCount() {
        breaker = new DockerCircuitBreaker(2, Duration.ofMillis(50));
        AtomicBoolean pinged = new AtomicBoolean();
        breaker.useProbe(() -> {
            pinged.set(true);
            return false;
        }, true);
        AtomicInteger call = new AtomicInteger();
        CountingTransport transport = new CountingTransport(() -> {
            if (call.incrementAndGet() % 2 == 1) {
                throw new RuntimeException(new SocketException("Broken pipe"));
            }
            return new StatusResponse(500);
        });
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(transport, breaker);

        for (int i = 0; i < 6; i++) {
            try {
                client.execute(GET_INFO).close();
            } catch (RuntimeException expected) {
                // Every other call fails at the transport; the 500 in between proves the daemon is there.
            }
        }

        assertFalse(pinged.get(), "failures interleaved with HTTP responses never reach the threshold");
        assertFalse(breaker.isOpen());
    }

    // Catches: the retry layer replaying the breaker's fail-fast exception for about fifteen seconds.
    @Test
    void theRetryLayerOverAnOpenBreakerMakesOneAttemptAndNeverBacksOff() {
        breaker = new DockerCircuitBreaker(1, Duration.ofMillis(50));
        breaker.useProbe(() -> false, true);
        CountingTransport transport = CountingTransport.failing(() -> true);
        DockerHttpClient breaking = new CircuitBreakingDockerHttpClient(transport, breaker);
        assertThrows(RuntimeException.class, () -> breaking.execute(GET_INFO));
        awaitTrue(breaker::isOpen, "one failure and a failed ping should open a threshold-1 breaker");

        DockerHttpClient retrying = new RetryingDockerHttpClient(breaking, 6, 500L, null, System::nanoTime,
                millis -> fail("an open breaker must not be retried, but the retry layer backed off " + millis + " ms"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> retrying.execute(GET_INFO));
        assertInstanceOf(DockerUnavailableException.class, e.getCause());
        assertEquals(1, transport.calls.get(), "only the call that opened the breaker reached the transport");
    }

    // Catches: a breaker that never closes after the daemon comes back.
    @Test
    void aPingThatAnswersClosesAnOpenBreaker() {
        breaker = new DockerCircuitBreaker(1, Duration.ofMillis(50));
        AtomicBoolean daemonBack = new AtomicBoolean();
        breaker.useProbe(daemonBack::get, true);
        AtomicBoolean failing = new AtomicBoolean(true);
        CountingTransport transport = CountingTransport.failing(failing::get);
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(transport, breaker);
        assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        awaitTrue(breaker::isOpen, "the breaker should open");

        daemonBack.set(true);
        failing.set(false);
        awaitTrue(() -> !breaker.isOpen(), "a ping that answers should close the breaker");

        client.execute(GET_INFO).close();
        assertEquals(2, transport.calls.get(), "calls reach the transport again once the breaker closes");
    }

    // Catches: a missing Docker socket going uncounted, or a full pool or the fail-fast exception being
    // read as a dead daemon.
    @Test
    void countsUnusableSocketsAndTransientIoButNotLeaseWaitsOrItsOwnException() {
        assertTrue(DockerRetry.isDaemonFailure(new RuntimeException(new SocketException("Connection reset"))));
        assertTrue(DockerRetry.isDaemonFailure(new RuntimeException(new NoSuchFileException("/var/run/docker.sock"))));
        assertFalse(DockerRetry.isDaemonFailure(new RuntimeException(new ConnectionRequestTimeoutException("lease"))));
        assertFalse(DockerRetry.isDaemonFailure(new RuntimeException(new DockerUnavailableException("open"))));
        assertFalse(DockerRetry.isTransientIo(new RuntimeException(new DockerUnavailableException("open"))),
                "the retry layer must never replay the breaker's fail-fast exception");
    }

    // Catches: the producer leaving the breaker out of the transport stack, or putting it above the retry
    // layer, where an open breaker would still be retried.
    @Test
    void producerPutsTheBreakerUnderTheRetryLayerOfBothTransports() {
        breaker = new DockerCircuitBreaker(1, Duration.ofMillis(50));
        breaker.useProbe(() -> false, true);
        CountingTransport transport = CountingTransport.failing(() -> true);
        DockerHttpClient control = DockerClientProducer.wrapForRole(transport, "control-plane",
                Duration.ofSeconds(30), breaker);
        assertSame(RetryingDockerHttpClient.class, control.getClass());

        Request ping = Request.builder().method(Request.Method.GET).path("/_ping").build();
        assertThrows(RuntimeException.class, () -> control.execute(ping));
        awaitTrue(breaker::isOpen, "a failure under the control-plane transport should open the breaker");
        int callsWhenOpened = transport.calls.get();

        DockerHttpClient streaming = DockerClientProducer.wrapForRole(transport, "streaming",
                Duration.ofSeconds(30), breaker);
        RuntimeException e = assertThrows(RuntimeException.class, () -> streaming.execute(GET_INFO));
        assertInstanceOf(DockerUnavailableException.class, e.getCause());
        assertEquals(callsWhenOpened, transport.calls.get(), "the shared breaker must also stop the streaming transport");
    }

    private static void awaitTrue(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(message);
            }
            Thread.onSpinWait();
        }
    }

    private static final class CountingTransport implements DockerHttpClient {
        private final Supplier<Response> behaviour;
        final AtomicInteger calls = new AtomicInteger();

        CountingTransport(Supplier<Response> behaviour) {
            this.behaviour = behaviour;
        }

        static CountingTransport failing(BooleanSupplier fails) {
            return new CountingTransport(() -> {
                if (fails.getAsBoolean()) {
                    throw new RuntimeException(new SocketException("Broken pipe"));
                }
                return new StatusResponse(200);
            });
        }

        @Override
        public Response execute(Request request) {
            calls.incrementAndGet();
            return behaviour.get();
        }

        @Override
        public void close() throws IOException {
            // Nothing to release.
        }
    }

    private record StatusResponse(int status) implements Response {
        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return Map.of();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
