package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(15)
class RedshiftCopyTransactionTest {
    @Test
    void rollbackToSavepointRetainsOwnershipUntilClientSync() throws Exception {
        runCycle(List.of(), List.of("SAVEPOINT client_sp", "ROLLBACK TO SAVEPOINT client_sp"), false);
    }

    @Test
    void fullRollbackBeforeCopyStartsANewOwnedTransaction() throws Exception {
        runCycle(List.of("BEGIN", "ROLLBACK"), List.of(), false);
    }

    @Test
    void syncCompletionWaitsForTheRestOfAnAsynchronousFrame() throws Exception {
        runCycle(List.of(), List.of("SAVEPOINT client_sp"), true);
    }

    private void runCycle(List<String> before, List<String> after, boolean fragmentNotice) throws Exception {
        try (ServerSocket clients = new ServerSocket(0); ServerSocket backends = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", clients.getLocalPort()); Socket bridgeClient = clients.accept();
                Socket bridgeBackend = new Socket("127.0.0.1", backends.getLocalPort()); Socket backend = backends.accept()) {
            client.setSoTimeout(5000);
            backend.setSoTimeout(5000);
            S3Service s3 = mock(S3Service.class);
            when(s3.objectExists("bucket", "data")).thenReturn(true);
            when(s3.getObject("bucket", "data")).thenReturn(new S3Object("bucket", "data",
                    "1\n".getBytes(StandardCharsets.UTF_8), "text/plain"));
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
                try {
                    serve(backend, fragmentNotice);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            Thread.ofVirtual().start(new RedshiftInterceptingBridge(bridgeClient, bridgeBackend, s3,
                    mock(IamService.class))::run);
            ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
            int index = 0;
            for (String sql : before) {
                statement(pipeline, "stmt" + index++, sql);
            }
            statement(pipeline, "copy", "COPY events FROM 's3://bucket/data' MAXERROR 2");
            for (String sql : after) {
                statement(pipeline, "stmt" + index++, sql);
            }
            pipeline.writeBytes(PostgresWire.frame('S', new byte[0]));
            client.getOutputStream().write(pipeline.toByteArray());
            client.getOutputStream().flush();
            PostgresWireDecoder decoder = new PostgresWireDecoder(client.getInputStream());
            int completed = 0;
            while (true) {
                PostgresWireDecoder.FrontendMessage response = decoder.nextMessage();
                assertNotNull(response, "COPY must leave the connection usable");
                assertFalse(response.type() == 'E', () -> "Unexpected COPY error: "
                        + new String(response.body(), StandardCharsets.UTF_8));
                if (response.type() == 'C') {
                    completed++;
                } else if (response.type() == 'Z') {
                    assertEquals('I', response.body()[0], "The implicit cycle must finish at client Sync");
                    break;
                }
            }
            assertEquals(before.size() + after.size() + 1, completed);
            server.get(5, TimeUnit.SECONDS);
        }
    }

    private static void statement(ByteArrayOutputStream output, String name, String sql) {
        output.writeBytes(PostgresWireDecoder.encodeParse(new PostgresWireDecoder.ParseMessage(name, sql, List.of()), sql));
        output.writeBytes(PostgresWire.frame('B', join(PostgresWire.cStringBytes(name), PostgresWire.cStringBytes(name), new byte[6])));
        output.writeBytes(PostgresWire.frame('E', join(PostgresWire.cStringBytes(name), new byte[4])));
    }

    private static void serve(Socket socket, boolean fragmentNotice) throws Exception {
        PostgresWireDecoder decoder = new PostgresWireDecoder(socket.getInputStream());
        Map<String, String> statements = new HashMap<>();
        Map<String, String> portals = new HashMap<>();
        boolean transaction = false;
        boolean failed = false;
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return;
            }
            if (failed && message.type() != 'S') {
                continue;
            }
            switch (message.type()) {
                case 'P' -> {
                    PostgresWireDecoder.ParseMessage parsed = decoder.decodeParse(message);
                    statements.put(parsed.statementName(), parsed.sql());
                    send(socket, '1', new byte[0]);
                }
                case 'B' -> {
                    PostgresWireDecoder.BindMessage bound = decoder.decodeBind(message);
                    portals.put(bound.portalName(), statements.get(bound.statementName()));
                    send(socket, '2', new byte[0]);
                }
                case 'E' -> {
                    String sql = portals.get(decoder.decodeExecute(message).portalName());
                    if (sql.equals("BEGIN")) {
                        transaction = true;
                    } else if (sql.equals("COMMIT") || sql.equals("ROLLBACK")) {
                        transaction = false;
                    }
                    if (sql.startsWith("SAVEPOINT ") && !transaction) {
                        send(socket, 'E', S3CopySimulator.errorBody("25P01", "SAVEPOINT requires a transaction"));
                        failed = true;
                    } else if (sql.startsWith("COPY ")) {
                        send(socket, 'G', new byte[]{0, 0, 0});
                    } else {
                        if (sql.startsWith("SELECT a.attname")) {
                            send(socket, 'D', join(new byte[]{0, 3}, new byte[]{0, 0, 0, 2, 'i', 'd'},
                                    new byte[]{-1, -1, -1, -1}, new byte[]{0, 0, 0, 2, '2', '3'}));
                        }
                        String tag = sql.startsWith("ROLLBACK") ? "ROLLBACK" : sql.split(" ")[0];
                        send(socket, 'C', PostgresWire.cStringBytes(tag));
                        if (fragmentNotice && sql.equals("SAVEPOINT client_sp")) {
                            byte[] notice = PostgresWire.frame('N', S3CopySimulator.errorBody("00000", "async notice"));
                            socket.getOutputStream().write(notice, 0, 3);
                            socket.getOutputStream().flush();
                            Thread.sleep(300);
                            socket.getOutputStream().write(notice, 3, notice.length - 3);
                            socket.getOutputStream().flush();
                        }
                    }
                }
                case 'c' -> send(socket, 'C', PostgresWire.cStringBytes("COPY 1"));
                case 'C' -> send(socket, '3', new byte[0]);
                case 'S' -> {
                    send(socket, 'Z', new byte[]{(byte) (transaction ? 'T' : 'I')});
                    if (!failed) {
                        return;
                    }
                    failed = false;
                }
                case 'd', 'H' -> { }
                default -> throw new AssertionError("Unexpected wire message " + message.type());
            }
        }
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private static void send(Socket socket, char type, byte[] body) throws IOException {
        socket.getOutputStream().write(PostgresWire.frame(type, body));
        socket.getOutputStream().flush();
    }
}
