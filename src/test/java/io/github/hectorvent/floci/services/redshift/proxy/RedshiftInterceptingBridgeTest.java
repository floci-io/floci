package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftInterceptingBridgeTest {

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
        clientListener = new ServerSocket(0);
        testClientEnd = new Socket("localhost", clientListener.getLocalPort());
        bridgeClientEnd = clientListener.accept();

        backendListener = new ServerSocket(0);
        bridgeBackendEnd = new Socket("localhost", backendListener.getLocalPort());
        testBackendEnd = backendListener.accept();

        s3Stub = Mockito.mock(S3Service.class);
        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(bridgeClientEnd, bridgeBackendEnd, s3Stub);
        bridgeThread = Thread.ofVirtual().name("bridge-under-test").start(bridge::run);
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
