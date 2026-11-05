package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

    @Test
    void commitFailureAtClientSyncReturnsErrorWithoutDroppingSession() throws Exception {
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
                    serve(backend, false, true, null);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            Thread.ofVirtual().start(new RedshiftInterceptingBridge(bridgeClient, bridgeBackend, s3,
                    mock(IamService.class))::run);
            ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
            statement(pipeline, "copy", "COPY events FROM 's3://bucket/data' MAXERROR 2");
            pipeline.writeBytes(PostgresWire.frame('S', new byte[0]));
            client.getOutputStream().write(pipeline.toByteArray());
            client.getOutputStream().flush();
            PostgresWireDecoder decoder = new PostgresWireDecoder(client.getInputStream());
            boolean sawError = false;
            boolean sawReady = false;
            while (true) {
                PostgresWireDecoder.FrontendMessage response = decoder.nextMessage();
                assertNotNull(response, "COPY must leave the connection usable");
                if (response.type() == 'E') {
                    sawError = true;
                } else if (response.type() == 'Z') {
                    sawReady = true;
                    assertEquals('I', response.body()[0], "The aborted cycle must report Idle at Sync");
                    break;
                }
            }
            assertTrue(sawError, "Commit failure must report an error frame to the client");
            assertTrue(sawReady, "The client must receive ReadyForQuery");
            server.get(5, TimeUnit.SECONDS);
        }
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
            CountDownLatch midFrameTimeout = new CountDownLatch(1);
            Socket observedBackend = observePumpTimeout(bridgeBackend, midFrameTimeout);
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
                try {
                    serve(backend, fragmentNotice, false, midFrameTimeout);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            Thread.ofVirtual().start(new RedshiftInterceptingBridge(bridgeClient, observedBackend, s3,
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

    private static void statement(ByteArrayOutputStream output, String name, String sql) throws IOException {
        output.writeBytes(PostgresWireDecoder.encodeParse(new PostgresWireDecoder.ParseMessage(name, sql, List.of()), sql));
        PostgresWire.write(output, 'B', PostgresWire.cStringBytes(name), PostgresWire.cStringBytes(name), new byte[6]);
        PostgresWire.write(output, 'E', PostgresWire.cStringBytes(name), new byte[4]);
    }

    private static Socket observePumpTimeout(Socket backend, CountDownLatch midFrameTimeout) throws IOException {
        InputStream input = new FilterInputStream(backend.getInputStream()) {
            private final RedshiftInterceptingBridge.WireFrameTracker tracker =
                    new RedshiftInterceptingBridge.WireFrameTracker((type, body) -> {
                        // Only frame boundaries matter to the timeout observation.
                    });

            @Override
            public int read(byte[] bytes) throws IOException {
                try {
                    int count = in.read(bytes);
                    if (count > 0) {
                        tracker.consume(bytes, 0, count);
                    }
                    return count;
                } catch (SocketTimeoutException exception) {
                    if (!tracker.betweenMessages()) {
                        midFrameTimeout.countDown();
                    }
                    throw exception;
                }
            }
        };
        Socket observed = spy(backend);
        doReturn(input).when(observed).getInputStream();
        return observed;
    }

    private static void serve(Socket socket, boolean fragmentNotice, boolean failCommit,
                              CountDownLatch midFrameTimeout) throws Exception {
        new FakeCopyBackend(socket).serveUntilSync(failCommit, sql -> {
            if (fragmentNotice && sql.equals("SAVEPOINT client_sp")) {
                byte[] notice = PostgresWire.frame('N', S3CopySimulator.errorBody("00000", "async notice"));
                socket.getOutputStream().write(notice, 0, 3);
                socket.getOutputStream().flush();
                assertTrue(midFrameTimeout.await(5, TimeUnit.SECONDS),
                        "Pump must time out while the notice frame is incomplete");
                socket.getOutputStream().write(notice, 3, notice.length - 3);
                socket.getOutputStream().flush();
            }
        });
    }
}
