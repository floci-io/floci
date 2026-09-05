package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3CopySimulatorTest {

    private ServerSocket listener;
    private Socket simClient;   // simulator's "client" end
    private Socket simBackend;  // simulator's "backend" end
    private Socket testClient;  // test reads ErrorResponse / CommandComplete here
    private Socket testBackend; // test plays PostgreSQL here
    private S3Service s3;

    @BeforeEach
    void setUp() throws IOException {
        listener = new ServerSocket(0);
        simClient = new Socket("localhost", listener.getLocalPort());
        testClient = listener.accept();

        ServerSocket backendListener = new ServerSocket(0);
        simBackend = new Socket("localhost", backendListener.getLocalPort());
        testBackend = backendListener.accept();
        backendListener.close();

        s3 = mock(S3Service.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Socket s : new Socket[]{simClient, simBackend, testClient, testBackend}) {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        }
        if (listener != null && !listener.isClosed()) {
            listener.close();
        }
    }

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(s.getBytes(StandardCharsets.US_ASCII));
        }
        return bytes.toByteArray();
    }

    /** Read one backend message the simulator sent to the fake backend. */
    private PostgresWireDecoder.FrontendMessage nextFromSimulatorToBackend() throws IOException {
        return new PostgresWireDecoder(testBackend.getInputStream()).nextMessage();
    }

    /** Play a minimal happy-path PostgreSQL backend: 'G' then, after CopyDone, 'C' and 'Z'. */
    private void playHappyBackend(ByteArrayOutputStream capturedCopyData) throws IOException {
        OutputStream out = testBackend.getOutputStream();
        // read the fabricated Query
        PostgresWireDecoder.FrontendMessage query = nextFromSimulatorToBackend();
        assertEquals('Q', query.type());
        // CopyInResponse 'G': body = format(1) + columnCount(2) = 3 bytes, all zero
        out.write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
        out.flush();
        // drain CopyData 'd' frames and CopyDone 'c'
        PostgresWireDecoder backend = new PostgresWireDecoder(testBackend.getInputStream());
        while (true) {
            PostgresWireDecoder.FrontendMessage m = backend.nextMessage();
            if (m == null || m.type() == 'c') {
                break;
            }
            if (m.type() == 'd') {
                capturedCopyData.write(m.body());
            }
        }
        // CommandComplete 'C' "COPY 1\0", then ReadyForQuery 'Z' 'I'
        byte[] tag = "COPY 1\0".getBytes(StandardCharsets.US_ASCII);
        out.write('C');
        out.write(intBytes(4 + tag.length));
        out.write(tag);
        out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
        out.flush();
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    @Test
    void loadsSingleKeyAsPipeTextAndForwardsCommandComplete() throws Exception {
        when(s3.objectExists("wh", "d/a.txt")).thenReturn(true);
        S3Object obj = new S3Object("wh", "d/a.txt", "1|alice\n2|bob\n".getBytes(StandardCharsets.US_ASCII), "text/plain");
        when(s3.getObject("wh", "d/a.txt")).thenReturn(obj);

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "people", List.of(), "wh", "d/a.txt", "|", 0, false, false, null);

        ByteArrayOutputStream copyData = new ByteArrayOutputStream();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                playHappyBackend(copyData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        backend.join();

        assertTrue(handled);
        assertEquals("1|alice\n2|bob\n", copyData.toString(StandardCharsets.US_ASCII));

        PostgresWireDecoder.FrontendMessage toClient = new PostgresWireDecoder(testClient.getInputStream()).nextMessage();
        assertEquals('C', toClient.type());
    }

    @Test
    void fabricatedQueryUsesTextFormatAndPipeDelimiterByDefault() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "x\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of("a", "b"), "wh", "k", "|", 0, false, false, null);

        StringBuilder fabricated = new StringBuilder();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder.FrontendMessage q = nextFromSimulatorToBackend();
                fabricated.append(q.getSql());
                testBackend.getOutputStream().write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
                testBackend.getOutputStream().flush();
                PostgresWireDecoder b = new PostgresWireDecoder(testBackend.getInputStream());
                while (true) {
                    PostgresWireDecoder.FrontendMessage m = b.nextMessage();
                    if (m == null || m.type() == 'c') {
                        break;
                    }
                }
                testBackend.getOutputStream().write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
                testBackend.getOutputStream().flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        backend.join();

        String sql = fabricated.toString();
        assertTrue(sql.contains("COPY t (a, b) FROM STDIN"), sql);
        assertTrue(sql.toUpperCase().contains("FORMAT TEXT"), sql);
        assertTrue(sql.contains("DELIMITER '|'"), sql);
        assertFalse(sql.toUpperCase().contains("HEADER"), sql);
    }

    @Test
    void fabricatedQueryUsesCsvFormatAndCommaDelimiterByDefault() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "x\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of("a", "b"), "wh", "k", null, 0, false, true, null);

        StringBuilder fabricated = new StringBuilder();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                PostgresWireDecoder.FrontendMessage q = nextFromSimulatorToBackend();
                fabricated.append(q.getSql());
                testBackend.getOutputStream().write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
                testBackend.getOutputStream().flush();
                PostgresWireDecoder b = new PostgresWireDecoder(testBackend.getInputStream());
                while (true) {
                    PostgresWireDecoder.FrontendMessage m = b.nextMessage();
                    if (m == null || m.type() == 'c') {
                        break;
                    }
                }
                testBackend.getOutputStream().write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
                testBackend.getOutputStream().flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        backend.join();

        String sql = fabricated.toString();
        assertTrue(sql.contains("COPY t (a, b) FROM STDIN"), sql);
        assertTrue(sql.toUpperCase().contains("FORMAT CSV"), sql);
        assertTrue(sql.contains("DELIMITER ','"), sql);
    }

    @Test
    void concatenatesPrefixObjectsInKeyOrderAndSkipsHeaderOnFirstOnly() throws Exception {
        when(s3.objectExists("wh", "p/")).thenReturn(false);
        when(s3.listObjects("wh", "p/", null, 10000)).thenReturn(List.of(
                new S3Object("wh", "p/1", "h1|h2\n1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"),
                new S3Object("wh", "p/2", "2|b\n".getBytes(StandardCharsets.US_ASCII), "text/plain")));
        when(s3.getObject("wh", "p/1")).thenReturn(
                new S3Object("wh", "p/1", "h1|h2\n1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        when(s3.getObject("wh", "p/2")).thenReturn(
                new S3Object("wh", "p/2", "2|b\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "p/", "|", 1, false, false, null);

        ByteArrayOutputStream copyData = new ByteArrayOutputStream();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                playHappyBackend(copyData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        backend.join();

        assertEquals("1|a\n2|b\n", copyData.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void decompressesGzipObjects() throws Exception {
        when(s3.objectExists("wh", "k.gz")).thenReturn(true);
        when(s3.getObject("wh", "k.gz")).thenReturn(
                new S3Object("wh", "k.gz", gzip("1|a\n2|b\n"), "application/gzip"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k.gz", "|", 0, true, false, null);

        ByteArrayOutputStream copyData = new ByteArrayOutputStream();
        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                playHappyBackend(copyData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        backend.join();

        assertEquals("1|a\n2|b\n", copyData.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void deniedBucketSendsInsufficientPrivilegeErrorResponse() throws Exception {
        doThrow(new AwsException("AccessDenied", "no", 403))
                .when(s3).authorizeAnonymousListBucket("wh");
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k", "|", 0, false, false, null);

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        assertTrue(handled);

        byte[] header = testClient.getInputStream().readNBytes(1);
        assertEquals('E', (char) header[0]);
        PostgresWireDecoder clientDecoder = new PostgresWireDecoder(testClient.getInputStream());
        // read length (4 bytes) and body
        byte[] lenBytes = testClient.getInputStream().readNBytes(4);
        int len = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16) | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);
        String body = new String(testClient.getInputStream().readNBytes(len - 4), StandardCharsets.US_ASCII);
        assertTrue(body.contains("42501"), body);
    }

    @Test
    void missingKeySendsNotFoundErrorResponse() throws Exception {
        when(s3.objectExists("wh", "missing")).thenReturn(false);
        when(s3.listObjects("wh", "missing", null, 10000)).thenReturn(List.of());
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "missing", "|", 0, false, false, null);

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        assertTrue(handled);

        byte[] header = testClient.getInputStream().readNBytes(1);
        assertEquals('E', (char) header[0]);
    }

    @Test
    void backendErrorInsteadOfCopyInIsForwardedToClient() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "1\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "nosuch", List.of(), "wh", "k", "|", 0, false, false, null);

        Thread backend = Thread.ofVirtual().start(() -> {
            try {
                nextFromSimulatorToBackend();
                byte[] msg = "SERROR\0C42P01\0Mrelation \"nosuch\" does not exist\0\0".getBytes(StandardCharsets.US_ASCII);
                OutputStream out = testBackend.getOutputStream();
                out.write('E');
                out.write(intBytes(4 + msg.length));
                out.write(msg);
                out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3);
        backend.join();
        assertTrue(handled);

        byte[] header = testClient.getInputStream().readNBytes(1);
        assertEquals('E', (char) header[0]);
    }
}
