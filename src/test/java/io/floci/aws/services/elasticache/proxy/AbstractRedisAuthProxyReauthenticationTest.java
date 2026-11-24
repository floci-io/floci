package io.floci.aws.services.elasticache.proxy;

import io.floci.aws.testing.ParallelLocks;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(ParallelLocks.PORTS)
class AbstractRedisAuthProxyReauthenticationTest {

    private static final long HOUR = TimeUnit.HOURS.toNanos(1);
    /** Each user's accepted secrets; the IAM user takes either of two tokens. */
    private static final Map<String, List<String>> SECRETS = Map.of(
            "app", List.of("app-secret"),
            "reader", List.of("reader-secret"),
            "iam-user", List.of("token-1", "token-2"));

    private final AtomicLong now = new AtomicLong(1_000 * HOUR);
    private ServerSocket backendServer;
    private TestProxy proxy;

    @AfterEach
    void stop() throws IOException {
        if (proxy != null) {
            proxy.stop();
        }
        if (backendServer != null) {
            backendServer.close();
        }
    }

    @Test
    void aSecondAuthIsRelayedWithTheBackendCredentialsOfItsUser() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            send(client, "AUTH", "reader", "reader-secret");

            assertEquals(List.of("AUTH", "backend-reader", "backend-password-reader"), received(backend));
            reply(backend, "+OK\r\n");
            assertEquals("+OK", line(client));
        }
    }

    @Test
    void refusedCredentialsReachTheBackendAsCredentialsItRefusesWithoutTheClientsSecret() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            send(client, "AUTH", "app", "wrong-secret");
            send(client, "AUTH", "unknown-secret");

            for (int attempt = 0; attempt < 2; attempt++) {
                List<String> relayed = received(backend);
                assertEquals("AUTH", relayed.get(0));
                assertEquals(3, relayed.size());
                assertTrue(relayed.get(1).startsWith("floci-refused-"), relayed.get(1));
                assertFalse(relayed.contains("wrong-secret") || relayed.contains("unknown-secret"));
            }
        }
    }

    @Test
    void aHelloThatSignsInAgainKeepsItsOtherOptions() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            send(client, "HELLO", "3", "AUTH", "reader", "reader-secret", "SETNAME", "worker");

            assertEquals(List.of("HELLO", "3", "AUTH", "backend-reader", "backend-password-reader",
                    "SETNAME", "worker"), received(backend));
        }
    }

    @Test
    void aHelloTheBackendRefusesEndsAtTheRefusedArgumentWithoutTheClientsSecret() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            int attempts = proxy.authenticationAttempts.get();
            send(client, "HELLO", "3", "AUTH", "reader", "reader-secret", "BOGUS", "AUTH", "app", "app-secret");
            send(client, "HELLO", "4", "AUTH", "reader", "reader-secret");
            send(client, "HELLO", "3", "SETNAME", "bad name", "AUTH", "reader", "reader-secret");

            List<String> unknownOption = received(backend);
            assertEquals(List.of("HELLO", "3", "AUTH"), unknownOption.subList(0, 3));
            assertTrue(unknownOption.get(3).startsWith("floci-refused-"), unknownOption.get(3));
            assertEquals("BOGUS", unknownOption.getLast());
            assertEquals(6, unknownOption.size());
            assertEquals(List.of("HELLO", "4"), received(backend));
            assertEquals(List.of("HELLO", "3", "SETNAME", "bad name"), received(backend));
            assertEquals(attempts, proxy.authenticationAttempts.get(),
                    "credentials the backend never reaches are not checked");
        }
    }

    @Test
    void anIamUserCannotSignInAgainInsideATransaction() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "iam-user", "token-1")) {
            int attempts = proxy.authenticationAttempts.get();
            send(client, "MULTI");
            send(client, "AUTH", "iam-user", "token-2");
            send(client, "HELLO", "3", "AUTH", "iam-user", "token-2");
            send(client, "EXEC");
            send(client, "AUTH", "iam-user", "token-2");

            assertEquals(List.of("MULTI"), received(backend));
            assertEquals(List.of("MULTI"), received(backend), "the backend refuses MULTI inside a transaction");
            assertEquals(List.of("MULTI"), received(backend));
            assertEquals(List.of("EXEC"), received(backend));
            assertEquals(List.of("AUTH", "backend-iam-user", "backend-password-iam-user"), received(backend));
            assertEquals(attempts + 1, proxy.authenticationAttempts.get());
        }
    }

    @Test
    void aPasswordSignInInsideATransactionIsQueuedLikeAnyCommand() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            send(client, "MULTI");
            send(client, "AUTH", "reader", "reader-secret");

            assertEquals(List.of("MULTI"), received(backend));
            assertEquals(List.of("AUTH", "backend-reader", "backend-password-reader"), received(backend));
        }
    }

    @Test
    void anIamConnectionIsClosed12HoursAfterItSignedIn() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "iam-user", "token-1")) {
            now.addAndGet(12 * HOUR - 1);
            proxy.closeExpiredConnections();
            assertOpen(client, backend);

            now.incrementAndGet();
            proxy.closeExpiredConnections();
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    void aConnectionsOwnWaitClosesItOnceItsIamSignInRunsOut() throws Exception {
        AtomicInteger readings = new AtomicInteger();
        // The connection reads the time when it signs in, and again when it starts waiting: 12 hours on.
        start(true, () -> readings.getAndIncrement() == 0 ? 0 : 12 * HOUR);
        try (Socket client = client()) {
            send(client, "AUTH", "iam-user", "token-1");
            try (Socket backend = backendServer.accept()) {
                assertEquals("+OK", line(client));
                assertEquals(-1, client.getInputStream().read(), "closed while the backend side is still open");
                assertEquals(-1, backend.getInputStream().read());
            }
        }
    }

    @Test
    void signingInAgainWithAFreshTokenGivesTheConnectionAnother12Hours() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "iam-user", "token-1")) {
            now.addAndGet(11 * HOUR);
            send(client, "AUTH", "iam-user", "token-2");
            assertEquals(List.of("AUTH", "backend-iam-user", "backend-password-iam-user"), received(backend));

            now.addAndGet(2 * HOUR);
            proxy.closeExpiredConnections();
            assertOpen(client, backend);

            now.addAndGet(10 * HOUR);
            proxy.closeExpiredConnections();
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    void aConnectionSignedInWithAPasswordIsNeverClosedForItsAge() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            now.addAndGet(100 * HOUR);
            proxy.closeExpiredConnections();
            assertOpen(client, backend);
        }
    }

    @Test
    void anIamConnectionThatSignsInAgainWithAPasswordIsNoLongerClosedForItsAge() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "iam-user", "token-1")) {
            send(client, "AUTH", "app", "app-secret");
            assertEquals(List.of("AUTH", "backend-app", "backend-password-app"), received(backend));

            now.addAndGet(13 * HOUR);
            proxy.closeExpiredConnections();
            assertOpen(client, backend);
        }
    }

    @Test
    void aSignInInsideATransactionLeavesTheIamLifetimeAsItWas() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "iam-user", "token-1")) {
            send(client, "MULTI");
            send(client, "AUTH", "app", "app-secret");
            send(client, "AUTH", "iam-user", "token-2");
            send(client, "EXEC");
            assertEquals(List.of("MULTI"), received(backend));
            assertEquals(List.of("AUTH", "backend-app", "backend-password-app"), received(backend),
                    "queued for the backend, which runs it only if EXEC does not abort");
            assertEquals(List.of("MULTI"), received(backend));
            assertEquals(List.of("EXEC"), received(backend), "this EXEC aborts: the refused MULTI doomed it");

            now.addAndGet(12 * HOUR);
            proxy.closeExpiredConnections();
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    void aTransactionCommandWithArgumentsLeavesTheTransactionAsItWas() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "iam-user", "token-1")) {
            send(client, "MULTI", "extra");
            send(client, "AUTH", "iam-user", "token-2");
            assertEquals(List.of("MULTI", "extra"), received(backend));
            assertEquals(List.of("AUTH", "backend-iam-user", "backend-password-iam-user"), received(backend),
                    "the backend refuses MULTI with an argument, so no transaction began");

            send(client, "MULTI");
            send(client, "EXEC", "extra");
            send(client, "DISCARD", "extra");
            send(client, "RESET", "extra");
            send(client, "AUTH", "iam-user", "token-2");
            assertEquals(List.of("MULTI"), received(backend));
            assertEquals(List.of("EXEC", "extra"), received(backend));
            assertEquals(List.of("DISCARD", "extra"), received(backend));
            assertEquals(List.of("RESET", "extra"), received(backend));
            assertEquals(List.of("MULTI"), received(backend), "the transaction is still open on the backend");
        }
    }

    @Test
    void anInlineSignInIsCheckedLikeAnyOther() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            client.getOutputStream().write("auth reader \"reader-secret\"\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();

            assertEquals(List.of("auth", "backend-reader", "backend-password-reader"), received(backend));
        }
    }

    @Test
    void aSignInTooLargeToCheckReachesTheBackendAsCredentialsItRefuses() throws Exception {
        start(true);
        try (Socket client = client(); Socket backend = signIn(client, "app", "app-secret")) {
            int attempts = proxy.authenticationAttempts.get();
            send(client, "AUTH", "app", "s".repeat(70_000));
            send(client, "PING");

            List<String> relayed = received(backend);
            assertEquals(3, relayed.size());
            assertTrue(relayed.get(1).startsWith("floci-refused-"), relayed.get(1));
            assertEquals(List.of("PING"), received(backend));
            assertEquals(attempts, proxy.authenticationAttempts.get());
        }
    }

    @Test
    void aProxyThatDoesNotCheckCredentialsRelaysAuthAsSent() throws Exception {
        start(false);
        try (Socket client = client()) {
            send(client, "PING");
            try (Socket backend = backendServer.accept()) {
                backend.setSoTimeout(2_000);
                assertEquals(List.of("PING"), received(backend));
                send(client, "AUTH", "app", "app-secret");
                assertEquals(List.of("AUTH", "app", "app-secret"), received(backend));
            }
        }
    }

    private void start(boolean authRequired) throws IOException {
        start(authRequired, now::get);
    }

    private void start(boolean authRequired, LongSupplier nanoTime) throws IOException {
        backendServer = new ServerSocket(0);
        backendServer.setSoTimeout(2_000);
        proxy = new TestProxy(backendServer.getLocalPort(), authRequired, nanoTime);
        proxy.start(0);
    }

    private Socket client() throws IOException {
        Socket client = new Socket("127.0.0.1", proxy.localPort());
        client.setSoTimeout(2_000);
        return client;
    }

    /** Signs the client in and returns the backend connection the proxy opened for it. */
    private Socket signIn(Socket client, String username, String secret) throws IOException {
        send(client, "AUTH", username, secret);
        Socket backend = backendServer.accept();
        backend.setSoTimeout(2_000);
        assertEquals("+OK", line(client));
        // The AUTH reply is sent before the connection records its sign-in time.
        assertOpen(client, backend);
        return backend;
    }

    /** A command and its reply both get through, so neither side has been closed. */
    private static void assertOpen(Socket client, Socket backend) throws IOException {
        send(client, "PING");
        assertEquals(List.of("PING"), received(backend));
        reply(backend, "+PONG\r\n");
        assertEquals("+PONG", line(client));
    }

    private static void send(Socket socket, String... arguments) throws IOException {
        socket.getOutputStream().write(ClientCommandRelay.encode(List.of(arguments)));
        socket.getOutputStream().flush();
    }

    private static void reply(Socket backend, String reply) throws IOException {
        backend.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
        backend.getOutputStream().flush();
    }

    private static List<String> received(Socket backend) throws IOException {
        return List.of(new RespReader(backend.getInputStream()).readCommand());
    }

    private static String line(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\r') {
            line.write(value);
        }
        input.read();
        return line.toString(StandardCharsets.UTF_8);
    }

    private static final class TestProxy extends AbstractRedisAuthProxy {
        private final boolean authRequired;
        private final AtomicInteger authenticationAttempts = new AtomicInteger();

        private TestProxy(int backendPort, boolean authRequired, LongSupplier nanoTime) {
            super(Logger.getLogger(TestProxy.class), "test", "test", "reauthentication", "127.0.0.1",
                    backendPort, 2_000, nanoTime);
            this.authRequired = authRequired;
        }

        @Override
        protected boolean authRequired() {
            return authRequired;
        }

        @Override
        protected boolean authenticate(String username, String password) {
            authenticationAttempts.incrementAndGet();
            return SECRETS.getOrDefault(username == null ? "default" : username, List.of()).contains(password);
        }

        @Override
        protected boolean signsInWithIam(String username) {
            return "iam-user".equals(username);
        }

        @Override
        protected List<String> backendCredentials(String username) {
            return List.of("backend-" + username, "backend-password-" + username);
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
