package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PostgresProtocolHandlerTest {

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int STARTUP_PROTOCOL_VERSION = 196608;

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
                                false, testSigV4Validator(),
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
                                false, testSigV4Validator(),
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
    void acceptsPostgresSslRequestAndUpgradesSocket() throws Exception {
        ArrayBlockingQueue<Integer> serverRead = new ArrayBlockingQueue<>(1);

        try (ServerSocket server = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = server.accept()) {
                    DataInputStream in = new DataInputStream(accepted.getInputStream());
                    DataOutputStream out = new DataOutputStream(accepted.getOutputStream());
                    assertEquals(8, in.readInt());
                    assertEquals(SSL_REQUEST_CODE, in.readInt());

                    out.writeByte('S');
                    out.flush();
                    Socket sslSocket = PostgresProtocolHandler.acceptSsl(accepted);
                    serverRead.add(sslSocket.getInputStream().read());
                    sslSocket.getOutputStream().write(99);
                    sslSocket.getOutputStream().flush();
                    sslSocket.close();
                } catch (Exception e) {
                    serverRead.add(-1);
                }
            });

            try (Socket rawClient = new Socket("localhost", server.getLocalPort())) {
                DataOutputStream out = new DataOutputStream(rawClient.getOutputStream());
                DataInputStream in = new DataInputStream(rawClient.getInputStream());
                out.writeInt(8);
                out.writeInt(SSL_REQUEST_CODE);
                out.flush();
                assertEquals('S', in.readUnsignedByte());

                SSLSocket sslClient = (SSLSocket) trustAllContext().getSocketFactory()
                        .createSocket(rawClient, "localhost", server.getLocalPort(), true);
                sslClient.setUseClientMode(true);
                sslClient.startHandshake();
                sslClient.getOutputStream().write(42);
                sslClient.getOutputStream().flush();
                assertEquals(99, sslClient.getInputStream().read());
                sslClient.close();
            }
        }

        assertEquals(42, serverRead.poll(5, TimeUnit.SECONDS));
    }

    private static RdsSigV4Validator testSigV4Validator() {
        return new RdsSigV4Validator(IamServiceTestHelper.iamServiceWithAccessKey("AKIATEST", "secret"));
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

    private static void writePassword(DataOutputStream out, String password) throws IOException {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        out.writeByte('p');
        out.writeInt(4 + pw.length + 1);
        out.write(pw);
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

    private static SSLContext trustAllContext() throws Exception {
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
        }}, new SecureRandom());
        return context;
    }
}
