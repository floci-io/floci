package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtendedS3ExchangeTest {

    private Socket simClient;
    private Socket simBackend;
    private Socket testClient;
    private Socket testBackend;
    private S3Service s3;
    private final AtomicReference<Throwable> backendFailure = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        ServerSocket clientListener = new ServerSocket(0);
        simClient = new Socket("localhost", clientListener.getLocalPort());
        testClient = clientListener.accept();
        clientListener.close();

        ServerSocket backendListener = new ServerSocket(0);
        simBackend = new Socket("localhost", backendListener.getLocalPort());
        testBackend = backendListener.accept();
        backendListener.close();
        s3 = mock(S3Service.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Socket socket : new Socket[]{simClient, simBackend, testClient, testBackend}) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    @Test
    void copyInForwardsCommandCompleteWithoutReadyForQuery() throws Exception {
        when(s3.objectExists("b", "in/data")).thenReturn(true);
        when(s3.getObject("b", "in/data")).thenReturn(
                new S3Object("b", "in/data", "1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom copy = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "in/data", "|", 0, false, false, null);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        writeSync();
        Thread backend = backendThread(() -> playCopyIn(copied));

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), copy, s3, coordinator, ticket);
        joinBackend(backend);

        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('C', clientDecoder.nextMessage().type());
        assertEquals("1|a\n", copied.toString(StandardCharsets.US_ASCII));
        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
        testClient.setSoTimeout(200);
        assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read());
    }

    @Test
    void copyOutWritesS3AndForwardsOnlyCommandComplete() throws Exception {
        Map<String, byte[]> written = new ConcurrentHashMap<>();
        when(s3.putObject(eq("b"), any(), any(), any(), any())).thenAnswer(invocation -> {
            written.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        });
        CopyStatementParser.S3Unload unload = new CopyStatementParser.S3Unload(
                "select a from t", "b", "out/", "|", false, false, false,
                false, null, false, true, false, 0);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        writeSync();
        Thread backend = backendThread(this::playCopyOut);

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), unload, s3, coordinator, ticket);
        joinBackend(backend);

        assertEquals("1\n2\n", new String(written.get("out/000"), StandardCharsets.US_ASCII));
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('C', clientDecoder.nextMessage().type());
        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
        testClient.setSoTimeout(200);
        assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read());
    }

    @Test
    void missingCopyInputSendsCopyFailAndWaitsForSyncRecovery() throws Exception {
        CopyStatementParser.S3CopyFrom copy = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "missing", "|", 0, false, false, null);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        writeSync();
        Thread backend = backendThread(() -> {
            PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
            assertEquals('E', decoder.nextMessage().type());
            assertEquals('S', decoder.nextMessage().type());
            testBackend.getOutputStream().write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
            testBackend.getOutputStream().flush();
            assertEquals('f', decoder.nextMessage().type());
            writeFrame(testBackend.getOutputStream(), 'E',
                    "SERROR\0C57014\0MCOPY aborted\0\0".getBytes(StandardCharsets.US_ASCII));
            testBackend.getOutputStream().flush();
        });

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), copy, s3, coordinator, ticket);
        joinBackend(backend);

        PostgresWireDecoder.FrontendMessage error = new PostgresWireDecoder(
                testClient.getInputStream()).nextMessage();
        assertEquals('E', error.type());
        assertTrue(new String(error.body(), StandardCharsets.US_ASCII).contains("XX000"));
        assertTrue(new String(error.body(), StandardCharsets.US_ASCII).contains("s3://b/missing"));
        assertTrue(coordinator.isDiscardingUntilSync());

        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertEquals('I', coordinator.lastReadyStatus());
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
    }

    private static PostgresWireDecoder.FrontendMessage executeFrame() {
        return new PostgresWireDecoder.FrontendMessage('E', new byte[]{0, 0, 0, 0, 0});
    }

    private void playCopyIn(ByteArrayOutputStream copied) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
        assertEquals('E', decoder.nextMessage().type());
        assertEquals('S', decoder.nextMessage().type());
        testBackend.getOutputStream().write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
        testBackend.getOutputStream().flush();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message.type() == 'd') {
                copied.write(message.body());
            } else if (message.type() == 'c') {
                break;
            }
        }
        writeCommandComplete();
    }

    private void playCopyOut() throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
        assertEquals('E', decoder.nextMessage().type());
        assertEquals('S', decoder.nextMessage().type());
        OutputStream out = testBackend.getOutputStream();
        out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
        writeFrame(out, 'd', "1\n".getBytes(StandardCharsets.US_ASCII));
        writeFrame(out, 'd', "2\n".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{'c', 0, 0, 0, 4});
        writeCommandComplete();
    }

    private void writeCommandComplete() throws IOException {
        writeFrame(testBackend.getOutputStream(), 'C', "COPY 2\0".getBytes(StandardCharsets.US_ASCII));
        testBackend.getOutputStream().flush();
    }

    private void writeSync() throws IOException {
        testClient.getOutputStream().write(new byte[]{'S', 0, 0, 0, 4});
        testClient.getOutputStream().flush();
    }

    private static void writeFrame(OutputStream out, char type, byte[] body) throws IOException {
        out.write(type);
        out.write(new byte[]{0, 0, 0, (byte) (4 + body.length)});
        out.write(body);
    }

    private Thread backendThread(BackendScript script) {
        return Thread.ofVirtual().start(() -> {
            try {
                script.run();
            } catch (Throwable failure) {
                backendFailure.compareAndSet(null, failure);
            }
        });
    }

    private void joinBackend(Thread backend) throws InterruptedException {
        backend.join();
        Throwable failure = backendFailure.get();
        if (failure != null) {
            throw new AssertionError("fake backend failed", failure);
        }
    }

    @FunctionalInterface
    private interface BackendScript {
        void run() throws IOException;
    }
}
