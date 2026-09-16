package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlProtocolHandlerTest {

    private static final int CLIENT_PROTOCOL_41 = 0x0200;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_PLUGIN_AUTH = 0x0008_0000;
    private static final String MYSQL_NATIVE_PASSWORD = "mysql_native_password";
    private static final String MYSQL_CLEAR_PASSWORD = "mysql_clear_password";
    private static final int CLIENT_CONNECT_WITH_DB = 0x0008;
    private static final int CLIENT_CONNECT_ATTRS = 0x0010_0000;
    private static final int CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x0020_0000;
    private static final int CLIENT_ZSTD_COMPRESSION_ALGORITHM = 0x0400_0000;
    /** What libmysqlclient 8 sends: every optional field after the auth-response is present. */
    private static final int FULL_RESPONSE_CAPABILITIES = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION
            | CLIENT_CONNECT_WITH_DB | CLIENT_PLUGIN_AUTH | CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
            | CLIENT_CONNECT_ATTRS | CLIENT_ZSTD_COMPRESSION_ALGORITHM;
    private static final int ER_ACCESS_DENIED = 1045;
    private static final byte[] OK_PAYLOAD = {0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00};
    private static final byte[] MASTER_BACKEND_NONCE =
            "second-backend-nonce".getBytes(StandardCharsets.US_ASCII);

    /** Fails a login that opens a second backend connection; only an IAM auth switch may. */
    private static final PostgresProtocolHandler.BackendConnector NO_RECONNECT = () -> {
        throw new IOException("unexpected second backend connection");
    };

    @TempDir
    Path tempDir;

    @Test
    void forwardsPlaintextHandshakeResponseUnmodifiedWhenClientDoesNotRequestTls() throws Exception {
        byte[] nonce = fixedNonce();
        AtomicReference<Byte> backendResponseSeq = new AtomicReference<>();
        AtomicReference<byte[]> backendResponsePayload = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockMySqlBackend(backendServer, nonce, backendResponseSeq, backendResponsePayload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();

                byte[] serverHandshake = readMysqlPacketRaw(clientIn);
                assertNotNull(serverHandshake);
                // The proxy forces CLIENT_SSL even though the mock backend didn't advertise it.
                assertTrue((capabilityFlagsLower(serverHandshake) & CLIENT_SSL) != 0);

                byte[] scramble = scrambleNativePassword("secret", nonce);
                byte[] response = buildHandshakeResponse41("admin", scramble, (byte) 1);
                clientOut.write(response);
                clientOut.flush();

                // Wait for the backend's OK packet to come back through the bridge before tearing
                // down sockets, so we don't race the proxy mid-relay.
                assertNotNull(readMysqlPacketRaw(clientIn));

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }

        assertEquals((byte) 1, backendResponseSeq.get(), "sequence must be unchanged when no TLS upgrade occurs");
        assertEquals("admin", extractUsername(backendResponsePayload.get()));
    }

    @Test
    void validatesCachingSha2MasterScrambleAndForwardsResponse() throws Exception {
        byte[] nonce = fixedNonce();
        AtomicReference<Byte> backendResponseSeq = new AtomicReference<>();
        AtomicReference<byte[]> backendResponsePayload = new AtomicReference<>();

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {
            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockMySqlBackend(
                            backendServer, nonce, "caching_sha2_password",
                            backendResponseSeq, backendResponsePayload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());
                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();
                assertNotNull(readMysqlPacketRaw(clientIn));
                byte[] scramble = HexFormat.of().parseHex(
                        "746ebe205d56a0707acb3e796e834e0dd7b1d61743b26bd5202c7a623230c7c9");
                clientOut.write(buildHandshakeResponse41("admin", scramble, (byte) 1));
                clientOut.flush();
                assertNotNull(readMysqlPacketRaw(clientIn));

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }

        assertEquals((byte) 1, backendResponseSeq.get());
        assertEquals("admin", extractUsername(backendResponsePayload.get()));
    }

    @Test
    void upgradesToTlsOnSslRequestAndRenumbersSequenceBeforeForwardingToBackend() throws Exception {
        byte[] nonce = fixedNonce();
        AtomicReference<Byte> backendResponseSeq = new AtomicReference<>();
        AtomicReference<byte[]> backendResponsePayload = new AtomicReference<>();
        RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
        tlsCertificates.ensureHost("172.17.0.6");

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockMySqlBackend(backendServer, nonce, backendResponseSeq, backendResponsePayload);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();

                byte[] serverHandshake = readMysqlPacketRaw(clientIn);
                assertNotNull(serverHandshake);
                assertTrue((capabilityFlagsLower(serverHandshake) & CLIENT_SSL) != 0);

                // SSLRequest: 32-byte payload, CLIENT_SSL set, sequence 1 (right after the server's
                // handshake at sequence 0).
                clientOut.write(buildSslRequest());
                clientOut.flush();

                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                InputStream tlsIn = sslClient.getInputStream();
                OutputStream tlsOut = sslClient.getOutputStream();

                byte[] scramble = scrambleNativePassword("secret", nonce);
                // Real clients send this at sequence 2 after the TLS upgrade — the proxy must
                // rewrite it to 1 before forwarding to the (plaintext-only) backend. They also
                // keep CLIENT_SSL set in it; a real backend would treat a first packet carrying
                // that bit as an SSLRequest and hang waiting for a TLS handshake.
                byte[] response = buildHandshakeResponse41("admin", scramble, (byte) 2,
                        CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_SSL);
                tlsOut.write(response);
                tlsOut.flush();

                // The mock backend replies with sequence 2 (its own view of the shared counter);
                // the client, having sent its SSLRequest at sequence 1, expects that reply at 3.
                byte[] okPacket = readMysqlPacketRaw(tlsIn);
                assertNotNull(okPacket);
                assertEquals((byte) 3, okPacket[3],
                        "client sent SSLRequest(1) then response(2), so it expects the backend's "
                                + "reply at sequence 3, not the backend's own sequence 2");

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertEquals(false, backendThread.isAlive(), "backendThread did not terminate");
            }
        }

        assertEquals((byte) 1, backendResponseSeq.get(),
                "backend never saw the SSLRequest, so it must receive the real response at sequence 1");
        assertEquals("admin", extractUsername(backendResponsePayload.get()));
        int backendCaps = (backendResponsePayload.get()[0] & 0xFF)
                | ((backendResponsePayload.get()[1] & 0xFF) << 8);
        assertEquals(0, backendCaps & CLIENT_SSL,
                "the plaintext backend must not see CLIENT_SSL, or it treats the response as an "
                        + "SSLRequest and waits forever for a TLS handshake");
    }

    @Test
    void fastAuthSuccessDoesNotBlockWaitingForAClientReply() throws Exception {
        byte[] nonce = fixedNonce();
        RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
        tlsCertificates.ensureHost("172.17.0.7");

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = backendServer.accept()) {
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    out.write(buildHandshakeV10(nonce));
                    out.flush();

                    readMysqlPacketRaw(in); // the real HandshakeResponse41, forwarded at sequence 1

                    // caching_sha2_password fast-auth success: AuthMoreData(0x03) immediately
                    // followed by the terminal OK — the client sends nothing back in between.
                    writeMysqlPacket(out, 2, new byte[]{0x01, 0x03});
                    writeMysqlPacket(out, 3, new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00});
                    out.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();

                assertNotNull(readMysqlPacketRaw(clientIn));

                clientOut.write(buildSslRequest());
                clientOut.flush();

                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                InputStream tlsIn = sslClient.getInputStream();
                OutputStream tlsOut = sslClient.getOutputStream();

                byte[] scramble = scrambleNativePassword("secret", nonce);
                byte[] response = buildHandshakeResponse41("admin", scramble, (byte) 2,
                        CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_SSL);
                tlsOut.write(response);
                tlsOut.flush();

                // Neither packet should require anything further from the client — if the proxy
                // wrongly waits for a client reply after AuthMoreData(0x03), this blocks until the
                // join timeout below fails the test.
                byte[] authMoreData = readMysqlPacketRaw(tlsIn);
                assertNotNull(authMoreData);
                assertEquals((byte) 0x01, authMoreData[4], "expected AuthMoreData");

                byte[] okPacket = readMysqlPacketRaw(tlsIn);
                assertNotNull(okPacket);
                assertEquals((byte) 0x00, okPacket[4], "expected the terminal OK packet");

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
    void silentBackendHandshakeFailsWithinTheConfiguredTimeoutInsteadOfHanging() throws Exception {
        try (ServerSocket silentBackend = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", silentBackend.getLocalPort());

                AtomicReference<IOException> authFailure = new AtomicReference<>();
                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 200);
                    } catch (IOException e) {
                        authFailure.set(e);
                    }
                });

                // The silent backend accepts the TCP connection but never sends its Handshake V10;
                // the backend-side handshake read deadline must fire well within the 5s join instead
                // of hanging forever.
                authThread.join(5_000);
                assertEquals(false, authThread.isAlive(), "authThread did not terminate");
                assertNotNull(authFailure.get(),
                        "expected handleAuth to fail once the backend stayed silent");

                ourClient.close();
                proxyClient.close();
            }
        }
    }

    // ── RDS IAM authentication ───────────────────────────────────────────────

    @Test
    void switchesIamLoginToClearPasswordAndAuthenticatesBackendAsMaster() throws Exception {
        AuthSwitchLogin login = authSwitchLogin("app", rdsToken("app", Instant.now()), true, true);

        assertNotNull(login.authSwitch(), "an access-denied backend verdict must become an auth switch");
        assertEquals(3, login.authSwitch()[3] & 0xFF);
        assertEquals(MYSQL_CLEAR_PASSWORD, authSwitchPluginName(login.authSwitch()));
        assertOk(login.clientVerdict(), 5);
        assertEquals(1, login.reconnects());
        // The master leg keeps the client's database, attributes and zstd level, in wire order.
        assertArrayEquals(
                fullHandshakeResponse41("admin", scrambleNativePassword("secret", MASTER_BACKEND_NONCE),
                        MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1),
                login.masterResponse());
    }

    @Test
    void iamAuthSwitchKeepsSequenceNumbersInStepAfterTlsUpgrade() throws Exception {
        AuthSwitchLogin login = authSwitchLogin("app", rdsToken("app", Instant.now()), true, true);

        // SSLRequest(1) and HandshakeResponse(2) leave the client one packet ahead of the backend.
        assertNotNull(login.authSwitch());
        assertEquals(3, login.authSwitch()[3] & 0xFF);
        assertOk(login.clientVerdict(), 5);
        assertArrayEquals(
                fullHandshakeResponse41("admin", scrambleNativePassword("secret", MASTER_BACKEND_NONCE),
                        MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1),
                login.masterResponse(),
                "the fresh backend connection expects the response at sequence 1 without CLIENT_SSL");
    }

    @Test
    void rejectsIamTokenWithTamperedSignature() throws Exception {
        String token = rdsToken("app", Instant.now());
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0");

        assertIamLoginRejected(authSwitchLogin("app", tampered, true, false));
    }

    @Test
    void rejectsExpiredIamToken() throws Exception {
        String expired = rdsToken("app", Instant.now().minusSeconds(3600));

        assertIamLoginRejected(authSwitchLogin("app", expired, true, false));
    }

    @Test
    void rejectsIamTokenIssuedForAnotherDbUser() throws Exception {
        String otherUser = rdsToken("reporting", Instant.now());

        assertIamLoginRejected(authSwitchLogin("app", otherUser, true, false));
    }

    @Test
    void iamDisabledRelaysBackendRejectionWithoutAuthSwitch() throws Exception {
        AuthSwitchLogin login = authSwitchLogin("app", rdsToken("app", Instant.now()), false, false);

        assertNull(login.authSwitch(), "the proxy must not ask for an IAM token when IAM auth is disabled");
        assertAccessDenied(login.clientVerdict(), 2);
        assertEquals(0, login.reconnects());
    }

    @Test
    void validatesIamTokenSentUpFrontAfterTheDatabaseAndAuthenticatesBackendAsMaster() throws Exception {
        byte[] response = fullHandshakeResponse41("app", nulTerminated(rdsToken("app", Instant.now())),
                MYSQL_CLEAR_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, true);

        assertAccessDenied(login.clientVerdict(), 2);
        assertNull(login.backendResponse(), "clear-password IAM must require TLS");
    }

    @Test
    void iamDisabledForwardsClearPasswordLoginToBackendUnmodified() throws Exception {
        byte[] response = fullHandshakeResponse41("app", nulTerminated(rdsToken("app", Instant.now())),
                MYSQL_CLEAR_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, false);

        assertOk(login.clientVerdict(), 2);
        assertArrayEquals(response, login.backendResponse(),
                "without IAM auth the backend, not the proxy, decides on a non-master login");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forwardsNonMasterNativePasswordLoginUnmodified(boolean iamEnabled) throws Exception {
        byte[] response = fullHandshakeResponse41("app", scrambleNativePassword("app-password", fixedNonce()),
                MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, iamEnabled);

        assertOk(login.clientVerdict(), 2);
        assertArrayEquals(response, login.backendResponse());
    }

    @Test
    void forwardsMasterPasswordLoginUnmodifiedWhenIamEnabled() throws Exception {
        byte[] response = fullHandshakeResponse41("admin", scrambleNativePassword("secret", fixedNonce()),
                MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, true);

        assertOk(login.clientVerdict(), 2);
        assertArrayEquals(response, login.backendResponse());
    }

    @Test
    void rejectsWrongMasterPasswordWithoutAuthSwitchWhenIamEnabled() throws Exception {
        byte[] response = fullHandshakeResponse41("admin", scrambleNativePassword("wrong", fixedNonce()),
                MYSQL_NATIVE_PASSWORD, FULL_RESPONSE_CAPABILITIES, 1);

        DirectLogin login = directLogin(response, true);

        assertAccessDenied(login.clientVerdict(), 2);
        assertNull(login.backendResponse(), "a rejected master login must never reach the backend");
    }

    private static String rdsToken(String dbUser, Instant issuedAt) throws Exception {
        return SigV4TokenTestHelper.createRdsToken("mydb.abc123.us-east-1.rds.amazonaws.com", 3306,
                dbUser, "AKIATEST", "secret", issuedAt, 900);
    }

    private static void assertIamLoginRejected(AuthSwitchLogin login) {
        assertNull(login.authSwitch(), "invalid or non-TLS IAM must not receive an auth switch");
        assertAccessDenied(login.clientVerdict(), 2);
        assertEquals(0, login.reconnects(), "an invalid token must never reach the backend as master");
        assertNull(login.masterResponse());
    }

    private static void assertOk(byte[] packet, int sequence) {
        assertNotNull(packet);
        assertEquals(0x00, packet[4] & 0xFF, "expected an OK packet");
        assertEquals(sequence, packet[3] & 0xFF);
    }

    private static void assertAccessDenied(byte[] packet, int sequence) {
        assertNotNull(packet);
        assertEquals(0xFF, packet[4] & 0xFF, "expected an ERR packet");
        assertEquals(ER_ACCESS_DENIED, (packet[5] & 0xFF) | ((packet[6] & 0xFF) << 8));
        assertEquals(sequence, packet[3] & 0xFF);
    }

    private record DirectLogin(byte[] clientVerdict, byte[] backendResponse) {}

    /** One client HandshakeResponse against a single backend connection that accepts it. */
    private DirectLogin directLogin(byte[] clientResponse, boolean iamEnabled) throws Exception {
        AtomicReference<byte[]> backendResponse = new AtomicReference<>();
        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0);
             Socket ourClient = new Socket("localhost", clientServer.getLocalPort());
             Socket proxyClient = clientServer.accept();
             Socket backend = new Socket("localhost", backendServer.getLocalPort())) {
            ourClient.setSoTimeout(5_000);
            Thread backendThread = startBackend(backendServer, fixedNonce(), OK_PAYLOAD, backendResponse);
            Thread authThread = startProxy(proxyClient, backend, NO_RECONNECT, iamEnabled,
                    testTlsCertificates());

            InputStream clientIn = ourClient.getInputStream();
            OutputStream clientOut = ourClient.getOutputStream();
            assertNotNull(readMysqlPacketRaw(clientIn));
            clientOut.write(clientResponse);
            clientOut.flush();
            byte[] verdict = readMysqlPacketRaw(clientIn);

            ourClient.close();
            proxyClient.close();
            joinAll(authThread, backendThread);
            return new DirectLogin(verdict, backendResponse.get());
        }
    }

    private record AuthSwitchLogin(byte[] authSwitch, byte[] clientVerdict, byte[] masterResponse,
                                   int reconnects) {}

    /**
     * Drives a cleartext-enabled client the way libmysqlclient, PyMySQL, mysql2 and Connector/J log in
     * with an IAM token: the HandshakeResponse answers the advertised mysql_native_password with a
     * scramble of the token, and the token itself is only sent if the server switches to
     * mysql_clear_password. The first backend connection rejects that scramble — as the real backend
     * does for an IAM user, whose password hash never matches — and a second one, if the proxy opens
     * it, accepts whatever it receives.
     */
    private AuthSwitchLogin authSwitchLogin(String username, String token, boolean iamEnabled, boolean tls)
            throws Exception {
        AtomicReference<byte[]> rejectedResponse = new AtomicReference<>();
        AtomicReference<byte[]> masterResponse = new AtomicReference<>();
        AtomicReference<Thread> masterBackendThread = new AtomicReference<>();
        AtomicInteger reconnects = new AtomicInteger();
        RdsProxyTlsCertificates tlsCertificates = testTlsCertificates();
        if (tls) {
            tlsCertificates.ensureHost("172.17.0.8");
        }

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket masterBackendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0);
             Socket ourClient = new Socket("localhost", clientServer.getLocalPort());
             Socket proxyClient = clientServer.accept();
             Socket backend = new Socket("localhost", backendServer.getLocalPort())) {
            ourClient.setSoTimeout(5_000);
            PostgresProtocolHandler.BackendConnector connector = () -> {
                reconnects.incrementAndGet();
                masterBackendThread.set(startBackend(
                        masterBackendServer, MASTER_BACKEND_NONCE, OK_PAYLOAD, masterResponse));
                return new Socket("localhost", masterBackendServer.getLocalPort());
            };
            Thread backendThread = startBackend(
                    backendServer, fixedNonce(), accessDeniedPayload(), rejectedResponse);
            Thread authThread = startProxy(proxyClient, backend, connector, iamEnabled, tlsCertificates);

            InputStream clientIn = ourClient.getInputStream();
            OutputStream clientOut = ourClient.getOutputStream();
            assertNotNull(readMysqlPacketRaw(clientIn));
            int capabilities = FULL_RESPONSE_CAPABILITIES;
            int sequence = 1;
            if (tls) {
                clientOut.write(buildSslRequest());
                clientOut.flush();
                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                clientIn = sslClient.getInputStream();
                clientOut = sslClient.getOutputStream();
                capabilities |= CLIENT_SSL;
                sequence = 2;
            }
            clientOut.write(fullHandshakeResponse41(username, scrambleNativePassword(token, fixedNonce()),
                    MYSQL_NATIVE_PASSWORD, capabilities, sequence));
            clientOut.flush();

            byte[] reply = readMysqlPacketRaw(clientIn);
            byte[] authSwitch = null;
            if (reply != null && (reply[4] & 0xFF) == 0xFE) {
                authSwitch = reply;
                clientOut.write(wrapPacket((reply[3] & 0xFF) + 1, nulTerminated(token)));
                clientOut.flush();
                reply = readMysqlPacketRaw(clientIn);
            }

            ourClient.close();
            proxyClient.close();
            joinAll(authThread, backendThread);
            if (masterBackendThread.get() != null) {
                joinAll(masterBackendThread.get());
            }
            return new AuthSwitchLogin(authSwitch, reply, masterResponse.get(), reconnects.get());
        }
    }

    /**
     * Backend that sends its handshake, records the HandshakeResponse it receives (null if the proxy
     * never forwards one), answers it with {@code verdict} at sequence 2, then closes.
     */
    private static Thread startBackend(ServerSocket server, byte[] nonce, byte[] verdict,
                                       AtomicReference<byte[]> response) {
        return Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                OutputStream out = socket.getOutputStream();
                out.write(buildHandshakeV10(nonce, MYSQL_NATIVE_PASSWORD));
                out.flush();
                byte[] raw = readMysqlPacketRaw(socket.getInputStream());
                response.set(raw);
                if (raw != null) {
                    writeMysqlPacket(out, 2, verdict);
                    out.flush();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static Thread startProxy(Socket proxyClient, Socket backend,
                                     PostgresProtocolHandler.BackendConnector connector,
                                     boolean iamEnabled, RdsProxyTlsCertificates tlsCertificates) {
        return Thread.ofVirtual().start(() -> {
            try {
                MySqlProtocolHandler.handleAuth(
                        proxyClient, backend, connector, "admin", "secret",
                        iamEnabled, testSigV4Validator(), tlsCertificates,
                        (user, pass) -> PasswordValidator.AuthResult.PASSTHROUGH, 5000);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void joinAll(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(5_000);
            assertFalse(thread.isAlive(), "thread did not terminate");
        }
    }

    private RdsProxyTlsCertificates testTlsCertificates() {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.storage()).thenReturn(storage);
        return new RdsProxyTlsCertificates(config, new CertificateGenerator());
    }

    private static RdsSigV4Validator testSigV4Validator() {
        return new RdsSigV4Validator(IamServiceTestHelper.iamServiceWithAccessKey("AKIATEST", "secret"));
    }

    private static SSLSocket trustedClientSocket(Socket socket) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
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

    private static void mockMySqlBackend(ServerSocket server, byte[] nonce,
                                         AtomicReference<Byte> responseSeq,
                                         AtomicReference<byte[]> responsePayload) throws IOException {
        mockMySqlBackend(server, nonce, null, responseSeq, responsePayload);
    }

    private static void mockMySqlBackend(ServerSocket server, byte[] nonce, String authPlugin,
                                         AtomicReference<Byte> responseSeq,
                                         AtomicReference<byte[]> responsePayload) throws IOException {
        try (Socket socket = server.accept()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(buildHandshakeV10(nonce, authPlugin));
            out.flush();

            byte[] responseRaw = readMysqlPacketRaw(in);
            responseSeq.set(responseRaw[3]);
            byte[] payload = new byte[responseRaw.length - 4];
            System.arraycopy(responseRaw, 4, payload, 0, payload.length);
            responsePayload.set(payload);

            writeMysqlPacket(out, 2, new byte[]{0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00}); // OK packet
            out.flush();
        }
    }

    // ── MySQL Handshake V10 (server -> client) ──────────────────────────────

    private static byte[] buildHandshakeV10(byte[] nonce) throws IOException {
        return buildHandshakeV10(nonce, null);
    }

    private static byte[] buildHandshakeV10(byte[] nonce, String authPlugin) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(10); // protocol version
        payload.write("8.0.34".getBytes(StandardCharsets.UTF_8));
        payload.write(0);
        writeInt32LE(payload, 1); // connection id
        payload.write(nonce, 0, 8); // auth-plugin-data part 1
        payload.write(0); // filler
        int capabilities = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION;
        if (authPlugin != null) {
            capabilities |= CLIENT_PLUGIN_AUTH;
        }
        int capsLower = capabilities & 0xFFFF; // no CLIENT_SSL
        writeInt16LE(payload, capsLower);
        payload.write(0x21); // charset
        writeInt16LE(payload, 0x0002); // status flags
        writeInt16LE(payload, (capabilities >>> 16) & 0xFFFF);
        payload.write(authPlugin == null ? 0 : 21);
        payload.write(new byte[10]); // reserved
        payload.write(nonce, 8, 12); // auth-plugin-data part 2 (12 bytes)
        payload.write(0); // null terminator
        if (authPlugin != null) {
            payload.write(authPlugin.getBytes(StandardCharsets.UTF_8));
            payload.write(0);
        }

        return wrapPacket(0, payload.toByteArray());
    }

    // ── MySQL SSLRequest / HandshakeResponse41 (client -> server) ───────────

    private static byte[] buildSslRequest() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_SSL);
        writeInt32LE(payload, 0); // max packet size
        payload.write(0x21); // charset
        payload.write(new byte[23]); // reserved
        return wrapPacket(1, payload.toByteArray());
    }

    private static byte[] buildHandshakeResponse41(String username, byte[] scramble, byte sequence)
            throws IOException {
        return buildHandshakeResponse41(username, scramble, sequence,
                CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION);
    }

    private static byte[] buildHandshakeResponse41(String username, byte[] scramble, byte sequence,
                                                   int capabilities) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, capabilities);
        writeInt32LE(payload, 0); // max packet size
        payload.write(0x21); // charset
        payload.write(new byte[23]); // reserved
        payload.write(username.getBytes(StandardCharsets.UTF_8));
        payload.write(0);
        payload.write(scramble.length); // CLIENT_SECURE_CONNECTION: 1-byte length prefix
        payload.write(scramble);

        return wrapPacket(sequence, payload.toByteArray());
    }

    /**
     * A HandshakeResponse41 shaped like libmysqlclient 8's: a length-encoded auth-response followed by
     * the database, client plugin name, connection attributes and zstd compression level, in the
     * order the protocol defines them.
     */
    private static byte[] fullHandshakeResponse41(String username, byte[] authData, String plugin,
                                                  int capabilities, int sequence) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, capabilities);
        writeInt32LE(payload, 0x0100_0000); // max packet size
        payload.write(0xFF); // charset utf8mb4_0900_ai_ci
        payload.writeBytes(new byte[23]); // reserved
        payload.writeBytes(nulTerminated(username));
        writeLenenc(payload, authData.length);
        payload.writeBytes(authData);
        payload.writeBytes(nulTerminated("appdb"));
        payload.writeBytes(nulTerminated(plugin));
        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        for (String entry : new String[]{"_client_name", "libmysql", "program_name", "mysql"}) {
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            writeLenenc(attributes, bytes.length);
            attributes.writeBytes(bytes);
        }
        writeLenenc(payload, attributes.size());
        payload.writeBytes(attributes.toByteArray());
        payload.write(3); // zstd compression level
        return wrapPacket(sequence, payload.toByteArray());
    }

    private static void writeLenenc(ByteArrayOutputStream out, int value) {
        if (value < 0xFB) {
            out.write(value);
        } else {
            out.write(0xFC);
            writeInt16LE(out, value);
        }
    }

    private static byte[] nulTerminated(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    private static byte[] accessDeniedPayload() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0xFF);
        writeInt16LE(payload, ER_ACCESS_DENIED);
        payload.writeBytes("#28000Access denied for user 'app'@'172.17.0.1' (using password: YES)"
                .getBytes(StandardCharsets.UTF_8));
        return payload.toByteArray();
    }

    private static String authSwitchPluginName(byte[] raw) {
        int end = 5;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 5, end - 5, StandardCharsets.UTF_8);
    }

    private static String extractUsername(byte[] payload) {
        int i = 4 + 4 + 1 + 23; // caps + max-packet-size + charset + reserved
        int start = i;
        while (i < payload.length && payload[i] != 0) {
            i++;
        }
        return new String(payload, start, i - start, StandardCharsets.UTF_8);
    }

    private static int capabilityFlagsLower(byte[] handshakeRaw) {
        int i = 4 + 1; // header + protocol version
        while (i < handshakeRaw.length && handshakeRaw[i] != 0) {
            i++;
        }
        i++; // null terminator
        i += 4; // connection id
        i += 8; // auth-plugin-data part 1
        i++; // filler
        return (handshakeRaw[i] & 0xFF) | ((handshakeRaw[i + 1] & 0xFF) << 8);
    }

    private static byte[] fixedNonce() {
        byte[] nonce = new byte[20];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (i + 1);
        }
        return nonce;
    }

    private static byte[] scrambleNativePassword(String password, byte[] nonce) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash1 = sha1.digest(password.getBytes(StandardCharsets.UTF_8));
        sha1.reset();
        byte[] hash2 = sha1.digest(hash1);
        sha1.reset();
        sha1.update(nonce);
        sha1.update(hash2);
        byte[] hash3 = sha1.digest();

        byte[] result = new byte[20];
        for (int i = 0; i < 20; i++) {
            result[i] = (byte) (hash1[i] ^ hash3[i]);
        }
        return result;
    }

    // ── Raw packet helpers ───────────────────────────────────────────────────

    private static byte[] wrapPacket(int sequence, byte[] payload) {
        byte[] raw = new byte[4 + payload.length];
        int len = payload.length;
        raw[0] = (byte) (len & 0xFF);
        raw[1] = (byte) ((len >> 8) & 0xFF);
        raw[2] = (byte) ((len >> 16) & 0xFF);
        raw[3] = (byte) sequence;
        System.arraycopy(payload, 0, raw, 4, payload.length);
        return raw;
    }

    private static void writeMysqlPacket(OutputStream out, int sequence, byte[] payload) throws IOException {
        out.write(wrapPacket(sequence, payload));
    }

    private static byte[] readMysqlPacketRaw(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int seq = in.read();
        if ((b0 | b1 | b2 | seq) < 0) {
            return null;
        }
        int length = b0 | (b1 << 8) | (b2 << 16);
        byte[] raw = new byte[4 + length];
        raw[0] = (byte) b0;
        raw[1] = (byte) b1;
        raw[2] = (byte) b2;
        raw[3] = (byte) seq;
        int offset = 4;
        while (offset < raw.length) {
            int n = in.read(raw, offset, raw.length - offset);
            if (n < 0) {
                throw new EOFException("Connection closed while reading MySQL packet");
            }
            offset += n;
        }
        return raw;
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
