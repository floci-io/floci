package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditLogPublisher;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumInterceptor;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumQueryPreparation;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.InlineMocks.inlineMock;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftInterceptingBridgeTest {

    @Test
    void tracksBeginCommandAcrossFragmentedBackendFrames() {
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(new ExtendedQuerySession());
        RedshiftInterceptingBridge.WireFrameTracker tracker =
                new RedshiftInterceptingBridge.WireFrameTracker(coordinator::onBackendFrame);
        byte[] begin = new byte[]{'C', 0, 0, 0, 10, 'B', 'E', 'G', 'I', 'N', 0};
        tracker.consume(begin, 0, 7);
        assertEquals('I', coordinator.lastReadyStatus());
        tracker.consume(begin, 7, 4);
        assertEquals('T', coordinator.lastReadyStatus());
    }

    private static final Logger LOG = Logger.getLogger(RedshiftInterceptingBridgeTest.class);

    private ServerSocket clientListener;
    private ServerSocket backendListener;
    private Socket testClientEnd;   // test writes client bytes here, reads pump output here
    private Socket bridgeClientEnd; // the bridge's "client" socket
    private Socket bridgeBackendEnd; // the bridge's "backend" socket
    private Socket testBackendEnd;  // test reads what the bridge forwarded, writes backend replies
    private Thread bridgeThread;

    private S3Service s3Stub;

    private void startBridge() throws IOException {
        startBridge(null);
    }

    private void startBridge(SpectrumInterceptor spectrumInterceptor) throws IOException {
        startBridge(spectrumInterceptor, null);
    }

    private void startBridge(SpectrumInterceptor spectrumInterceptor, RedshiftAuditLogPublisher audit) throws IOException {
        clientListener = new ServerSocket(0);
        testClientEnd = new Socket("localhost", clientListener.getLocalPort());
        bridgeClientEnd = clientListener.accept();

        backendListener = new ServerSocket(0);
        bridgeBackendEnd = new Socket("localhost", backendListener.getLocalPort());
        testBackendEnd = backendListener.accept();

        s3Stub = Mockito.mock(S3Service.class);
        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(
                bridgeClientEnd, bridgeBackendEnd, s3Stub, Mockito.mock(IamService.class), "000000000000", List.of(),
                spectrumInterceptor, "000000000000:c", "dev", null, audit, "analyst");
        bridgeThread = Thread.ofVirtual().name("bridge-under-test").start(bridge::run);
    }

    @Test
    @Timeout(10)
    void multiCommandQueryLogsEveryCommittedUserChange() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        sendAuditQuery("SELECT ';'; /* migration */ CREATE USER alice; ALTER USER alice RENAME TO bob",
                "SELECT 1", "CREATE ROLE", "ALTER ROLE");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "alice", "Create", null);
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "bob", "Rename", "alice");
    }

    @Test
    @Timeout(10)
    void emptyAndFailedQueriesDoNotMislabelLaterUserChanges() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery("; -- empty"));
        nextForwarded();
        testBackendEnd.getOutputStream().write(concat(frame('I', new byte[0]), frame('Z', new byte[]{'I'})));
        testBackendEnd.getOutputStream().flush();
        sendAuditQuery("SELECT broken; CREATE USER skipped", new String[0]);
        sendAuditQuery("CREATE USER actual", "CREATE ROLE");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "actual", "Create", null);
        verify(audit, never()).publishRegisteredUserChange(anyString(), anyString(), anyString(), eq("skipped"), anyString(), any());
    }

    @Test
    @Timeout(10)
    void implicitTransactionFailureDiscardsEarlierSuccessfulUserChanges() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery("CREATE USER rolledback; SELECT broken"));
        nextForwarded();
        testBackendEnd.getOutputStream().write(concat(frame('C', cString("CREATE ROLE")),
                frame('E', cString("failed")), frame('Z', new byte[]{'I'})));
        testBackendEnd.getOutputStream().flush();
        sendAuditQuery("CREATE USER actual", "CREATE ROLE");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "actual", "Create", null);
        verify(audit, never()).publishRegisteredUserChange(anyString(), anyString(), anyString(), eq("rolledback"), anyString(), any());
    }

    @Test
    @Timeout(10)
    void rollbackInsideAMultiCommandQueryDoesNotPublishDiscardedChanges() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        sendAuditQuery("BEGIN; CREATE USER rolledback; ROLLBACK; CREATE USER actual",
                "BEGIN", "CREATE ROLE", "ROLLBACK", "CREATE ROLE");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "actual", "Create", null);
        verify(audit, never()).publishRegisteredUserChange(anyString(), anyString(), anyString(), eq("rolledback"), anyString(), any());
    }

    @Test
    @Timeout(10)
    void rollbackToSavepointPreservesOnlyEarlierUserChanges() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        sendAuditQuery("BEGIN; CREATE USER kept; SAVEPOINT s; CREATE USER rolledback; ROLLBACK TO s; COMMIT",
                "BEGIN", "CREATE ROLE", "SAVEPOINT", "CREATE ROLE", "ROLLBACK", "COMMIT");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "kept", "Create", null);
        verify(audit, never()).publishRegisteredUserChange(anyString(), anyString(), anyString(), eq("rolledback"), anyString(), any());
    }

    @Test
    @Timeout(10)
    void suspendedPortalsDoNotConsumeLaterUserChangeCompletions() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
        PostgresWireDecoder.ParseMessage parse = new PostgresWireDecoder.ParseMessage("select", "SELECT 1", List.of());
        pipeline.write(PostgresWireDecoder.encodeParse(parse, parse.sql()));
        pipeline.write(frame('B', concat(cString("portal"), cString("select"), new byte[6])));
        pipeline.write(frame('E', concat(cString("portal"), new byte[]{0, 0, 0, 1})));
        pipeline.write(frame('E', concat(cString("portal"), new byte[4])));
        pipeline.write(frame('S', new byte[0]));
        testClientEnd.getOutputStream().write(pipeline.toByteArray());
        testClientEnd.getOutputStream().flush();
        for (int i = 0; i < 5; i++) {
            nextForwarded();
        }
        testBackendEnd.getOutputStream().write(concat(frame('1', new byte[0]), frame('2', new byte[0]),
                frame('s', new byte[0]), frame('C', cString("SELECT 1")), frame('Z', new byte[]{'I'})));
        testBackendEnd.getOutputStream().flush();
        sendAuditQuery("CREATE USER actual", "CREATE ROLE");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "actual", "Create", null);
    }

    @Test
    @Timeout(10)
    void rejectedNamedParseDoesNotReplaceTheSqlUsedForUserAudit() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        startBridge(null, audit);
        PostgresWireDecoder.ParseMessage original = new PostgresWireDecoder.ParseMessage("s", "SELECT 1", List.of());
        testClientEnd.getOutputStream().write(concat(PostgresWireDecoder.encodeParse(original, original.sql()), frame('S', new byte[0])));
        nextForwarded();
        nextForwarded();
        testBackendEnd.getOutputStream().write(concat(frame('1', new byte[0]), frame('Z', new byte[]{'I'})));
        testBackendEnd.getOutputStream().flush();
        PostgresWireDecoder replies = new PostgresWireDecoder(testClientEnd.getInputStream());
        assertEquals('1', replies.nextMessage().type());
        assertEquals('Z', replies.nextMessage().type());
        PostgresWireDecoder.ParseMessage rejected = new PostgresWireDecoder.ParseMessage("s", "CREATE USER phantom", List.of());
        testClientEnd.getOutputStream().write(concat(PostgresWireDecoder.encodeParse(rejected, rejected.sql()), frame('S', new byte[0])));
        nextForwarded();
        nextForwarded();
        testBackendEnd.getOutputStream().write(concat(frame('E', cString("duplicate name")), frame('Z', new byte[]{'I'})));
        testBackendEnd.getOutputStream().flush();
        assertEquals('E', replies.nextMessage().type());
        assertEquals('Z', replies.nextMessage().type());
        testClientEnd.getOutputStream().write(concat(frame('B', concat(cString("p"), cString("s"), new byte[6])),
                frame('E', concat(cString("p"), new byte[4])), frame('S', new byte[0])));
        for (int i = 0; i < 3; i++) {
            nextForwarded();
        }
        testBackendEnd.getOutputStream().write(concat(frame('2', new byte[0]), frame('C', cString("SELECT 1")), frame('Z', new byte[]{'I'})));
        testBackendEnd.getOutputStream().flush();
        sendAuditQuery("CREATE USER actual", "CREATE ROLE");
        verify(audit, timeout(2000)).publishRegisteredUserChange("000000000000", "c", "dev", "actual", "Create", null);
        verify(audit, never()).publishRegisteredUserChange(anyString(), anyString(), anyString(), eq("phantom"), anyString(), any());
    }

    private void sendAuditQuery(String sql, String... tags) throws IOException {
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery(sql));
        testClientEnd.getOutputStream().flush();
        nextForwarded();
        for (String tag : tags) {
            testBackendEnd.getOutputStream().write(frame('C', cString(tag)));
        }
        if (tags.length == 0) {
            testBackendEnd.getOutputStream().write(frame('E', cString("failed")));
        }
        testBackendEnd.getOutputStream().write(frame('Z', new byte[]{'I'}));
        testBackendEnd.getOutputStream().flush();
    }

    @AfterEach
    void tearDown() {
        for (Socket s : new Socket[]{testClientEnd, bridgeClientEnd, bridgeBackendEnd, testBackendEnd}) {
            if (s != null && !s.isClosed()) {
                try {
                    s.close();
                } catch (IOException e) {
                    LOG.debugv(e, "Error closing socket during test cleanup");
                }
            }
        }
        for (ServerSocket ss : new ServerSocket[]{clientListener, backendListener}) {
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException e) {
                    LOG.debugv(e, "Error closing server socket during test cleanup");
                }
            }
        }
    }

    private PostgresWireDecoder.FrontendMessage nextForwarded() throws IOException {
        return new PostgresWireDecoder(testBackendEnd.getInputStream()).nextMessage();
    }

    @Test
    void rewritesRedshiftCreateTableBeforeForwarding() throws IOException {
        startBridge();
        String ddl = "CREATE TABLE sales (id int ENCODE az64, d date) "
                + "DISTSTYLE KEY DISTKEY (id) COMPOUND SORTKEY (d);";
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery(ddl));
        testClientEnd.getOutputStream().flush();

        PostgresWireDecoder.FrontendMessage forwarded = nextForwarded();
        assertNotNull(forwarded);
        assertEquals('Q', forwarded.type());
        String sent = forwarded.getSql().toUpperCase();
        assertFalse(sent.contains("DISTKEY"), sent);
        assertFalse(sent.contains("SORTKEY"), sent);
        assertFalse(sent.contains("DISTSTYLE"), sent);
        assertFalse(sent.contains("ENCODE"), sent);
        assertTrue(forwarded.getSql().contains("CREATE TABLE sales"), forwarded.getSql());
    }

    @Test
    void forwardsANonDdlQueryByteForByte() throws IOException {
        startBridge();
        byte[] packet = PostgresWireDecoder.encodeQuery("SELECT 'DISTKEY' AS not_a_keyword");
        testClientEnd.getOutputStream().write(packet);
        testClientEnd.getOutputStream().flush();

        assertArrayEquals(packet, nextForwarded().toPacketBytes());
    }

    @Test
    void forwardsAnExtendedProtocolMessageOpaque() throws IOException {
        startBridge();
        byte[] parsePayload = "s1\0SELECT $1\0\0\0".getBytes(StandardCharsets.UTF_8);
        int length = 4 + parsePayload.length;
        byte[] parsePacket = new byte[1 + length];
        parsePacket[0] = 'P';
        parsePacket[1] = (byte) ((length >> 24) & 0xFF);
        parsePacket[2] = (byte) ((length >> 16) & 0xFF);
        parsePacket[3] = (byte) ((length >> 8) & 0xFF);
        parsePacket[4] = (byte) (length & 0xFF);
        System.arraycopy(parsePayload, 0, parsePacket, 5, parsePayload.length);

        testClientEnd.getOutputStream().write(parsePacket);
        testClientEnd.getOutputStream().flush();

        assertArrayEquals(parsePacket, nextForwarded().toPacketBytes());
    }

    @Test
    void rewritesDdlParseWhilePreservingNameAndParameterOids() throws IOException {
        startBridge();
        PostgresWireDecoder.ParseMessage parse = new PostgresWireDecoder.ParseMessage(
                "ddl", "CREATE TABLE sales (id int ENCODE az64, d date) "
                        + "DISTSTYLE KEY DISTKEY (id) SORTKEY (d)", List.of(23, 25));
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeParse(parse, parse.sql()));
        testClientEnd.getOutputStream().flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(testBackendEnd.getInputStream());
        PostgresWireDecoder.ParseMessage forwarded = backendDecoder.decodeParse(backendDecoder.nextMessage());

        assertEquals("ddl", forwarded.statementName());
        assertEquals(List.of(23, 25), forwarded.parameterTypeOids());
        String sql = forwarded.sql().toUpperCase();
        assertFalse(sql.contains("DISTSTYLE"), sql);
        assertFalse(sql.contains("DISTKEY"), sql);
        assertFalse(sql.contains("SORTKEY"), sql);
        assertFalse(sql.contains("ENCODE"), sql);
    }

    @Test
    void parameterizedCopyParseFallsOpenByteForByte() throws IOException {
        startBridge();
        PostgresWireDecoder.ParseMessage parse = new PostgresWireDecoder.ParseMessage(
                "copy", "COPY t FROM 's3://b/in/data'", List.of(25));
        byte[] packet = PostgresWireDecoder.encodeParse(parse, parse.sql());

        testClientEnd.getOutputStream().write(packet);
        testClientEnd.getOutputStream().flush();

        assertArrayEquals(packet, nextForwarded().toPacketBytes());
    }

    @Test
    void pipelinedExtendedCopyPreservesResponseOrderAndSyncReadyForQuery() throws Exception {
        startBridge();
        Mockito.when(s3Stub.objectExists("b", "in/data")).thenReturn(true);
        Mockito.when(s3Stub.getObject("b", "in/data")).thenReturn(
                new S3Object("b", "in/data", "1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        AtomicReference<Throwable> backendFailure = new AtomicReference<>();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder decoder = new PostgresWireDecoder(testBackendEnd.getInputStream());
                PostgresWireDecoder.ParseMessage parse = decoder.decodeParse(decoder.nextMessage());
                assertTrue(parse.sql().contains("FROM STDIN"), parse.sql());
                writeBackendFrame('1', new byte[0]);
                assertEquals('B', decoder.nextMessage().type());
                writeBackendFrame('2', new byte[0]);
                assertEquals('D', decoder.nextMessage().type());
                writeBackendFrame('n', new byte[0]);
                assertEquals('E', decoder.nextMessage().type());
                writeBackendFrame('G', new byte[]{0, 0, 0});
                while (decoder.nextMessage().type() != 'c') {
                    // Consume CopyData until the bridge completes CopyIn.
                }
                assertEquals('S', decoder.nextMessage().type());
                writeBackendFrame('C', "COPY 1\0".getBytes(StandardCharsets.US_ASCII));
                writeBackendFrame('Z', new byte[]{'I'});
            } catch (Throwable failure) {
                backendFailure.compareAndSet(null, failure);
            }
        });

        PostgresWireDecoder.ParseMessage parse = new PostgresWireDecoder.ParseMessage(
                "copy-s", "COPY t FROM 's3://b/in/data'", List.of());
        ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
        pipeline.write(PostgresWireDecoder.encodeParse(parse, parse.sql()));
        pipeline.write(frame('B', concat(cString("copy-p"), cString("copy-s"), new byte[6])));
        pipeline.write(frame('D', concat(new byte[]{'P'}, cString("copy-p"))));
        pipeline.write(frame('E', concat(cString("copy-p"), new byte[4])));
        pipeline.write(frame('S', new byte[0]));
        testClientEnd.getOutputStream().write(pipeline.toByteArray());
        testClientEnd.getOutputStream().flush();
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClientEnd.getInputStream());
        assertEquals('1', clientDecoder.nextMessage().type());
        assertEquals('2', clientDecoder.nextMessage().type());
        assertEquals('n', clientDecoder.nextMessage().type());
        assertEquals('C', clientDecoder.nextMessage().type());
        assertEquals('Z', clientDecoder.nextMessage().type());
        backend.join();
        if (backendFailure.get() != null) {
            throw new AssertionError("fake backend failed", backendFailure.get());
        }
    }

    @Test
    @Timeout(5)
    void extendedUnloadWithManifestCompletesAfterCopyOutCommandComplete() throws Exception {
        startBridge();
        Mockito.when(s3Stub.putObject(
                Mockito.eq("b"), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(null);
        AtomicReference<Throwable> backendFailure = new AtomicReference<>();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder decoder = new PostgresWireDecoder(testBackendEnd.getInputStream());
                PostgresWireDecoder.ParseMessage parse = decoder.decodeParse(decoder.nextMessage());
                assertTrue(parse.sql().startsWith("COPY (select id from t) TO STDOUT"), parse.sql());
                assertEquals('B', decoder.nextMessage().type());
                assertEquals('D', decoder.nextMessage().type());
                assertEquals('E', decoder.nextMessage().type());
                assertEquals('S', decoder.nextMessage().type());
                writeBackendFrame('1', new byte[0]);
                writeBackendFrame('2', new byte[0]);
                writeBackendFrame('n', new byte[0]);
                writeBackendFrame('H', new byte[]{0, 0, 0});
                writeBackendFrame('d', "1\n".getBytes(StandardCharsets.US_ASCII));
                writeBackendFrame('c', new byte[0]);
                writeBackendFrame('C', "COPY 1\0".getBytes(StandardCharsets.US_ASCII));
                writeBackendFrame('Z', new byte[]{'I'});
            } catch (Throwable failure) {
                backendFailure.compareAndSet(null, failure);
            }
        });

        PostgresWireDecoder.ParseMessage parse = new PostgresWireDecoder.ParseMessage(
                "unload-s", "UNLOAD ('select id from t') TO 's3://b/out/' MANIFEST", List.of());
        ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
        pipeline.write(PostgresWireDecoder.encodeParse(parse, parse.sql()));
        pipeline.write(frame('B', concat(cString("unload-p"), cString("unload-s"), new byte[6])));
        pipeline.write(frame('D', concat(new byte[]{'P'}, cString("unload-p"))));
        pipeline.write(frame('E', concat(cString("unload-p"), new byte[4])));
        pipeline.write(frame('S', new byte[0]));
        testClientEnd.getOutputStream().write(pipeline.toByteArray());
        testClientEnd.getOutputStream().flush();
        testClientEnd.setSoTimeout(3000);

        backend.join(1000);
        if (backendFailure.get() != null) {
            throw new AssertionError("fake backend failed", backendFailure.get());
        }
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClientEnd.getInputStream());
        assertEquals('1', clientDecoder.nextMessage().type());
        assertEquals('2', clientDecoder.nextMessage().type());
        assertEquals('n', clientDecoder.nextMessage().type());
        assertEquals('C', clientDecoder.nextMessage().type());
        assertEquals('Z', clientDecoder.nextMessage().type());
        backend.join();
        if (backendFailure.get() != null) {
            throw new AssertionError("fake backend failed", backendFailure.get());
        }
    }

    private void writeBackendFrame(char type, byte[] body) throws IOException {
        testBackendEnd.getOutputStream().write(frame(type, body));
        testBackendEnd.getOutputStream().flush();
    }

    private static byte[] frame(char type, byte[] body) {
        int length = 4 + body.length;
        byte[] packet = new byte[5 + body.length];
        packet[0] = (byte) type;
        packet[1] = (byte) (length >>> 24);
        packet[2] = (byte) (length >>> 16);
        packet[3] = (byte) (length >>> 8);
        packet[4] = (byte) length;
        System.arraycopy(body, 0, packet, 5, body.length);
        return packet;
    }

    private static byte[] cString(String value) {
        return concat(value.getBytes(StandardCharsets.UTF_8), new byte[]{0});
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    @Test
    @Timeout(20)
    void locallyHandledQueryInsideTransactionKeepsTheTransactionStatus() throws Exception {
        SpectrumInterceptor interceptor = inlineMock(SpectrumInterceptor.class);
        Mockito.when(interceptor.intercept(Mockito.anyString(), Mockito.any(), Mockito.any(Socket.class)))
                .thenReturn(new SpectrumInterceptor.Decision.Forward(), new SpectrumInterceptor.Decision.Handled());
        startBridge(interceptor);
        InputStream fromBridge = testClientEnd.getInputStream();

        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery("BEGIN"));
        testClientEnd.getOutputStream().flush();
        assertEquals('Q', nextForwarded().type());
        writeBackendFrame('C', cString("BEGIN"));
        writeBackendFrame('Z', new byte[]{'T'});
        assertEquals('C', fromBridge.read());
        skipFrameBody(fromBridge);
        assertEquals('Z', fromBridge.read());
        skipFrameBody(fromBridge);

        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery("CREATE EXTERNAL TABLE lake.t (id INT)"));
        testClientEnd.getOutputStream().flush();
        assertEquals('C', fromBridge.read());
        skipFrameBody(fromBridge);
        assertEquals('Z', fromBridge.read());
        fromBridge.readNBytes(4);
        assertEquals('T', fromBridge.read());
    }

    @Test
    @Timeout(20)
    void parsingAndDescribingExternalDdlDoesNotExecuteCatalogChanges() throws Exception {
        String sql = "ALTER TABLE lake.events ADD PARTITION (dt='today') LOCATION 's3://bucket/today/'";
        SpectrumInterceptor interceptor = inlineMock(SpectrumInterceptor.class);
        SpectrumQueryPreparation preparation = Mockito.mock(SpectrumQueryPreparation.class);
        Mockito.when(interceptor.preparation()).thenReturn(preparation);
        Mockito.when(preparation.referencesExternal(Mockito.eq(sql), Mockito.any())).thenReturn(true);
        Mockito.when(interceptor.plan(Mockito.eq(sql), Mockito.any())).thenReturn(new SpectrumInterceptor.Plan.ExternalDdl(
                sql, new SpectrumSession("000000000000", "000000000000:c", "dev", List.of(), false)));
        startBridge(interceptor);
        PostgresWireDecoder.ParseMessage parse = new PostgresWireDecoder.ParseMessage("ddl", sql, List.of());
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeParse(parse, sql));
        testClientEnd.getOutputStream().write(frame('D', concat(new byte[]{'S'}, cString("ddl"))));
        testClientEnd.getOutputStream().flush();
        InputStream response = testClientEnd.getInputStream();
        assertEquals('1', response.read());
        skipFrameBody(response);
        assertEquals('t', response.read());
        skipFrameBody(response);
        assertEquals('n', response.read());
        skipFrameBody(response);
        Mockito.verify(preparation, Mockito.never()).prepare(Mockito.anyString(), Mockito.any(), Mockito.any());
        Mockito.verify(interceptor, Mockito.never()).execute(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    private static void skipFrameBody(InputStream in) throws IOException {
        int length = ByteBuffer.wrap(in.readNBytes(4)).getInt();
        in.readNBytes(length - 4);
    }

    @Test
    void pumpsBackendBytesToTheClientUnchanged() throws IOException {
        startBridge();
        byte[] readyForQuery = new byte[]{'Z', 0, 0, 0, 5, 'I'};
        testBackendEnd.getOutputStream().write(readyForQuery);
        testBackendEnd.getOutputStream().flush();

        byte[] got = testClientEnd.getInputStream().readNBytes(readyForQuery.length);
        assertArrayEquals(readyForQuery, got);
    }

    @Test
    void terminateMessageIsForwardedAndEndsTheSession() throws Exception {
        startBridge();
        testClientEnd.getOutputStream().write(new byte[]{'X', 0, 0, 0, 4});
        testClientEnd.getOutputStream().flush();

        PostgresWireDecoder.FrontendMessage forwarded = nextForwarded();
        assertEquals('X', forwarded.type());

        bridgeThread.join(5_000);
        assertFalse(bridgeThread.isAlive(), "bridge did not stop after Terminate");
        assertEquals(-1, testClientEnd.getInputStream().read(), "bridge left the client socket open");
    }

    @Test
    void interceptsCopyFromS3AndDoesNotForwardTheOriginalQuery() throws Exception {
        startBridge();
        Mockito.when(s3Stub.objectExists("wh", "k")).thenReturn(true);
        Mockito.when(s3Stub.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));

        AtomicReference<Throwable> backendFailure = new AtomicReference<>();
        // fake backend: expect a fabricated Query, answer 'G', then after CopyDone answer 'C'+'Z'
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder in = new PostgresWireDecoder(testBackendEnd.getInputStream());
                PostgresWireDecoder.FrontendMessage q = in.nextMessage();
                assertEquals('Q', q.type());
                assertTrue(q.getSql().contains("FROM STDIN"), q.getSql());
                OutputStream out = testBackendEnd.getOutputStream();
                out.write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
                out.flush();
                while (true) {
                    PostgresWireDecoder.FrontendMessage m = in.nextMessage();
                    if (m == null || m.type() == 'c') {
                        break;
                    }
                }
                byte[] tag = "COPY 1\0".getBytes(StandardCharsets.US_ASCII);
                out.write('C');
                out.write(new byte[]{0, 0, 0, (byte) (4 + tag.length)});
                out.write(tag);
                out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
                out.flush();
            } catch (Throwable t) {
                backendFailure.compareAndSet(null, t);
            }
        });

        testClientEnd.getOutputStream().write(
                PostgresWireDecoder.encodeQuery("COPY t FROM 's3://wh/k'"));
        testClientEnd.getOutputStream().flush();

        PostgresWireDecoder.FrontendMessage toClient =
                new PostgresWireDecoder(testClientEnd.getInputStream()).nextMessage();
        assertEquals('C', toClient.type());
        backend.join();
        if (backendFailure.get() != null) {
            throw new AssertionError("fake backend failed", backendFailure.get());
        }
    }

    @Test
    void fallsOpenAndForwardsCopyWhenBackendHasAnOutstandingResponse() throws Exception {
        startBridge();
        // send a normal query first and never answer it, so outstandingResponses stays > 0
        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery("SELECT 1"));
        testClientEnd.getOutputStream().flush();
        PostgresWireDecoder backendIn = new PostgresWireDecoder(testBackendEnd.getInputStream());
        assertEquals('Q', backendIn.nextMessage().type()); // SELECT 1 arrived, unanswered

        testClientEnd.getOutputStream().write(PostgresWireDecoder.encodeQuery("COPY t FROM 's3://wh/k'"));
        testClientEnd.getOutputStream().flush();

        // within the ~2s deadline the bridge gives up and forwards the COPY verbatim
        PostgresWireDecoder.FrontendMessage forwarded = backendIn.nextMessage();
        assertEquals('Q', forwarded.type());
        assertTrue(forwarded.getSql().contains("s3://wh/k"), forwarded.getSql());
    }

    @Test
    void unloadQueryIsDispatchedToTheSimulator() throws Exception {
        startBridge();
        Mockito.when(s3Stub.listObjectsWithPrefixes(Mockito.eq("b"), Mockito.eq("out/"),
                        Mockito.isNull(), Mockito.anyInt(), Mockito.any(), Mockito.any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        AtomicReference<byte[]> put = new AtomicReference<>();
        Mockito.when(s3Stub.putObject(Mockito.eq("b"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> {
                    put.compareAndSet(null, inv.getArgument(2));
                    return null;
                });

        // Fake backend: expect the fabricated COPY (...) TO STDOUT and play one row.
        Thread be = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder in = new PostgresWireDecoder(testBackendEnd.getInputStream());
                PostgresWireDecoder.FrontendMessage q = in.nextMessage();
                assertTrue(q.getSql().contains("TO STDOUT"), q.getSql());
                OutputStream out = testBackendEnd.getOutputStream();
                out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
                byte[] row = "1|a\n".getBytes(StandardCharsets.US_ASCII);
                out.write('d');
                out.write(new byte[]{(byte) (0), 0, 0, (byte) (4 + row.length)});
                out.write(row);
                out.write(new byte[]{'c', 0, 0, 0, 4});
                byte[] tag = "COPY 1\0".getBytes(StandardCharsets.US_ASCII);
                out.write('C');
                out.write(new byte[]{0, 0, 0, (byte) (4 + tag.length)});
                out.write(tag);
                out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
                out.flush();
            } catch (IOException e) {
                LOG.debugv(e, "fake backend ended");
            }
        });

        OutputStream clientOut = testClientEnd.getOutputStream();
        clientOut.write(PostgresWireDecoder.encodeQuery("UNLOAD ('select a,b from t') TO 's3://b/out/'"));
        clientOut.flush();

        PostgresWireDecoder fromPump = new PostgresWireDecoder(testClientEnd.getInputStream());
        assertEquals('C', fromPump.nextMessage().type());
        assertEquals('Z', fromPump.nextMessage().type());
        be.join();
        assertNotNull(put.get(), "the simulator should have written one S3 object");
    }
}
