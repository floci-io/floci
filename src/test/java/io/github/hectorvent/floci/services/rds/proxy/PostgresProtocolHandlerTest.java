package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresProtocolHandlerTest {

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int STARTUP_PROTOCOL_VERSION = 196608;

    @TempDir
    Path tempDir;

    @Test
    void rejectsStartupMessageAboveTheHandshakeLimitBeforeReadingPayload() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(input);
        out.writeInt(1_048_577);
        out.writeInt(STARTUP_PROTOCOL_VERSION);

        IOException error = assertThrows(IOException.class, () -> PostgresProtocolHandler.authenticate(
                new MemorySocket(input.toByteArray()), mock(Socket.class),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));

        assertEquals("PostgreSQL startup message length exceeds the 1048576 byte limit: 1048577",
                error.getMessage());
    }

    @Test
    void acceptsStartupMessageAtTheHandshakeLimit() throws Exception {
        byte[] startup = new byte[1_048_576];
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(headerBytes);
        out.writeInt(startup.length);
        out.writeInt(STARTUP_PROTOCOL_VERSION);
        byte[] header = headerBytes.toByteArray();
        System.arraycopy(header, 0, startup, 0, header.length);

        MemorySocket client = new MemorySocket(startup);
        assertNull(PostgresProtocolHandler.authenticate(
                client, new MemorySocket(new byte[0]),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));
    }

    @Test
    void rejectsNegativeStartupMessageLength() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(input);
        out.writeInt(-1);
        out.writeInt(STARTUP_PROTOCOL_VERSION);

        IOException error = assertThrows(IOException.class, () -> PostgresProtocolHandler.authenticate(
                new MemorySocket(input.toByteArray()), new MemorySocket(new byte[0]),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));

        assertEquals("PostgreSQL startup message length is below the 8 byte minimum: -1",
                error.getMessage());
    }

    @Test
    void rejectsPasswordMessageBelowItsProtocolMinimum() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(input);
        writeStartup(out, "dbadmin", "postgres");
        out.writeByte('p');
        out.writeInt(4);

        IOException error = assertThrows(IOException.class, () -> PostgresProtocolHandler.authenticate(
                new MemorySocket(input.toByteArray()), new MemorySocket(new byte[0]),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));

        assertEquals("PostgreSQL password message length is below the 5 byte minimum: 4",
                error.getMessage());
    }

    @Test
    void rejectsOversizedBackendAuthenticationMessage() throws Exception {
        ByteArrayOutputStream backendInput = new ByteArrayOutputStream();
        DataOutputStream backendOut = new DataOutputStream(backendInput);
        backendOut.writeByte('R');
        backendOut.writeInt(1_048_577);

        IOException error = assertThrows(IOException.class, () -> PostgresProtocolHandler.authenticate(
                new MemorySocket(startupAndPassword()), new MemorySocket(backendInput.toByteArray()),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));

        assertEquals("PostgreSQL backend authentication message length exceeds the 1048576 byte limit: 1048577",
                error.getMessage());
    }

    @Test
    void rejectsOversizedBackendMessageBeforeBufferingIt() throws Exception {
        ByteArrayOutputStream backendInput = new ByteArrayOutputStream();
        DataOutputStream backendOut = new DataOutputStream(backendInput);
        backendOut.writeByte('R');
        backendOut.writeInt(8);
        backendOut.writeInt(0);
        backendOut.writeByte('Z');
        backendOut.writeInt(1_048_577);

        IOException error = assertThrows(IOException.class, () -> PostgresProtocolHandler.authenticate(
                new MemorySocket(startupAndPassword()), new MemorySocket(backendInput.toByteArray()),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));

        assertEquals("PostgreSQL backend message length exceeds the 1048576 byte limit: 1048577",
                error.getMessage());
    }

    @Test
    void rejectsOversizedSaslContinuationMessage() throws Exception {
        ByteArrayOutputStream backendInput = new ByteArrayOutputStream();
        DataOutputStream backendOut = new DataOutputStream(backendInput);
        backendOut.writeByte('R');
        backendOut.writeInt(8);
        backendOut.writeInt(10);
        backendOut.writeByte('R');
        backendOut.writeInt(1_048_577);

        IOException error = assertThrows(IOException.class, () -> PostgresProtocolHandler.authenticate(
                new MemorySocket(startupAndPassword()), new MemorySocket(backendInput.toByteArray()),
                "dbadmin", "adminpass", "postgres",
                false, testSigV4Validator(), testTlsCertificates(),
                (user, pass) -> true));

        assertEquals("PostgreSQL SASL continue message length exceeds the 1048576 byte limit: 1048577",
                error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "auth_db, postgres, auth_db",
            "'', postgres, postgres",
            "'', '', postgres",
            "auth_db, '', auth_db"
    })
    void resolveEffectiveDbNamePrefersClientDatabase(String clientDb, String instanceDb, String expected) {
        String clientDatabase = clientDb.isEmpty() ? null : clientDb;
        String instanceDatabase = instanceDb.isEmpty() ? null : instanceDb;
        assertEquals(expected, PostgresProtocolHandler.resolveEffectiveDbName(clientDatabase, instanceDatabase));
    }

    @Test
    void forwardsClientDatabaseToBackendStartup() throws Exception {
        AtomicReference<String> backendDatabase = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, backendDatabase, false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
                        if (session != null) {
                            PostgresProtocolHandler.bridge(session, backend);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "dbadmin", "auth_db");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, "adminpass");
                readAuthenticationOk(clientIn);
                readReadyForQuery(clientIn);

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }

            assertEquals("auth_db", backendDatabase.get());
        }
    }

    @Test
    void doesNotSendAuthenticationOkWhenBackendStartupFails() throws Exception {
        AtomicReference<String> backendDatabase = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, backendDatabase, true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
                        if (session != null) {
                            PostgresProtocolHandler.bridge(session, backend);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "dbadmin", "missing_db");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, "adminpass");

                int firstResponse = clientIn.read();
                assertEquals('E', firstResponse);
                assertNotEquals('R', firstResponse);

                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }

            assertEquals("missing_db", backendDatabase.get());
        }
    }

    @Test
    void acceptsPostgresSslRequestAndContinuesStartup() throws Exception {
        AtomicReference<String> backendDatabase = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, backendDatabase, false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
                        if (session != null) {
                            PostgresProtocolHandler.bridge(session, backend);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeSslRequest(clientOut);
                assertEquals('S', clientIn.readUnsignedByte());

                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                clientOut = new DataOutputStream(sslClient.getOutputStream());
                clientIn = new DataInputStream(sslClient.getInputStream());

                writeStartup(clientOut, "dbadmin", "auth_db");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, "adminpass");
                readAuthenticationOk(clientIn);
                readReadyForQuery(clientIn);

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }

            assertEquals("auth_db", backendDatabase.get());
        }
    }

    @Test
    void hostnameVerificationSucceedsForRegisteredAdvertisedHost() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
            String advertisedHost = "172.17.0.5";
            tlsCertificates.ensureHost(advertisedHost);

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, new AtomicReference<>(), false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> true);
                        if (session != null) {
                            PostgresProtocolHandler.bridge(session, backend);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeSslRequest(clientOut);
                assertEquals('S', clientIn.readUnsignedByte());

                Path caCertFile = tempDir.resolve("tls").resolve("rds-ca.crt");
                SSLSocket sslClient = verifyingClientSocket(ourClient, caCertFile, advertisedHost);
                sslClient.startHandshake(); // must not throw — advertisedHost is in the cert's SAN

                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
            }
        }
    }

    @Test
    void hostnameVerificationFailsForUnregisteredAdvertisedHost() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
            tlsCertificates.ensureHost("172.17.0.5");

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, new AtomicReference<>(), false);
                } catch (IOException e) {
                    // Backend connection is torn down before completing startup — expected here.
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> true);
                        if (session != null) {
                            PostgresProtocolHandler.bridge(session, backend);
                        }
                    } catch (IOException ignored) {
                        // Client aborts the handshake below — the handler observing that is expected.
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeSslRequest(clientOut);
                assertEquals('S', clientIn.readUnsignedByte());

                Path caCertFile = tempDir.resolve("tls").resolve("rds-ca.crt");
                SSLSocket sslClient = verifyingClientSocket(ourClient, caCertFile, "never-registered.example.com");
                assertThrows(SSLHandshakeException.class, sslClient::startHandshake);

                proxyClient.close();
                authThread.join(5_000);
            }
        }
    }

    @Test
    void closesClientWhenBackendClosesDuringBridge() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendClosesDuringBridge(backendServer);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
                        if (session != null) {
                            PostgresProtocolHandler.bridge(session, backend);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "dbadmin", "auth_db");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, "adminpass");
                readAuthenticationOk(clientIn);
                readReadyForQuery(clientIn);

                writeSimpleQuery(clientOut, "select 1");

                assertEquals(-1, clientIn.read(), "backend close must be visible to the client");
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }
    }

    @Test
    void iamSessionRunsAsTheRoleNamedInTheToken() throws Exception {
        AtomicReference<String> backendUser = new AtomicReference<>();
        AtomicReference<String> backendQuery = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendRoleSwitch(backendServer, backendUser, backendQuery, "approle", true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = startIamAuth(proxyClient, backend);

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "approle", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, rdsToken("approle"));
                readAuthenticationOk(clientIn);

                Map<String, String> params = readParametersUntilReadyForQuery(clientIn);
                assertEquals("approle", params.get("session_authorization"));
                assertEquals("off", params.get("is_superuser"));

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }

            assertEquals("dbadmin", backendUser.get(), "backend connection is still opened as master");
            assertEquals("SET SESSION AUTHORIZATION \"approle\"", backendQuery.get());
        }
    }

    @Test
    void iamSessionIsRejectedWhenTheTokenRoleDoesNotExist() throws Exception {
        AtomicReference<String> backendUser = new AtomicReference<>();
        AtomicReference<String> backendQuery = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendRoleSwitch(backendServer, backendUser, backendQuery, "nosuchrole", false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = startIamAuth(proxyClient, backend);

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "nosuchrole", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, rdsToken("nosuchrole"));

                Map<Character, String> error = readErrorResponse(clientIn);
                assertEquals("FATAL", error.get('S'));
                assertEquals("28000", error.get('C'));
                assertEquals("role \"nosuchrole\" does not exist", error.get('M'));

                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }

            assertEquals("SET SESSION AUTHORIZATION \"nosuchrole\"", backendQuery.get());
        }
    }

    @Test
    void iamSessionForTheMasterUserKeepsTheMasterSession() throws Exception {
        AtomicReference<String> backendDatabase = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, backendDatabase, false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = startIamAuth(proxyClient, backend);

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "dbadmin", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, rdsToken("dbadmin"));
                // The mock backend answers no query, so reaching ReadyForQuery proves no
                // SET SESSION AUTHORIZATION was issued for the master user.
                readAuthenticationOk(clientIn);
                readReadyForQuery(clientIn);

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }
    }

    @Test
    void iamSessionIsTerminatedWhenPostgresReportsAnotherSessionRole() throws Exception {
        AtomicReference<String> backendQuery = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendRoleSwitch(backendServer, new AtomicReference<>(), backendQuery,
                            "approle", true, (in, out) -> {
                                readSimpleQuery(in);
                                // What PostgreSQL reports after RESET SESSION AUTHORIZATION and
                                // friends hand the session back to the connection's master role.
                                writeParameterStatus(out, "session_authorization", "masteruser");
                                writeParameterStatus(out, "is_superuser", "on");
                                writeCommandComplete(out, "RESET");
                                writeReadyForQuery(out);
                            });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = startIamAuth(proxyClient, backend);

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "approle", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, rdsToken("approle"));
                readAuthenticationOk(clientIn);
                readParametersUntilReadyForQuery(clientIn);

                writeSimpleQuery(clientOut, "RESET SESSION AUTHORIZATION");

                Map<Character, String> error = readErrorResponse(clientIn);
                assertEquals("FATAL", error.get('S'));
                assertEquals("42501", error.get('C'));
                assertEquals("permission denied to set session authorization", error.get('M'));
                assertEquals(-1, clientIn.read(), "session must be closed, not handed back as master");

                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
            }
        }
    }

    @Test
    void iamSessionKeepsRelayingWhenTheReportedRoleIsUnchanged() throws Exception {
        byte[] wideRow = new byte[40_000];
        java.util.Arrays.fill(wideRow, (byte) 'x');

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendRoleSwitch(backendServer, new AtomicReference<>(),
                            new AtomicReference<>(), "approle", true, (in, out) -> {
                                readSimpleQuery(in);
                                writeParameterStatus(out, "session_authorization", "approle");
                                writeMessage(out, 'D', wideRow);
                                writeCommandComplete(out, "SELECT 1");
                                writeReadyForQuery(out);
                            });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = startIamAuth(proxyClient, backend);

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "approle", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, rdsToken("approle"));
                readAuthenticationOk(clientIn);
                readParametersUntilReadyForQuery(clientIn);

                writeSimpleQuery(clientOut, "SET SESSION AUTHORIZATION approle");

                assertEquals('S', clientIn.readByte());
                clientIn.readNBytes(clientIn.readInt() - 4);
                assertEquals('D', clientIn.readByte());
                // A message larger than the relay buffer must arrive whole.
                assertEquals(wideRow.length, clientIn.readInt() - 4);
                assertEquals(wideRow.length, clientIn.readNBytes(wideRow.length).length);
                assertEquals('C', clientIn.readByte());
                clientIn.readNBytes(clientIn.readInt() - 4);
                readReadyForQuery(clientIn);

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
            }
        }
    }

    @Test
    void nonIamSessionRelaysSessionAuthorizationChanges() throws Exception {
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            int backendPort = backendServer.getLocalPort();
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendMasterSession(backendServer, (in, out) -> {
                        readSimpleQuery(in);
                        writeParameterStatus(out, "session_authorization", "approle");
                        writeCommandComplete(out, "SET");
                        writeReadyForQuery(out);
                    });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Socket proxyClient;
            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendPort);

                Thread authThread = startIamAuth(proxyClient, backend);

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeStartup(clientOut, "dbadmin", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, "adminpass");
                readAuthenticationOk(clientIn);
                readReadyForQuery(clientIn);

                // The master session is the backend's own identity, so switching roles is its
                // business. The guard applies only to IAM sessions.
                writeSimpleQuery(clientOut, "SET SESSION AUTHORIZATION approle");
                Map<String, String> params = readParametersUntilReadyForQuery(clientIn);
                assertEquals("approle", params.get("session_authorization"));

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
            }
        }
    }

    @Test
    void quotesRoleNamesContainingDoubleQuotes() {
        assertEquals("\"app\"\"role\"", PostgresProtocolHandler.quoteIdentifier("app\"role"));
    }

    private Thread startIamAuth(Socket proxyClient, Socket backend) {
        return Thread.ofVirtual().start(() -> {
            try {
                PostgresProtocolHandler.AuthenticatedSession session =
                        PostgresProtocolHandler.authenticate(
                        proxyClient, backend,
                        "dbadmin", "adminpass", "postgres",
                        true, testSigV4Validator(), testTlsCertificates(),
                        (user, pass) -> true);
                if (session != null) {
                    PostgresProtocolHandler.bridge(session, backend);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String rdsToken(String dbUser) throws Exception {
        return SigV4TokenTestHelper.createRdsToken("localhost", 7001, dbUser,
                "AKIATEST", "secret", Instant.now(), 900);
    }

    /**
     * Mock backend that completes startup as master and then answers one simple query: either
     * reporting the handover to {@code role}, or replying with the ErrorResponse PostgreSQL sends
     * for a role the database does not have.
     */
    private static void mockBackendRoleSwitch(ServerSocket server, AtomicReference<String> backendUser,
                                              AtomicReference<String> backendQuery,
                                              String role, boolean roleExists) throws IOException {
        mockBackendRoleSwitch(server, backendUser, backendQuery, role, roleExists, null);
    }

    private static void mockBackendRoleSwitch(ServerSocket server, AtomicReference<String> backendUser,
                                              AtomicReference<String> backendQuery,
                                              String role, boolean roleExists,
                                              BackendScript afterHandover) throws IOException {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            int length = in.readInt();
            assertEquals(STARTUP_PROTOCOL_VERSION, in.readInt());
            byte[] payload = in.readNBytes(length - 8);
            backendUser.set(parseStartupParams(payload).get("user"));

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(3);
            out.flush();

            assertEquals('p', in.readByte());
            in.readNBytes(in.readInt() - 4);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(0);
            writeParameterStatus(out, "session_authorization", "dbadmin");
            writeParameterStatus(out, "is_superuser", "on");
            writeBackendKeyData(out);
            writeReadyForQuery(out);

            assertEquals('Q', in.readByte());
            byte[] query = in.readNBytes(in.readInt() - 4);
            backendQuery.set(new String(query, 0, query.length - 1, StandardCharsets.UTF_8));

            if (!roleExists) {
                writeErrorResponse(out, "ERROR", "42704", "role \"" + role + "\" does not exist");
                writeReadyForQuery(out);
                return;
            }

            writeParameterStatus(out, "session_authorization", role);
            writeParameterStatus(out, "is_superuser", "off");
            writeCommandComplete(out, "SET");
            writeReadyForQuery(out);

            if (afterHandover != null) {
                afterHandover.run(in, out);
            }
        }
    }

    /** Drives the backend side of a bridged session, once the client is past authentication. */
    @FunctionalInterface
    private interface BackendScript {
        void run(DataInputStream in, DataOutputStream out) throws IOException;
    }

    /** Backend that completes a plain master startup and then runs {@code script}. */
    private static void mockBackendMasterSession(ServerSocket server, BackendScript script)
            throws IOException {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            int length = in.readInt();
            assertEquals(STARTUP_PROTOCOL_VERSION, in.readInt());
            in.readNBytes(length - 8);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(3);
            out.flush();

            assertEquals('p', in.readByte());
            in.readNBytes(in.readInt() - 4);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(0);
            writeReadyForQuery(out);

            script.run(in, out);
        }
    }

    private static String readSimpleQuery(DataInputStream in) throws IOException {
        assertEquals('Q', in.readByte());
        byte[] query = in.readNBytes(in.readInt() - 4);
        return new String(query, 0, query.length - 1, StandardCharsets.UTF_8);
    }

    private static void writeMessage(DataOutputStream out, char type, byte[] payload)
            throws IOException {
        out.writeByte(type);
        out.writeInt(4 + payload.length);
        out.write(payload);
        out.flush();
    }

    private static void writeParameterStatus(DataOutputStream out, String name, String value)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte('S');
        out.writeInt(4 + nameBytes.length + 1 + valueBytes.length + 1);
        out.write(nameBytes);
        out.writeByte(0);
        out.write(valueBytes);
        out.writeByte(0);
        out.flush();
    }

    private static void writeBackendKeyData(DataOutputStream out) throws IOException {
        out.writeByte('K');
        out.writeInt(12);
        out.writeInt(4242);
        out.writeInt(1234);
        out.flush();
    }

    private static void writeCommandComplete(DataOutputStream out, String tag) throws IOException {
        byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
        out.writeByte('C');
        out.writeInt(4 + tagBytes.length + 1);
        out.write(tagBytes);
        out.writeByte(0);
        out.flush();
    }

    private static void writeReadyForQuery(DataOutputStream out) throws IOException {
        out.writeByte('Z');
        out.writeInt(5);
        out.writeByte('I');
        out.flush();
    }

    /** Collects ParameterStatus values the client is handed, stopping at ReadyForQuery. */
    private static Map<String, String> readParametersUntilReadyForQuery(DataInputStream in)
            throws IOException {
        Map<String, String> params = new HashMap<>();
        while (true) {
            int type = in.readByte();
            byte[] payload = in.readNBytes(in.readInt() - 4);
            if (type == 'Z') {
                return params;
            }
            if (type == 'S') {
                int nameEnd = 0;
                while (payload[nameEnd] != 0) {
                    nameEnd++;
                }
                int valueEnd = nameEnd + 1;
                while (payload[valueEnd] != 0) {
                    valueEnd++;
                }
                params.put(new String(payload, 0, nameEnd, StandardCharsets.UTF_8),
                        new String(payload, nameEnd + 1, valueEnd - nameEnd - 1, StandardCharsets.UTF_8));
            }
        }
    }

    private static Map<Character, String> readErrorResponse(DataInputStream in) throws IOException {
        assertEquals('E', in.readByte());
        byte[] payload = in.readNBytes(in.readInt() - 4);
        Map<Character, String> fields = new HashMap<>();
        int i = 0;
        while (i < payload.length && payload[i] != 0) {
            char fieldType = (char) payload[i];
            i++;
            int start = i;
            while (payload[i] != 0) {
                i++;
            }
            fields.put(fieldType, new String(payload, start, i - start, StandardCharsets.UTF_8));
            i++;
        }
        return fields;
    }

    private static RdsSigV4Validator testSigV4Validator() {
        return new RdsSigV4Validator(IamServiceTestHelper.iamServiceWithAccessKey("AKIATEST", "secret"));
    }

    private RdsProxyTlsCertificates testTlsCertificates() {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.storage()).thenReturn(storage);
        return new RdsProxyTlsCertificates(config, new CertificateGenerator());
    }

    /**
     * Wraps {@code socket} in a client-side {@link SSLSocket} that trusts only {@code caCertFile}
     * and enforces standard HTTPS-style hostname verification against {@code advertisedHost} —
     * the same SAN-matching behavior libpq performs for {@code sslmode=verify-full}.
     */
    private static SSLSocket verifyingClientSocket(Socket socket, Path caCertFile, String advertisedHost)
            throws Exception {
        X509Certificate ca;
        try (var in = Files.newInputStream(caCertFile)) {
            ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("rds-ca", ca);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);

        SSLSocket sslSocket = (SSLSocket) context.getSocketFactory()
                .createSocket(socket, advertisedHost, socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        SSLParameters params = sslSocket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(params);
        return sslSocket;
    }

    private static void mockBackendStartup(ServerSocket server, AtomicReference<String> backendDatabase,
                                           boolean failWithError) throws IOException {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            int length = in.readInt();
            int proto = in.readInt();
            assertEquals(STARTUP_PROTOCOL_VERSION, proto);
            byte[] payload = in.readNBytes(length - 8);
            backendDatabase.set(parseStartupParams(payload).get("database"));

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(3);
            out.flush();

            assertEquals('p', in.readByte());
            int pwLength = in.readInt();
            in.readNBytes(pwLength - 4);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(0);
            out.flush();

            if (failWithError) {
                writeErrorResponse(out, "FATAL", "3D000", "database \"missing_db\" does not exist");
            } else {
                out.writeByte('Z');
                out.writeInt(5);
                out.writeByte('I');
                out.flush();
            }
        }
    }

    private static void mockBackendClosesDuringBridge(ServerSocket server) throws IOException {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            int length = in.readInt();
            int proto = in.readInt();
            assertEquals(STARTUP_PROTOCOL_VERSION, proto);
            in.readNBytes(length - 8);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(3);
            out.flush();

            assertEquals('p', in.readByte());
            int pwLength = in.readInt();
            in.readNBytes(pwLength - 4);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(0);
            out.writeByte('Z');
            out.writeInt(5);
            out.writeByte('I');
            out.flush();

            assertEquals('Q', in.readByte());
            int queryLength = in.readInt();
            in.readNBytes(queryLength - 4);
        }
    }

    private static void writeStartup(DataOutputStream out, String user, String database) throws IOException {
        byte[] userKey = "user".getBytes(StandardCharsets.UTF_8);
        byte[] userVal = user.getBytes(StandardCharsets.UTF_8);
        byte[] dbKey = "database".getBytes(StandardCharsets.UTF_8);
        byte[] dbVal = database.getBytes(StandardCharsets.UTF_8);

        int length = 4 + 4
                + userKey.length + 1 + userVal.length + 1
                + dbKey.length + 1 + dbVal.length + 1
                + 1;

        out.writeInt(length);
        out.writeInt(STARTUP_PROTOCOL_VERSION);
        out.write(userKey);
        out.writeByte(0);
        out.write(userVal);
        out.writeByte(0);
        out.write(dbKey);
        out.writeByte(0);
        out.write(dbVal);
        out.writeByte(0);
        out.writeByte(0);
        out.flush();
    }

    private static void writeSslRequest(DataOutputStream out) throws IOException {
        out.writeInt(8);
        out.writeInt(SSL_REQUEST_CODE);
        out.flush();
    }

    private static SSLSocket trustedClientSocket(Socket socket) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        SSLSocket sslSocket = (SSLSocket) context.getSocketFactory()
                .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        return sslSocket;
    }

    private static void writePassword(DataOutputStream out, String password) throws IOException {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        out.writeByte('p');
        out.writeInt(4 + pw.length + 1);
        out.write(pw);
        out.writeByte(0);
        out.flush();
    }

    private static void writeSimpleQuery(DataOutputStream out, String query) throws IOException {
        byte[] queryBytes = query.getBytes(StandardCharsets.UTF_8);
        out.writeByte('Q');
        out.writeInt(4 + queryBytes.length + 1);
        out.write(queryBytes);
        out.writeByte(0);
        out.flush();
    }

    private static void readCleartextPasswordChallenge(DataInputStream in) throws IOException {
        assertEquals('R', in.readByte());
        assertEquals(8, in.readInt());
        assertEquals(3, in.readInt());
    }

    private static void readAuthenticationOk(DataInputStream in) throws IOException {
        assertEquals('R', in.readByte());
        assertEquals(8, in.readInt());
        assertEquals(0, in.readInt());
    }

    private static void readReadyForQuery(DataInputStream in) throws IOException {
        assertEquals('Z', in.readByte());
        assertEquals(5, in.readInt());
        assertEquals('I', in.readByte());
    }

    private static byte[] startupAndPassword() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(input);
        writeStartup(out, "dbadmin", "postgres");
        writePassword(out, "adminpass");
        return input.toByteArray();
    }

    private static void writeErrorResponse(DataOutputStream out, String severity, String sqlState,
                                           String message) throws IOException {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        fields.write('S');
        fields.write(severity.getBytes(StandardCharsets.UTF_8));
        fields.write(0);
        fields.write('C');
        fields.write(sqlState.getBytes(StandardCharsets.UTF_8));
        fields.write(0);
        fields.write('M');
        fields.write(message.getBytes(StandardCharsets.UTF_8));
        fields.write(0);
        fields.write(0);

        byte[] payload = fields.toByteArray();
        out.writeByte('E');
        out.writeInt(4 + payload.length);
        out.write(payload);
        out.flush();
    }

    private static Map<String, String> parseStartupParams(byte[] data) {
        Map<String, String> params = new HashMap<>();
        int i = 0;
        while (i < data.length) {
            int keyStart = i;
            while (i < data.length && data[i] != 0) {
                i++;
            }
            if (i >= data.length) {
                break;
            }
            String key = new String(data, keyStart, i - keyStart, StandardCharsets.UTF_8);
            i++;
            if (key.isEmpty()) {
                break;
            }
            int valStart = i;
            while (i < data.length && data[i] != 0) {
                i++;
            }
            String value = new String(data, valStart, i - valStart, StandardCharsets.UTF_8);
            i++;
            params.put(key, value);
        }
        return params;
    }

    private static final class MemorySocket extends Socket {
        private final ByteArrayInputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private MemorySocket(byte[] input) {
            this.input = new ByteArrayInputStream(input);
        }

        @Override
        public ByteArrayInputStream getInputStream() {
            return input;
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return output;
        }
    }

}
