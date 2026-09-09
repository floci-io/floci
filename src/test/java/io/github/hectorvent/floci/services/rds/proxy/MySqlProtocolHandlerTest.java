package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlProtocolHandlerTest {

    private static final int CLIENT_PROTOCOL_41 = 0x0200;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_PLUGIN_AUTH = 0x0008_0000;

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
                                proxyClient, backend, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
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
                                proxyClient, backend, "admin", "secret",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
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
                                proxyClient, backend, "admin", "secret",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> true);
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
                                proxyClient, backend, "admin", "secret",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> true);
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
