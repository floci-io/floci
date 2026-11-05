package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

@Timeout(10)
class ExtendedS3ExchangeTest {

    private Socket simClient;
    private Socket simBackend;
    private Socket testClient;
    private Socket testBackend;
    private S3Service s3;
    private IamService iamService;
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
        simBackend.setSoTimeout(3_000);
        testBackend.setSoTimeout(3_000);
        testClient.setSoTimeout(3_000);
        s3 = mock(S3Service.class);
        iamService = mock(IamService.class);
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
                "t", List.of(), "b", "in/data", "|", 0, false, false, null, null);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        Thread backend = backendThread(() -> playEtlCopy(copied, false));

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), copy, s3, iamService,
                null, List.of(), coordinator, ticket,
                new CopyBackendSession(simBackend, response -> writeFrame(simClient.getOutputStream(),
                        response.type(), response.body())));
        joinBackend(backend);

        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('C', clientDecoder.nextMessage().type());
        assertEquals("1|a\n", copied.toString(StandardCharsets.US_ASCII));
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
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
                false, null, false, true, false, 0, null);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        Thread backend = backendThread(this::playCopyOut);

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), unload, s3, iamService,
                null, List.of(), coordinator, ticket,
                new CopyBackendSession(simBackend, response -> writeFrame(simClient.getOutputStream(),
                        response.type(), response.body())));
        joinBackend(backend);

        assertEquals("1\n2\n", new String(written.get("out/000"), StandardCharsets.US_ASCII));
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('C', clientDecoder.nextMessage().type());
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
        testClient.setSoTimeout(200);
        assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read());
    }

    @Test
    void missingCopyInputReportsErrorBeforeClientSync() throws Exception {
        CopyStatementParser.S3CopyFrom copy = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "missing", "|", 0, false, false, null, null);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        Thread backend = backendThread(() -> playEtlCopy(new ByteArrayOutputStream(), true));

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), copy, s3, iamService,
                null, List.of(), coordinator, ticket, new CopyBackendSession(simBackend, response -> {
                    throw new AssertionError("Unexpected asynchronous frame");
                }));
        joinBackend(backend);

        testClient.setSoTimeout(1_000);
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage error = clientDecoder.nextMessage();
        assertEquals('E', error.type());
        assertTrue(new String(error.body(), StandardCharsets.US_ASCII).contains("XX000"));
        assertTrue(new String(error.body(), StandardCharsets.US_ASCII).contains("s3://b/missing"));
        testClient.setSoTimeout(200);
        assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read());
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
    }

    private static PostgresWireDecoder.FrontendMessage executeFrame() {
        return new PostgresWireDecoder.FrontendMessage('E', new byte[]{0, 0, 0, 0, 0});
    }

    private void playEtlCopy(ByteArrayOutputStream copied, boolean missingInput) throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
        assertEquals('E', decoder.nextMessage().type());
        assertEquals('H', decoder.nextMessage().type());
        writeCommandComplete();
        Map<String, String> statements = new HashMap<>();
        Map<String, String> portals = new HashMap<>();
        boolean finishing = false;
        int closed = 0;
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            OutputStream out = testBackend.getOutputStream();
            switch (message.type()) {
                case 'P' -> {
                    PostgresWireDecoder.ParseMessage parsed = decoder.decodeParse(message);
                    statements.put(parsed.statementName(), parsed.sql());
                    writeFrame(out, '1', new byte[0]);
                }
                case 'B' -> {
                    PostgresWireDecoder.BindMessage bound = decoder.decodeBind(message);
                    portals.put(bound.portalName(), statements.get(bound.statementName()));
                    writeFrame(out, '2', new byte[0]);
                }
                case 'E' -> {
                    String sql = portals.get(decoder.decodeExecute(message).portalName());
                    if (sql.startsWith("COPY ")) {
                        writeFrame(out, 'G', new byte[]{0, 0, 0});
                    } else if (sql.equals("SELECT 1 / 0")) {
                        assertTrue(missingInput);
                        writeFrame(out, 'E', S3CopySimulator.errorBody("22012", "division by zero"));
                        out.flush();
                        return;
                    } else {
                        if (sql.startsWith("SELECT a.attname")) {
                            writeFrame(out, 'D', new byte[]{0, 3, 0, 0, 0, 2, 'i', 'd',
                                    -1, -1, -1, -1, 0, 0, 0, 2, '2', '3'});
                        }
                        finishing = !missingInput && sql.startsWith("RELEASE SAVEPOINT floci_load_");
                        writeFrame(out, 'C', PostgresWire.cStringBytes("SELECT 0"));
                    }
                }
                case 'd' -> copied.write(message.body());
                case 'c' -> writeFrame(out, 'C', PostgresWire.cStringBytes("COPY 1"));
                case 'C' -> {
                    writeFrame(out, '3', new byte[0]);
                    if (finishing) {
                        closed++;
                    }
                }
                case 'H' -> {
                    out.flush();
                    if (finishing && closed == 2) {
                        return;
                    }
                }
                default -> throw new AssertionError("Unexpected wire message " + message.type());
            }
        }
    }

    private void playCopyOut() throws IOException {
        PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
        assertEquals('E', decoder.nextMessage().type());
        assertEquals('H', decoder.nextMessage().type());
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

    private static void writeFrame(OutputStream out, char type, byte[] body) throws IOException {
        PostgresWire.write(out, type, body);
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
        backend.join(5_000);
        assertTrue(!backend.isAlive(), "Fake backend must finish");
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
