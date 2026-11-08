package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        Thread backend = backendThread(() -> playEtlCopy(copied));

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

    @ParameterizedTest
    @ValueSource(strings = {"", " MAXERROR 2"})
    void jsonAutoNormalizesUnquotedColumnsAndPreservesQuotedColumns(String tolerance) throws Exception {
        when(s3.objectExists("b", "in/data")).thenReturn(true);
        when(s3.getObject("b", "in/data")).thenReturn(new S3Object("b", "in/data",
                "{\"id\":1,\"ID\":99,\"MixedCase\":2,\"col one\":3}\n".getBytes(StandardCharsets.UTF_8),
                "application/json"));
        CopyStatementParser.S3CopyFrom copy = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t (ID, \"MixedCase\", \"col one\") FROM 's3://b/in/data' JSON 'auto'" + tolerance);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        Thread backend = backendThread(() -> new FakeCopyBackend(testBackend).serveUntilLoadComplete(copied));

        long accepted = CopyEtlLoader.execute(new CopyBackendSession(simBackend, response -> { }),
                copy, s3, iamService, null, List.of(), false);
        joinBackend(backend);

        assertEquals(1, accepted);
        assertEquals("1,2,3\n", copied.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " MAXERROR 2"})
    void copyRechecksRolePolicyAfterPreflightBeforeReadingObject(String tolerance) throws Exception {
        String roleArn = "arn:aws:iam::000000000000:role/CopyRole";
        IamRole role = mock(IamRole.class);
        when(role.getArn()).thenReturn(roleArn);
        when(iamService.findRole("000000000000", "CopyRole")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(roleArn)).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}")));
        when(s3.isAuthEnforced()).thenReturn(true);
        when(s3.objectExists("b", "in/data")).thenReturn(true);
        when(s3.getObject("b", "in/data")).thenReturn(new S3Object("b", "in/data",
                "1\n".getBytes(StandardCharsets.UTF_8), "text/plain"));
        doAnswer(invocation -> {
            when(iamService.resolvePrincipalContext(roleArn)).thenReturn(CallerContext.of(List.of()));
            return null;
        }).when(s3).authorizeSignedGetObject(any(), any(), eq("b"), eq("in/data"));
        CopyStatementParser.S3CopyFrom copy = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY t FROM 's3://b/in/data' IAM_ROLE '" + roleArn + "'" + tolerance);
        Thread backend = backendThread(() -> new FakeCopyBackend(testBackend).serveUntilSync(false, sql -> { }));
        CopyBackendSession session = new CopyBackendSession(simBackend, response -> { });

        S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> CopyEtlLoader.execute(session, copy, s3, iamService, null, List.of(), false));
        session.finish(true);
        session.synchronize();
        joinBackend(backend);

        assertEquals("42501", error.sqlState());
        verify(s3, never()).getObject("b", "in/data");
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
    void parquetUnloadDescribesColumnsAfterPendingParseAndBind() throws Exception {
        CopyStatementParser.S3Unload unload = (CopyStatementParser.S3Unload) CopyStatementParser.parse(
                "UNLOAD ('SELECT id FROM t') TO 's3://b/out/' FORMAT AS PARQUET ALLOWOVERWRITE");
        RedshiftParquetWriter writer = mock(RedshiftParquetWriter.class);
        byte[] parquet = "PAR1testPAR1".getBytes(StandardCharsets.US_ASCII);
        List<RedshiftParquetWriter.Column> columns = List.of(new RedshiftParquetWriter.Column("id", "INTEGER"));
        when(writer.convert(any(), eq(columns), any(), any(), anyLong())).thenReturn(List.of(parquet));
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, null);
        coordinator.register(BackendResponseCoordinator.Operation.BIND, null);
        BackendResponseCoordinator.Ticket ticket = coordinator.register(BackendResponseCoordinator.Operation.EXECUTE, null);
        Thread backend = backendThread(() -> {
            PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
            assertEquals('H', decoder.nextMessage().type());
            OutputStream out = testBackend.getOutputStream();
            PostgresWire.write(out, '1');
            PostgresWire.write(out, '2');
            out.flush();
            assertEquals('P', decoder.nextMessage().type());
            assertEquals('D', decoder.nextMessage().type());
            assertEquals('C', decoder.nextMessage().type());
            assertEquals('H', decoder.nextMessage().type());
            PostgresWire.write(out, '1');
            PostgresWire.write(out, 'T', new byte[]{0, 1}, PostgresWire.cStringBytes("id"),
                    PostgresWire.int32(0), new byte[2], PostgresWire.int32(23), new byte[]{0, 4},
                    PostgresWire.int32(-1), new byte[2]);
            PostgresWire.write(out, '3');
            out.flush();
            playCopyOut();
        });

        ExtendedS3Exchange.execute(simClient, simBackend, executeFrame(), unload, s3, iamService,
                null, List.of(), coordinator, ticket,
                new CopyBackendSession(simBackend, response -> {
                    throw new AssertionError("Unexpected asynchronous frame");
                }), writer);
        joinBackend(backend);

        verify(s3).putObject(eq("b"), any(), eq(parquet), eq("application/octet-stream"), any());
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('1', clientDecoder.nextMessage().type());
        assertEquals('2', clientDecoder.nextMessage().type());
        assertEquals('C', clientDecoder.nextMessage().type());
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        coordinator.onBackendFrame('Z', new byte[]{'I'});
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
    }

    @Test
    void missingCopyInputReportsErrorBeforeClientSync() throws Exception {
        CopyStatementParser.S3CopyFrom copy = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "b", "missing", "|", 0, false, false, null, null);
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        BackendResponseCoordinator.Ticket ticket = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        Thread backend = backendThread(() -> playEtlCopy(new ByteArrayOutputStream()));

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

    private void playEtlCopy(ByteArrayOutputStream copied) throws Exception {
        PostgresWireDecoder decoder = new PostgresWireDecoder(testBackend.getInputStream());
        assertEquals('E', decoder.nextMessage().type());
        assertEquals('H', decoder.nextMessage().type());
        writeCommandComplete();
        new FakeCopyBackend(testBackend).serveUntilLoadComplete(copied);
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
        void run() throws Exception;
    }
}
