package io.github.hectorvent.floci.services.redshift.proxy;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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

    private void startBridge() throws IOException {
        clientListener = new ServerSocket(0);
        testClientEnd = new Socket("localhost", clientListener.getLocalPort());
        bridgeClientEnd = clientListener.accept();

        backendListener = new ServerSocket(0);
        bridgeBackendEnd = new Socket("localhost", backendListener.getLocalPort());
        testBackendEnd = backendListener.accept();

        RedshiftInterceptingBridge bridge = new RedshiftInterceptingBridge(bridgeClientEnd, bridgeBackendEnd);
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
}
