package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.rds.proxy.PasswordValidator;
import io.floci.aws.services.rds.proxy.PostgresProtocolHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftAuthProxyIdentityTest {

    @Test
    void detectsSessionAuthorizationResetAndDefaultStatements() {
        assertTrue(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("RESET SESSION AUTHORIZATION"));
        assertTrue(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("  reset   session   authorization ; "));
        assertTrue(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("SET SESSION AUTHORIZATION DEFAULT"));
        assertTrue(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("SET LOCAL SESSION AUTHORIZATION 'admin'"));
        assertTrue(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("DISCARD ALL"));
        assertTrue(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("RESET ALL"));
        assertFalse(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("SET search_path TO public"));
        assertFalse(RedshiftInterceptingBridge.isRestrictedSessionAuthorization("SELECT current_user"));
    }

    @Test
    void roleHandoverFailureRejectsClientWithFatalErrorInsteadOfMasterFallback() throws Exception {
        try (ServerSocket clientListener = new ServerSocket(0);
             ServerSocket backendListener = new ServerSocket(0)) {

            AtomicReference<String> clientError = new AtomicReference<>();
            CountDownLatch clientDone = new CountDownLatch(1);
            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", clientListener.getLocalPort())) {
                    OutputStream out = client.getOutputStream();
                    InputStream in = client.getInputStream();
                    sendStartup(out, "IAM:analyst", "dev");
                    expectAuthCleartext(in);
                    sendPassword(out, "temp-pw");
                    clientError.set(readErrorText(in));
                } catch (IOException ignored) {
                    // Client socket closes when test finishes.
                } finally {
                    clientDone.countDown();
                }
            });

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try (Socket backend = backendListener.accept()) {
                    InputStream in = backend.getInputStream();
                    OutputStream out = backend.getOutputStream();
                    readStartup(in);
                    // Request cleartext password from proxy, accept master password, send ReadyForQuery.
                    writeFrame(out, 'R', int32Bytes(3));
                    out.flush();
                    readFrontendMessage(in);
                    writeFrame(out, 'R', int32Bytes(0));
                    writeFrame(out, 'Z', new byte[]{'I'});
                    out.flush();
                } catch (IOException ignored) {
                    // Backend socket closes on rejection.
                }
            });

            try (Socket acceptedClient = clientListener.accept()) {
                PostgresProtocolHandler.BackendConnector connector = () -> {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()), 2000);
                    return s;
                };
                PasswordValidator validator = (u, p) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT;
                PostgresProtocolHandler.SessionRoleHandover handover = (user, pass, db) -> {
                    throw new SQLException("role \"analyst\" does not exist", "28000");
                };

                PostgresProtocolHandler.AuthenticatedSession session = PostgresProtocolHandler.authenticate(
                        acceptedClient, connector, "admin", "master-pw", "dev",
                        false, null, null, null, validator,
                        5000, false, true, (u, d, params) -> { }, handover);

                assertNull(session);
            }

            assertTrue(clientDone.await(5, TimeUnit.SECONDS));
            clientThread.join(1000);
            backendThread.join(1000);
            assertTrue(clientError.get() != null && clientError.get().contains("role \"analyst\" does not exist"),
                    () -> "Expected role error, got: " + clientError.get());
        }
    }

    private static void sendStartup(OutputStream out, String user, String database) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(int32Bytes(196608));
        writeCString(body, "user");
        writeCString(body, user);
        writeCString(body, "database");
        writeCString(body, database);
        body.write(0);
        byte[] payload = body.toByteArray();
        out.write(int32Bytes(4 + payload.length));
        out.write(payload);
        out.flush();
    }

    private static void expectAuthCleartext(InputStream in) throws IOException {
        int type = in.read();
        int len = readInt32(in);
        byte[] body = in.readNBytes(len - 4);
        if (type != 'R' || ByteBuffer.wrap(body).getInt() != 3) {
            throw new IOException("Expected AuthenticationCleartextPassword");
        }
    }

    private static void sendPassword(OutputStream out, String password) throws IOException {
        byte[] bytes = (password + "\0").getBytes(StandardCharsets.UTF_8);
        writeFrame(out, 'p', bytes);
        out.flush();
    }

    private static String readErrorText(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        int len = readInt32(in);
        byte[] body = in.readNBytes(len - 4);
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void readStartup(InputStream in) throws IOException {
        int len = readInt32(in);
        in.readNBytes(len - 4);
    }

    private static void readFrontendMessage(InputStream in) throws IOException {
        in.read();
        int len = readInt32(in);
        in.readNBytes(len - 4);
    }

    private static void writeFrame(OutputStream out, char type, byte[] body) throws IOException {
        out.write((byte) type);
        out.write(int32Bytes(4 + body.length));
        out.write(body);
    }

    private static void writeCString(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static byte[] int32Bytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static int readInt32(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(4);
        return ByteBuffer.wrap(buf).getInt();
    }
}
