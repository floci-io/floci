package io.github.hectorvent.floci.services.elasticache.proxy;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractRedisAuthProxyHandshakeTest {

    private static final int SOCKET_TIMEOUT_MILLIS = 2_000;
    private static final String USERNAME = "app";
    private static final String PASSWORD = "secret";

    @Test
    void helloAuthStripsCredentialsBeforeForwardingToBackend() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, true, USERNAME, PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                write(client, respArray("HELLO", "3", "AUTH", USERNAME, PASSWORD,
                        "SETNAME", "test-client"));

                try (Socket backend = acceptBackend(backendServer)) {
                    byte[] expected = respArray("HELLO", "3", "SETNAME", "test-client");
                    assertArrayEquals(expected, backend.getInputStream().readNBytes(expected.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void helloAuthPreservesDefaultUsernameForValidator() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, true, "default", PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                write(client, respArray("HELLO", "2", "AUTH", "default", PASSWORD));

                try (Socket backend = acceptBackend(backendServer)) {
                    byte[] expected = respArray("HELLO", "2");
                    assertArrayEquals(expected, backend.getInputStream().readNBytes(expected.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void invalidClientNameIsRejectedBeforeAuthenticationAndAllowsRetry() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, true, USERNAME, PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                write(client, respArray("HELLO", "3", "AUTH", USERNAME, PASSWORD,
                        "SETNAME", "bad name"));

                assertEquals("-ERR Client names cannot contain spaces, newlines or special characters.\r\n",
                        readLine(client));
                assertEquals(0, proxy.authenticationAttempts());
                backendServer.setSoTimeout(200);
                assertThrows(SocketTimeoutException.class, backendServer::accept);

                write(client, respArray("HELLO", "3", "AUTH", USERNAME, PASSWORD,
                        "SETNAME", "good-name"));
                try (Socket backend = acceptBackend(backendServer)) {
                    byte[] expected = respArray("HELLO", "3", "SETNAME", "good-name");
                    assertArrayEquals(expected, backend.getInputStream().readNBytes(expected.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
                assertEquals(1, proxy.authenticationAttempts());
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void helloSyntaxErrorSanitizesNewlinesAndAllowsRetry() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, true, USERNAME, PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                write(client, respArray("HELLO", "3", "BAD\r\n+INJECTED"));
                assertEquals("-ERR Syntax error in HELLO option 'BAD  +INJECTED'\r\n",
                        readLine(client));

                write(client, respArray("HELLO", "3", "AUTH", USERNAME, PASSWORD));
                try (Socket backend = acceptBackend(backendServer)) {
                    byte[] expected = respArray("HELLO", "3");
                    assertArrayEquals(expected, backend.getInputStream().readNBytes(expected.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void helloWithoutAuthReturnsNoAuthAndAllowsRetry() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, true, USERNAME, PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                write(client, respArray("HELLO", "3"));
                assertEquals("-NOAUTH HELLO must be called with the client already authenticated, "
                        + "otherwise the HELLO <proto> AUTH <user> <pass> option can be used to "
                        + "authenticate the client and select the RESP protocol version at the same time\r\n",
                        readLine(client));

                write(client, respArray("HELLO", "3", "AUTH", USERNAME, PASSWORD));
                try (Socket backend = acceptBackend(backendServer)) {
                    byte[] expected = respArray("HELLO", "3");
                    assertArrayEquals(expected, backend.getInputStream().readNBytes(expected.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void wrongHelloPasswordReturnsWrongPassAndAllowsRetry() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, true, USERNAME, PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                write(client, respArray("HELLO", "3", "AUTH", USERNAME, "wrong"));
                assertEquals("-WRONGPASS invalid username-password pair or user is disabled.\r\n",
                        readLine(client));

                write(client, respArray("HELLO", "3", "AUTH", USERNAME, PASSWORD));
                try (Socket backend = acceptBackend(backendServer)) {
                    byte[] expected = respArray("HELLO", "3");
                    assertArrayEquals(expected, backend.getInputStream().readNBytes(expected.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void helloOnOpenCacheIsForwardedVerbatim() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0)) {
            TestProxy proxy = startProxy(backendServer, false, USERNAME, PASSWORD);
            try (Socket client = openClient(proxy.proxyPort())) {
                byte[] command = respArray("HELLO", "3", "AUTH", "unused", "unused");
                write(client, command);

                try (Socket backend = acceptBackend(backendServer)) {
                    assertArrayEquals(command, backend.getInputStream().readNBytes(command.length));

                    write(backend, "+HELLO\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("+HELLO\r\n", readLine(client));
                }
                assertEquals(0, proxy.authenticationAttempts());
            } finally {
                proxy.stop();
            }
        }
    }

    private static TestProxy startProxy(ServerSocket backendServer, boolean authRequired,
                                        String username, String password) throws IOException {
        int proxyPort;
        try (ServerSocket freePort = new ServerSocket(0)) {
            proxyPort = freePort.getLocalPort();
        }
        TestProxy proxy = new TestProxy("127.0.0.1", backendServer.getLocalPort(), proxyPort,
                authRequired, username, password);
        proxy.start(proxyPort);
        return proxy;
    }

    private static Socket openClient(int port) throws IOException {
        Socket client = new Socket("127.0.0.1", port);
        client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return client;
    }

    private static Socket acceptBackend(ServerSocket backendServer) throws IOException {
        backendServer.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        Socket backend = backendServer.accept();
        backend.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return backend;
    }

    private static void write(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
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

    private static final class TestProxy extends AbstractRedisAuthProxy {
        private static final Logger LOG = Logger.getLogger(TestProxy.class);

        private final int proxyPort;
        private final boolean authRequired;
        private final String expectedUsername;
        private final String expectedPassword;
        private final AtomicInteger authenticationAttempts = new AtomicInteger();

        private TestProxy(String backendHost, int backendPort, int proxyPort,
                          boolean authRequired, String expectedUsername, String expectedPassword) {
            super(LOG, "test", "test", "handshake", backendHost, backendPort);
            this.proxyPort = proxyPort;
            this.authRequired = authRequired;
            this.expectedUsername = expectedUsername;
            this.expectedPassword = expectedPassword;
        }

        private int proxyPort() {
            return proxyPort;
        }

        private int authenticationAttempts() {
            return authenticationAttempts.get();
        }

        @Override
        protected boolean authRequired() {
            return authRequired;
        }

        @Override
        protected boolean authenticate(String username, String password) {
            authenticationAttempts.incrementAndGet();
            return Objects.equals(expectedUsername, username) && expectedPassword.equals(password);
        }

        @Override
        protected void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The peer may already have closed the socket.
            }
        }
    }
}
