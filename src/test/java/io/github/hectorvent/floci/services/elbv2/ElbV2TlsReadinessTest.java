package io.github.hectorvent.floci.services.elbv2;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ElbV2TlsReadinessTest {

    @Test
    void connectionRefusalBeforeBindDoesNotEndReadiness() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            int port = unusedLoopbackPort();
            AtomicInteger refusals = new AtomicInteger();
            ElbV2TlsReadiness.await(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
                    try (Socket accepted = listener.accept()) {
                        accepted.getOutputStream().write(42);
                        socket.setSoTimeout(500);
                        assertEquals(42, socket.getInputStream().read());
                    }
                } catch (ConnectException refusal) {
                    assertEquals(1, refusals.incrementAndGet());
                    listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    listener.setSoTimeout(500);
                    throw refusal;
                }
            });
            assertEquals(1, refusals.get());
        }
    }

    @Test
    void permanentlyStoppedListenerStillTimesOut() throws Exception {
        int port = unusedLoopbackPort();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ConditionTimeoutException.class, () -> ElbV2TlsReadiness.await(
                Duration.ofMillis(350), () -> {
                    attempts.incrementAndGet();
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
                        fail("Stopped listener unexpectedly accepted a connection");
                    }
                }));
        assertTrue(attempts.get() > 1);
    }

    @Test
    void wrongResponseStillTimesOut() {
        assertThrows(ConditionTimeoutException.class, () -> ElbV2TlsReadiness.await(
                Duration.ofMillis(350), () -> assertEquals("winner", "wrong listener")));
    }

    @Test
    void transientHandshakeFailureRetainsAcceptedRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ElbV2TlsReadiness.await(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new SSLHandshakeException("TLS material not installed yet");
            }
        });
        assertEquals(2, attempts.get());
    }

    @Test
    void unrelatedIoFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        IOException failure = new IOException("Unexpected protocol failure");
        assertSame(failure, assertThrows(IOException.class, () -> ElbV2TlsReadiness.await(() -> {
            attempts.incrementAndGet();
            throw failure;
        })));
        assertEquals(1, attempts.get());
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return reservation.getLocalPort();
        }
    }
}
