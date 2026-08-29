package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresProtocolHandlerTest {

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int STARTUP_PROTOCOL_VERSION = 196608;

    @TempDir
    Path tempDir;

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
                        PostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
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
                        PostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
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
                        PostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
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
                        PostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> true);
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
                        PostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), tlsCertificates,
                                (user, pass) -> true);
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
                        PostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "dbadmin", "adminpass", "postgres",
                                false, testSigV4Validator(), testTlsCertificates(),
                                (user, pass) -> true);
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

}
