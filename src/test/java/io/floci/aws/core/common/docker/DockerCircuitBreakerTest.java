package io.floci.aws.core.common.docker;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient.Request;
import com.github.dockerjava.transport.DockerHttpClient.Response;
import io.floci.aws.core.common.docker.DockerCircuitBreaker.PingResult;
import io.floci.aws.testing.ParallelLocks;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
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
            return PingResult.UNREACHABLE;
        });
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
    void staysClosedWhenThePingAnswers() {
        breaker = new DockerCircuitBreaker(3, Duration.ofMillis(50));
        AtomicInteger pings = new AtomicInteger();
        breaker.useProbe(() -> {
            pings.incrementAndGet();
            return PingResult.ANSWERED;
        });
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(CountingTransport.failing(() -> true), breaker);

        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        }
        awaitTrue(() -> breaker.probesRun() == 1, "the third failure should ping the daemon");

        assertFalse(breaker.isOpen(), "a daemon that answers the ping must not open the breaker");
        assertFalse(breaker.isProbeScheduled(), "a closed breaker should not schedule another ping");
        assertEquals(1, pings.get());
    }

    // Catches: a daemon that answers its ping with an error status (a 500, a 503) being taken for gone.
    @Test
    void aPingAnsweredWithAnErrorStatusKeepsTheBreakerClosed() {
        DockerCircuitBreaker.Probe ping = DockerCircuitBreaker.pingOver(
                new CountingTransport(() -> new StatusResponse(500)));
        assertEquals(PingResult.ANSWERED, ping.ping());

        breaker = new DockerCircuitBreaker(1, Duration.ofMillis(50));
        breaker.useProbe(ping);
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(CountingTransport.failing(() -> true), breaker);
        assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        awaitTrue(() -> breaker.probesRun() == 1, "the failure should ping the daemon");

        assertFalse(breaker.isOpen(), "an HTTP status from the ping is an answer");
    }

    // Catches: a ping that only waited for a pooled connection, during the fan-out the ping is there
    // for, opening the breaker over a healthy daemon.
    @Test
    void aPingThatOnlyWaitsForAConnectionNeitherOpensNorStopsLaterPings() {
        DockerCircuitBreaker.Probe ping = DockerCircuitBreaker.pingOver(new CountingTransport(() -> {
            throw new RuntimeException(new ConnectionRequestTimeoutException("lease"));
        }));
        assertEquals(PingResult.INCONCLUSIVE, ping.ping());
        assertEquals(PingResult.UNREACHABLE,
                DockerCircuitBreaker.pingOver(CountingTransport.failing(() -> true)).ping());

        breaker = new DockerCircuitBreaker(1, Duration.ofMillis(50));
        breaker.useProbe(ping);
        DockerHttpClient client = new CircuitBreakingDockerHttpClient(CountingTransport.failing(() -> true), breaker);
        assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        awaitTrue(() -> breaker.probesRun() == 1, "the failure should ping the daemon");
        assertFalse(breaker.isOpen(), "a ping that never reached the daemon must not open the breaker");

        assertThrows(RuntimeException.class, () -> client.execute(GET_INFO));
        awaitTrue(() -> breaker.probesRun() == 2, "the next transport failure should ping again");
    }

    // Catches: the confirming ping waiting out the API timeout (300 s by default) on a daemon that
    // accepts the connection and never answers, while every call keeps entering the transport.
    @Test
    void thePingTransportGivesUpOnASilentDaemonWithinItsTimeout() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("tcp://127.0.0.1:" + silent.getLocalPort())
                    .withDockerTlsVerify(false)
                    .build();
            try (FlociDockerHttpClient transport = DockerClientProducer.newPingTransport(config, Duration.ofMillis(300))) {
                DockerCircuitBreaker.Probe ping = DockerCircuitBreaker.pingOver(transport);
                PingResult result = assertTimeoutPreemptively(Duration.ofSeconds(10), ping::ping,
                        "the ping must give up after its own timeout, not the API timeout");
                assertEquals(PingResult.UNREACHABLE, result);
            }
        }
    }

    // Catches: a daemon that answers with an error status (a 500, a 404) being treated as gone.
    @Test
    void anHttpErrorResponseResetsTheCount() {
        breaker = new DockerCircuitBreaker(2, Duration.ofMillis(50));
        AtomicBoolean pinged = new AtomicBoolean();
        breaker.useProbe(() -> {
            pinged.set(true);
            return PingResult.UNREACHABLE;
        });
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
        breaker.useProbe(() -> PingResult.UNREACHABLE);
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
        breaker.useProbe(() -> daemonBack.get() ? PingResult.ANSWERED : PingResult.UNREACHABLE);
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
        breaker.useProbe(() -> PingResult.UNREACHABLE);
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
