package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.testutil.FreePorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractRedisAuthProxyRelayTest {

    @Test
    void closingBackendReleasesClientConnection() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.close();
                }

                client.setSoTimeout(2_000);
                assertEquals(-1, client.getInputStream().read());
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void clientHalfCloseStillAllowsBackendResponse() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(2_000);
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                client.shutdownOutput();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(2_000);
                    while (backend.getInputStream().read() != -1) {
                        // Drain the request before asserting that the half-close reached the backend.
                    }
                    backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    byte[] response = client.getInputStream().readNBytes(8);
                    assertEquals("response", new String(response, StandardCharsets.US_ASCII));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void clientHalfCloseClosesRelayWhenBackendStaysOpenAfterResponse() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(3_000);
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                client.shutdownOutput();
                try (Socket backend = backendServer.accept()) {
                    while (backend.getInputStream().read() != -1) {
                        // Drain until the client half-close reaches the backend.
                    }
                    backend.getOutputStream().write("response".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    assertEquals("response", new String(client.getInputStream().readNBytes(8),
                            StandardCharsets.US_ASCII));
                    assertEquals(-1, client.getInputStream().read());
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void keepsHealthyIdleConnectionOpenBeyondRelayShutdownDeadline() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(5_000);
                byte[] request = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
                client.getOutputStream().write(request);
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(5_000);
                    assertEquals(new String(request, StandardCharsets.US_ASCII),
                            new String(backend.getInputStream().readNBytes(request.length),
                                    StandardCharsets.US_ASCII));
                    Thread.sleep(2_200);

                    backend.getOutputStream().write("PONG".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();
                    assertEquals("PONG", new String(client.getInputStream().readNBytes(4),
                            StandardCharsets.US_ASCII));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void bindsWithABurstTolerantAcceptBacklog() throws Exception {
        AtomicInteger acceptBacklog = new AtomicInteger();
        TestProxy proxy = new TestProxy("127.0.0.1", 1) {
            @Override
            ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
                acceptBacklog.set(backlog);
                return super.listenSocket(proxyPort, backlog);
            }
        };
        proxy.start(0);
        try {
            assertEquals(4096, acceptBacklog.get(),
                    "a connection burst must not overflow the proxy's accept queue");
        } finally {
            proxy.stop();
        }
    }

    @Test
    void liveConnectionReauthWithAuthInterceptedByProxy() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort(), false, "iam-user", "valid-token");
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(2_000);
                // Complete handshake with PING
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(2_000);
                    byte[] ping = backend.getInputStream().readNBytes(14);
                    assertEquals("*1\r\n$4\r\nPING\r\n", new String(ping, StandardCharsets.US_ASCII));
                    backend.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    byte[] pong = client.getInputStream().readNBytes(7);
                    assertEquals("+PONG\r\n", new String(pong, StandardCharsets.US_ASCII));

                    // Live connection re-auth with AUTH command
                    byte[] authCmd = "*3\r\n$4\r\nAUTH\r\n$8\r\niam-user\r\n$11\r\nvalid-token\r\n".getBytes(StandardCharsets.US_ASCII);
                    client.getOutputStream().write(authCmd);
                    client.getOutputStream().flush();

                    // Must receive +OK\r\n without AUTH reaching backend
                    String authResponse = readLine(client);
                    assertEquals("+OK\r\n", authResponse);

                    // Verify backend did not receive AUTH by sending another command
                    client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();

                    byte[] nextBackendCmd = backend.getInputStream().readNBytes(14);
                    assertEquals("*1\r\n$4\r\nPING\r\n", new String(nextBackendCmd, StandardCharsets.US_ASCII));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void liveConnectionFailedReauthReceivesError() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort(), false, "iam-user", "valid-token");
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(2_000);
                // Handshake with PING
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(2_000);
                    byte[] ping = backend.getInputStream().readNBytes(14);
                    assertEquals("*1\r\n$4\r\nPING\r\n", new String(ping, StandardCharsets.US_ASCII));
                    backend.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    byte[] pong = client.getInputStream().readNBytes(7);
                    assertEquals("+PONG\r\n", new String(pong, StandardCharsets.US_ASCII));

                    // Send invalid AUTH
                    byte[] authCmd = "*3\r\n$4\r\nAUTH\r\n$8\r\niam-user\r\n$10\r\nwrong-pass\r\n".getBytes(StandardCharsets.US_ASCII);
                    client.getOutputStream().write(authCmd);
                    client.getOutputStream().flush();

                    String authResponse = readLine(client);
                    assertTrue(authResponse.startsWith("-ERR invalid username-password pair or user is disabled.")
                                    || authResponse.startsWith("-WRONGPASS invalid username-password pair or user is disabled."),
                            "Expected invalid credentials error response, got: " + authResponse);
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void liveConnectionHelloReauthStripsAuthBeforeBackend() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort(), false, "iam-user", "valid-token");
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(2_000);
                // Handshake with initial PING
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(2_000);
                    byte[] ping = backend.getInputStream().readNBytes(14);
                    assertEquals("*1\r\n$4\r\nPING\r\n", new String(ping, StandardCharsets.US_ASCII));
                    backend.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    byte[] pong = client.getInputStream().readNBytes(7);
                    assertEquals("+PONG\r\n", new String(pong, StandardCharsets.US_ASCII));

                    // Re-authenticate via HELLO 3 AUTH <user> <pass>
                    byte[] helloAuth = respArray("HELLO", "3", "AUTH", "iam-user", "valid-token");
                    client.getOutputStream().write(helloAuth);
                    client.getOutputStream().flush();

                    // Backend must receive HELLO 3 without AUTH
                    byte[] expectedBackend = respArray("HELLO", "3");
                    byte[] actualBackend = backend.getInputStream().readNBytes(expectedBackend.length);
                    assertArrayEquals(expectedBackend, actualBackend);

                    // Backend responds to HELLO 3
                    backend.getOutputStream().write("+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();

                    String clientResponse = readLine(client);
                    assertEquals("+HELLO\r\n", clientResponse);
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void binaryDataPreservedThroughRelay() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            int proxyPort = FreePorts.anyInterfacePort();
            TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort());
            proxy.start(proxyPort);

            try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                client.setSoTimeout(2_000);
                // Handshake with initial PING
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();

                try (Socket backend = backendServer.accept()) {
                    backend.setSoTimeout(2_000);
                    byte[] ping = backend.getInputStream().readNBytes(14);
                    assertEquals("*1\r\n$4\r\nPING\r\n", new String(ping, StandardCharsets.US_ASCII));
                    backend.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().flush();
                    byte[] pong = client.getInputStream().readNBytes(7);
                    assertEquals("+PONG\r\n", new String(pong, StandardCharsets.US_ASCII));

                    // Send binary payload: all byte values 0..255
                    byte[] binaryData = new byte[256];
                    for (int i = 0; i < 256; i++) {
                        binaryData[i] = (byte) i;
                    }

                    // Build command: *3\r\n$3\r\nSET\r\n$7\r\nbin-key\r\n$256\r\n<bytes>\r\n
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    bout.write("*3\r\n$3\r\nSET\r\n$7\r\nbin-key\r\n$256\r\n".getBytes(StandardCharsets.US_ASCII));
                    bout.write(binaryData);
                    bout.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] commandBytes = bout.toByteArray();

                    client.getOutputStream().write(commandBytes);
                    client.getOutputStream().flush();

                    byte[] backendReceived = backend.getInputStream().readNBytes(commandBytes.length);
                    assertArrayEquals(commandBytes, backendReceived);

                    // Send binary payload back from backend to client
                    ByteArrayOutputStream responseBout = new ByteArrayOutputStream();
                    responseBout.write("$256\r\n".getBytes(StandardCharsets.US_ASCII));
                    responseBout.write(binaryData);
                    responseBout.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] responseBytes = responseBout.toByteArray();

                    backend.getOutputStream().write(responseBytes);
                    backend.getOutputStream().flush();

                    byte[] clientReceived = client.getInputStream().readNBytes(responseBytes.length);
                    assertArrayEquals(responseBytes, clientReceived);
                }
            } finally {
                proxy.stop();
            }
        }
    }

    private static String readLine(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        StringBuilder line = new StringBuilder();
        int current;
        while ((current = input.read()) != -1) {
            line.append((char) current);
            if (line.length() >= 2
                    && line.charAt(line.length() - 2) == '\r'
                    && line.charAt(line.length() - 1) == '\n') {
                return line.toString();
            }
        }
        return line.toString();
    }

    private static byte[] respArray(String... parts) {
        StringBuilder command = new StringBuilder();
        command.append('*').append(parts.length).append("\r\n");
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            command.append('$').append(bytes.length).append("\r\n");
            command.append(part).append("\r\n");
        }
        return command.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static class TestProxy extends AbstractRedisAuthProxy {
        private final boolean authRequired;
        private final String expectedUsername;
        private final String expectedPassword;
        private TestProxy(String backendHost, int backendPort) {
            this(backendHost, backendPort, false, null, null);
        }

        private TestProxy(String backendHost, int backendPort, boolean authRequired,
                          String expectedUsername, String expectedPassword) {
            super(org.jboss.logging.Logger.getLogger(TestProxy.class), "test", "test", "relay",
                    backendHost, backendPort);
            this.authRequired = authRequired;
            this.expectedUsername = expectedUsername;
            this.expectedPassword = expectedPassword;
        }

        @Override
        protected boolean authRequired() {
            return authRequired;
        }

        @Override
        protected boolean authenticate(String username, String password) {
            if (expectedUsername == null && expectedPassword == null) {
                return true;
            }
            return Objects.equals(expectedUsername, username)
                    && Objects.equals(expectedPassword, password);
        }

        @Override
        protected void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The peer may already be closed.
            }
        }
    }
}
