package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private final AtomicReference<Throwable> backendFailure = new AtomicReference<>();

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

    @FunctionalInterface
    private interface BackendScript {
        void run() throws IOException;
    }

    /** Run a fake-backend script on a virtual thread, capturing any failure for the test thread. */
    private Thread backendThread(BackendScript script) {
        return Thread.ofVirtual().start(() -> {
            try {
                script.run();
            } catch (Throwable t) {
                backendFailure.compareAndSet(null, t);
            }
        });
    }

    private void joinBackend(Thread t) throws InterruptedException {
        t.join();
        Throwable failure = backendFailure.get();
        if (failure != null) {
            throw new AssertionError("fake backend failed: " + failure, failure);
        }
    }

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(s.getBytes(StandardCharsets.US_ASCII));
        }
        return bytes.toByteArray();
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private PostgresWireDecoder.FrontendMessage nextFromSimulatorToBackend() throws IOException {
        return new PostgresWireDecoder(testBackend.getInputStream()).nextMessage();
    }

    /** Play a PostgreSQL backend for a fabricated COPY (...) TO STDOUT: 'H', N 'd' rows, 'c', 'C', 'Z'. */
    private void playUnloadBackend(String... rows) throws IOException {
        OutputStream out = testBackend.getOutputStream();
        PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
        PostgresWireDecoder.FrontendMessage q = in.nextMessage();
        assertEquals('Q', q.type());
        assertTrue(q.getSql().contains("TO STDOUT"), q.getSql());
        out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0}); // CopyOutResponse
        for (String row : rows) {
            byte[] b = row.getBytes(StandardCharsets.US_ASCII);
            out.write('d');
            out.write(intBytes(4 + b.length));
            out.write(b);
        }
        out.write(new byte[]{'c', 0, 0, 0, 4});          // CopyDone
        byte[] tag = "COPY 2\0".getBytes(StandardCharsets.US_ASCII);
        out.write('C');
        out.write(intBytes(4 + tag.length));
        out.write(tag);
        out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
        out.flush();
    }

    private CopyStatementParser.S3Unload unloadSpec(String bucket, String prefix,
            boolean gzip, boolean manifest, boolean allowOverwrite, boolean parallel, long maxFileSize) {
        return new CopyStatementParser.S3Unload("select a,b from t", bucket, prefix,
                "|", false, gzip, false, false, null, manifest, allowOverwrite, parallel, maxFileSize);
    }

    @Test
    void unloadWritesSingleObjectAndForwardsCommandComplete() throws Exception {
        Map<String, byte[]> written = new ConcurrentHashMap<>();
        when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
            written.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        });
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);
        Thread backend = backendThread(() -> playUnloadBackend("1|alice\n", "2|bob\n"));

        boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertTrue(handled);
        assertEquals(1, written.size());
        assertTrue(written.containsKey("out/0000_part_00"), written.keySet().toString());
        assertEquals("1|alice\n2|bob\n", new String(written.get("out/0000_part_00"), StandardCharsets.US_ASCII));

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('C', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void unloadRepeatsTheHeaderRowInEverySlice() throws Exception {
        long saved = S3CopySimulator.UNLOAD_TARGET_FILE_BYTES;
        S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = 4; // one data row per slice
        try {
            Map<String, byte[]> written = new ConcurrentHashMap<>();
            when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
                written.put(inv.getArgument(1), inv.getArgument(2));
                return null;
            });
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

            CopyStatementParser.S3Unload spec = new CopyStatementParser.S3Unload(
                    "select a,b from t", "wh", "out/", "|", true, false, true, false, null,
                    false, false, true, 0);
            // The fake backend stands in for PostgreSQL: the first CopyData frame is the header row.
            Thread backend = backendThread(() -> playUnloadBackend("h1|h2\n", "1|a\n", "2|b\n", "3|c\n"));
            S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            assertEquals(3, written.size(), written.keySet().toString());
            for (Map.Entry<String, byte[]> e : written.entrySet()) {
                String body = new String(e.getValue(), StandardCharsets.US_ASCII);
                assertTrue(body.startsWith("h1|h2\n"), e.getKey() + " -> " + body);
            }
        } finally {
            S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = saved;
        }
    }

    @Test
    void unloadSplitsIntoMultipleObjectsOnNewlineBoundaries() throws Exception {
        long saved = S3CopySimulator.UNLOAD_TARGET_FILE_BYTES;
        S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = 6; // force a split after each row
        try {
            Map<String, byte[]> written = new ConcurrentHashMap<>();
            when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
                written.put(inv.getArgument(1), inv.getArgument(2));
                return null;
            });
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

            CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);
            Thread backend = backendThread(() -> playUnloadBackend("1|alice\n", "2|bob\n", "3|carol\n"));
            S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            assertTrue(written.containsKey("out/0000_part_00"));
            assertTrue(written.containsKey("out/0001_part_00"));
            assertTrue(written.containsKey("out/0002_part_00"));
            assertEquals(3, written.size(), written.keySet().toString());
            StringBuilder all = new StringBuilder();
            written.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> all.append(new String(e.getValue(), StandardCharsets.US_ASCII)));
            assertEquals("1|alice\n2|bob\n3|carol\n", all.toString());
        } finally {
            S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = saved;
        }
    }

    @Test
    void unloadParallelOffUsesThreeDigitKeyNames() throws Exception {
        Map<String, byte[]> written = new ConcurrentHashMap<>();
        when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
            written.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        });
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, false, 0);
        Thread backend = backendThread(() -> playUnloadBackend("1|a\n"));
        S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertTrue(written.containsKey("out/000"), written.keySet().toString());
    }

    @Test
    void unloadGzipAppendsGzSuffixAndWritesDecompressibleBytes() throws Exception {
        Map<String, byte[]> written = new ConcurrentHashMap<>();
        when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
            written.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        });
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", true, false, false, true, 0);
        Thread backend = backendThread(() -> playUnloadBackend("1|a\n", "2|b\n"));
        S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertTrue(written.containsKey("out/0000_part_00.gz"), written.keySet().toString());
        byte[] gz = written.get("out/0000_part_00.gz");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            in.transferTo(out);
        }
        assertEquals("1|a\n2|b\n", out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void unloadManifestListsEveryDataObject() throws Exception {
        long saved = S3CopySimulator.UNLOAD_TARGET_FILE_BYTES;
        S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = 8;
        try {
            Map<String, byte[]> written = new ConcurrentHashMap<>();
            when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
                written.put(inv.getArgument(1), inv.getArgument(2));
                return null;
            });
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

            CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, true, false, true, 0);
            Thread backend = backendThread(() -> playUnloadBackend("1|alice\n", "2|bob\n", "3|carol\n"));
            S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            String manifest = new String(written.get("out/manifest"), StandardCharsets.UTF_8);
            assertTrue(manifest.contains("s3://wh/out/0000_part_00"), manifest);
            assertTrue(manifest.contains("s3://wh/out/0001_part_00"), manifest);
            assertTrue(manifest.contains("\"content_length\":"), manifest);
        } finally {
            S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = saved;
        }
    }

    @Test
    void unloadIntoNonEmptyPrefixWithoutAllowOverwriteErrorsAndSkipsSelect() throws Exception {
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("wh", "out/old", new byte[]{1}, "text/plain")), List.of(), false, null));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);
        // No backend thread: the select must never run.
        boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void unloadForwardsBackendErrorWhenSelectIsInvalid() throws Exception {
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);

        Thread backend = backendThread(() -> {
            OutputStream out = testBackend.getOutputStream();
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            assertEquals('Q', in.nextMessage().type());
            byte[] err = "SERROR\0C42703\0Mcolumn \"nope\" does not exist\0\0".getBytes(StandardCharsets.US_ASCII);
            out.write('E');
            out.write(intBytes(4 + err.length));
            out.write(err);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
            out.flush();
        });

        S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
        testClient.setSoTimeout(400);
        assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read());
    }

    @Test
    void unloadAbortsWhenResultExceedsTotalCeiling() throws Exception {
        long saved = S3CopySimulator.UNLOAD_MAX_TOTAL_BYTES;
        S3CopySimulator.UNLOAD_MAX_TOTAL_BYTES = 16;
        try {
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
            CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 1_000_000);

            Thread backend = backendThread(() -> {
                OutputStream out = testBackend.getOutputStream();
                PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
                assertEquals('Q', in.nextMessage().type());
                out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
                byte[] big = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n".getBytes(StandardCharsets.US_ASCII);
                out.write('d');
                out.write(intBytes(4 + big.length));
                out.write(big);
                out.flush();
                try {
                    out.write(new byte[]{'c', 0, 0, 0, 4});
                    out.flush();
                } catch (IOException ignored) {
                    // Backend socket is closed immediately by simulator on ceiling exceed
                }
            });

            S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
            PostgresWireDecoder.FrontendMessage err = in.nextMessage();
            assertEquals('E', err.type());
            assertTrue(new String(err.body(), StandardCharsets.US_ASCII).contains("54000"));
            assertEquals('Z', in.nextMessage().type());
            assertNull(in.nextMessage(), "Client must receive EOF after ReadyForQuery on connection close");
        } finally {
            S3CopySimulator.UNLOAD_MAX_TOTAL_BYTES = saved;
        }
    }

    @Test
    void unloadInTransactionBlockReportsFailedTransactionStatusOnError() throws Exception {
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(
                        List.of(new S3Object("wh", "out/old", new byte[]{1}, "text/plain")), List.of(), false, null));
        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);

        Thread backend = backendThread(() -> {
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            PostgresWireDecoder.FrontendMessage q = in.nextMessage();
            assertEquals('Q', q.type());
            assertTrue(q.getSql().contains("FLOCI_ABORT_TX"));
            OutputStream out = testBackend.getOutputStream();
            byte[] err = "SERROR\0C42601\0Msyntax error\0\0".getBytes(StandardCharsets.US_ASCII);
            out.write('E');
            out.write(intBytes(4 + err.length));
            out.write(err);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'E'});
            out.flush();
        });

        S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'T');
        joinBackend(backend);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        PostgresWireDecoder.FrontendMessage z = in.nextMessage();
        assertEquals('Z', z.type());
        assertEquals('E', (char) z.body()[0]);
    }

    @Test
    void unloadStreamingFailureDrainsBackendAndSendsSingleError() throws Exception {
        long saved = S3CopySimulator.UNLOAD_TARGET_FILE_BYTES;
        S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = 8; // Force flush on first row
        try {
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
            when(s3.putObject(eq("wh"), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("simulated S3 storage failure"));

            CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);

            Thread backend = backendThread(() -> {
                OutputStream out = testBackend.getOutputStream();
                PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
                assertEquals('Q', in.nextMessage().type());
                out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
                // Send multiple rows so the backend has pending messages when putObject throws mid-stream on the first slice
                byte[] row1 = "1|alice\n".getBytes(StandardCharsets.US_ASCII);
                out.write('d');
                out.write(intBytes(4 + row1.length));
                out.write(row1);
                byte[] row2 = "2|bob\n".getBytes(StandardCharsets.US_ASCII);
                out.write('d');
                out.write(intBytes(4 + row2.length));
                out.write(row2);
                out.write(new byte[]{'c', 0, 0, 0, 4});
                byte[] tag = "COPY 2\0".getBytes(StandardCharsets.US_ASCII);
                out.write('C');
                out.write(intBytes(4 + tag.length));
                out.write(tag);
                out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
                out.flush();
            });

            boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            assertTrue(handled);
            PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
            assertEquals('E', in.nextMessage().type());
            assertEquals('Z', in.nextMessage().type());
            testClient.setSoTimeout(400);
            assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read(),
                    "no leftover or duplicate messages should reach client");
        } finally {
            S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = saved;
        }
    }

    @Test
    void unloadAbortsWhenMemoryBudgetExhaustedReports53400() throws Exception {
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);

        S3CopySimulator.UNLOAD_HEAP_MIB.acquire(192);
        try {
            boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            assertTrue(handled);

            PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
            PostgresWireDecoder.FrontendMessage err = in.nextMessage();
            assertEquals('E', err.type());
            String errBody = new String(err.body(), StandardCharsets.UTF_8);
            assertTrue(errBody.contains("53400"), errBody);
            assertTrue(errBody.contains("memory budget exhausted"), errBody);
            assertEquals('Z', in.nextMessage().type());
        } finally {
            S3CopySimulator.UNLOAD_HEAP_MIB.release(192);
        }
    }

    @Test
    void unloadFabricatedCopySqlMatchesFormattingOptions() throws Exception {
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        CopyStatementParser.S3Unload spec = new CopyStatementParser.S3Unload(
                "select a from t", "wh", "out/", "\t", true, false, false, true, "\\N", false, true, true, 0);

        AtomicReference<String> seenSql = new AtomicReference<>();
        Thread backend = backendThread(() -> {
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            PostgresWireDecoder.FrontendMessage q = in.nextMessage();
            seenSql.set(q.getSql());
            OutputStream out = testBackend.getOutputStream();
            out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
            out.write(new byte[]{'c', 0, 0, 0, 4});
            byte[] tag = "COPY 0\0".getBytes(StandardCharsets.US_ASCII);
            out.write('C');
            out.write(intBytes(4 + tag.length));
            out.write(tag);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
            out.flush();
        });

        S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        String sql = seenSql.get();
        assertNotNull(sql);
        assertTrue(sql.contains("FORMAT csv"), sql);
        assertTrue(sql.contains("DELIMITER '\t'"), sql);
        assertTrue(sql.contains("HEADER true"), sql);
        assertTrue(sql.contains("FORCE_QUOTE *"), sql);
        assertTrue(sql.contains("NULL '\\N'"), sql);
    }

    @Test
    void unloadZeroRowsEmitsSingleEmptyObject() throws Exception {
        Map<String, byte[]> written = new ConcurrentHashMap<>();
        when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
            written.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        });
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);
        Thread backend = backendThread(() -> {
            OutputStream out = testBackend.getOutputStream();
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            assertEquals('Q', in.nextMessage().type());
            out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
            out.write(new byte[]{'c', 0, 0, 0, 4});
            byte[] tag = "COPY 0\0".getBytes(StandardCharsets.US_ASCII);
            out.write('C');
            out.write(intBytes(4 + tag.length));
            out.write(tag);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
            out.flush();
        });

        boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertTrue(handled);
        assertEquals(1, written.size());
        assertTrue(written.containsKey("out/0000_part_00"));
        assertEquals(0, written.get("out/0000_part_00").length);
    }

    @Test
    void unloadBucketNotFoundSurfacesNotFoundMessageInsteadOfAccessDenied() throws Exception {
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);
        boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage err = in.nextMessage();
        assertEquals('E', err.type());
        String body = new String(err.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("not found"), body);
        assertFalse(body.contains("access denied"), body);
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void unloadManifestFailureCleansUpWrittenDataObjects() throws Exception {
        List<String> deleted = new ArrayList<>();
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            if (key.endsWith("manifest")) {
                throw new RuntimeException("disk full writing manifest");
            }
            return null;
        });
        when(s3.deleteObject(eq("wh"), any())).thenAnswer(inv -> {
            deleted.add(inv.getArgument(1));
            return null;
        });

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, true, false, true, 0);
        Thread backend = backendThread(() -> playUnloadBackend("1|a\n"));
        S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertEquals(1, deleted.size());
        assertEquals("out/0000_part_00", deleted.get(0));

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void unloadManifestFailureUnderAllowOverwriteLeavesEveryDataObjectInPlace() throws Exception {
        long saved = S3CopySimulator.UNLOAD_TARGET_FILE_BYTES;
        S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = 4; // one data row per slice
        try {
            List<String> deleted = new ArrayList<>();
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
            when(s3.putObject(eq("wh"), any(), any(), any(), any())).thenAnswer(inv -> {
                if (((String) inv.getArgument(1)).endsWith("manifest")) {
                    throw new RuntimeException("disk full writing manifest");
                }
                return null;
            });
            when(s3.deleteObject(eq("wh"), any())).thenAnswer(inv -> {
                deleted.add(inv.getArgument(1));
                return null;
            });

            CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, true, true, true, 0);
            Thread backend = backendThread(() -> playUnloadBackend("1|a\n", "2|b\n"));
            S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            // Under ALLOWOVERWRITE a written key may have replaced pre-existing (or another
            // operation's) data, so cleanup deletes nothing and the partial result is left behind.
            assertTrue(deleted.isEmpty(), "ALLOWOVERWRITE cleanup must not delete any object, got " + deleted);

            PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
            assertEquals('E', in.nextMessage().type());
            assertEquals('Z', in.nextMessage().type());
        } finally {
            S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = saved;
        }
    }

    @Test
    void unloadWithoutAllowOverwriteFailsIfListBucketDenied() throws Exception {
        doThrow(new AwsException("AccessDenied", "Access Denied", 403))
                .when(s3).authorizeAnonymousListBucket(eq("wh"));

        CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, false, false, true, 0);
        boolean handled = S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage err = in.nextMessage();
        assertEquals('E', err.type());
        String body = new String(err.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("42501"), body);
        assertTrue(body.contains("access denied"), body);
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void unloadBackendEofMidStreamWithManifestCleansUpWrittenDataObjects() throws Exception {
        long saved = S3CopySimulator.UNLOAD_TARGET_FILE_BYTES;
        S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = 6;
        try {
            List<String> deleted = new ArrayList<>();
            when(s3.listObjectsWithPrefixes(eq("wh"), eq("out/"), isNull(), anyInt(), any(), any()))
                    .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
            when(s3.deleteObject(eq("wh"), any())).thenAnswer(inv -> {
                deleted.add(inv.getArgument(1));
                return null;
            });

            CopyStatementParser.S3Unload spec = unloadSpec("wh", "out/", false, true, false, true, 0);

            Thread backend = backendThread(() -> {
                OutputStream out = testBackend.getOutputStream();
                PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
                assertEquals('Q', in.nextMessage().type());
                out.write(new byte[]{'H', 0, 0, 0, 7, 0, 0, 0});
                byte[] row = "1|alice\n".getBytes(StandardCharsets.US_ASCII);
                out.write('d');
                out.write(intBytes(4 + row.length));
                out.write(row);
                out.flush();
                testBackend.close();
            });

            S3CopySimulator.runUnload(simClient, simBackend, spec, s3, 'I');
            joinBackend(backend);

            assertEquals(1, deleted.size());
            assertEquals("out/0000_part_00", deleted.get(0));

            PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
            PostgresWireDecoder.FrontendMessage err = in.nextMessage();
            assertEquals('E', err.type());
            String body = new String(err.body(), StandardCharsets.UTF_8);
            assertTrue(body.contains("backend closed mid-stream"), body);
            assertEquals('Z', in.nextMessage().type());
        } finally {
            S3CopySimulator.UNLOAD_TARGET_FILE_BYTES = saved;
        }
    }

    /** Play a minimal happy-path PostgreSQL backend: 'G' then, after CopyDone, 'C' and 'Z'. */
    private void playHappyBackend(ByteArrayOutputStream capturedCopyData) throws IOException {
        OutputStream out = testBackend.getOutputStream();
        PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
        PostgresWireDecoder.FrontendMessage query = in.nextMessage();
        assertEquals('Q', query.type());
        // CopyInResponse 'G': body = format(1) + columnCount(2) = 3 bytes, all zero => length 7
        out.write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
        out.flush();
        while (true) {
            PostgresWireDecoder.FrontendMessage m = in.nextMessage();
            if (m == null || m.type() == 'c') {
                break;
            }
            if (m.type() == 'd') {
                capturedCopyData.write(m.body());
            }
        }
        byte[] tag = "COPY 1\0".getBytes(StandardCharsets.US_ASCII);
        out.write('C');
        out.write(intBytes(4 + tag.length));
        out.write(tag);
        out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
        out.flush();
    }

    private String captureFabricatedThenComplete() throws IOException {
        OutputStream out = testBackend.getOutputStream();
        PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
        PostgresWireDecoder.FrontendMessage q = in.nextMessage();
        assertEquals('Q', q.type());
        out.write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
        out.flush();
        while (true) {
            PostgresWireDecoder.FrontendMessage m = in.nextMessage();
            if (m == null || m.type() == 'c') {
                break;
            }
        }
        out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
        out.flush();
        return q.getSql();
    }

    @Test
    void loadsSingleKeyAsPipeTextAndForwardsCommandComplete() throws Exception {
        when(s3.objectExists("wh", "d/a.txt")).thenReturn(true);
        when(s3.getObject("wh", "d/a.txt")).thenReturn(
                new S3Object("wh", "d/a.txt", "1|alice\n2|bob\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "people", List.of(), "wh", "d/a.txt", "|", 0, false, false, null);

        ByteArrayOutputStream copyData = new ByteArrayOutputStream();
        Thread backend = backendThread(() -> playHappyBackend(copyData));

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

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

        AtomicReference<String> fabricated = new AtomicReference<>();
        Thread backend = backendThread(() -> fabricated.set(captureFabricatedThenComplete()));

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        String sql = fabricated.get();
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

        AtomicReference<String> fabricated = new AtomicReference<>();
        Thread backend = backendThread(() -> fabricated.set(captureFabricatedThenComplete()));

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        String sql = fabricated.get();
        assertTrue(sql.contains("COPY t (a, b) FROM STDIN"), sql);
        assertTrue(sql.toUpperCase().contains("FORMAT CSV"), sql);
        assertTrue(sql.contains("DELIMITER ','"), sql);
    }

    @Test
    void nullAsBackslashNIsPassedThroughLiterally() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "x\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k", "|", 0, false, false, "\\N");

        AtomicReference<String> fabricated = new AtomicReference<>();
        Thread backend = backendThread(() -> fabricated.set(captureFabricatedThenComplete()));

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        String sql = fabricated.get();
        assertTrue(sql.contains("NULL '\\N'"), sql);
        assertFalse(sql.contains("NULL '\\\\N'"), sql);
    }

    @Test
    void concatenatesPrefixObjectsInKeyOrderAndSkipsHeaderOnFirstOnly() throws Exception {
        when(s3.objectExists("wh", "p/")).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("p/"), isNull(), anyInt(), any(), any())).thenReturn(
                new S3Service.ListObjectsResult(List.of(
                        new S3Object("wh", "p/1", "h1|h2\n1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"),
                        new S3Object("wh", "p/2", "2|b\n".getBytes(StandardCharsets.US_ASCII), "text/plain")),
                        List.of(), false, null));
        when(s3.getObject("wh", "p/1")).thenReturn(
                new S3Object("wh", "p/1", "h1|h2\n1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        when(s3.getObject("wh", "p/2")).thenReturn(
                new S3Object("wh", "p/2", "2|b\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "p/", "|", 1, false, false, null);

        ByteArrayOutputStream copyData = new ByteArrayOutputStream();
        Thread backend = backendThread(() -> playHappyBackend(copyData));
        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

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
        Thread backend = backendThread(() -> playHappyBackend(copyData));
        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertEquals("1|a\n2|b\n", copyData.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void deniedBucketSendsInsufficientPrivilegeErrorResponse() throws Exception {
        doThrow(new AwsException("AccessDenied", "no", 403))
                .when(s3).authorizeAnonymousListBucket("wh");
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k", "|", 0, false, false, null);

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage err = in.nextMessage();
        assertEquals('E', err.type());
        assertTrue(new String(err.body(), StandardCharsets.US_ASCII).contains("42501"));
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void missingKeySendsNotFoundErrorResponse() throws Exception {
        when(s3.objectExists("wh", "missing")).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("missing"), isNull(), anyInt(), any(), any())).thenReturn(
                new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "missing", "|", 0, false, false, null);

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage err = in.nextMessage();
        assertEquals('E', err.type());
        assertTrue(new String(err.body(), StandardCharsets.US_ASCII).contains("not found"));
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void backendErrorInsteadOfCopyInIsForwardedToClient() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "1\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "nosuch", List.of(), "wh", "k", "|", 0, false, false, null);

        Thread backend = backendThread(() -> {
            new PostgresWireDecoder(testBackend.getInputStream()).nextMessage();
            byte[] msg = "SERROR\0C42P01\0Mrelation \"nosuch\" does not exist\0\0".getBytes(StandardCharsets.US_ASCII);
            OutputStream out = testBackend.getOutputStream();
            out.write('E');
            out.write(intBytes(4 + msg.length));
            out.write(msg);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
            out.flush();
        });

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void midCopyFailureSendsExactlyOneErrorAndReadyForQuery() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenThrow(new AwsException("InternalError", "boom", 500));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k", "|", 0, false, false, null);

        Thread backend = backendThread(() -> {
            OutputStream out = testBackend.getOutputStream();
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            assertEquals('Q', in.nextMessage().type());
            out.write(new byte[]{'G', 0, 0, 0, 7, 0, 0, 0});
            out.flush();
            // wait for the simulator's CopyFail 'f'
            PostgresWireDecoder.FrontendMessage m;
            while ((m = in.nextMessage()) != null && m.type() != 'f') {
                // discard any CopyData already buffered
            }
            assertEquals('f', m == null ? 0 : m.type());
            byte[] err = "SERROR\0C57014\0MCOPY aborted\0\0".getBytes(StandardCharsets.US_ASCII);
            out.write('E');
            out.write(intBytes(4 + err.length));
            out.write(err);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
            out.flush();
        });

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
        testClient.setSoTimeout(400);
        assertThrows(SocketTimeoutException.class, () -> testClient.getInputStream().read(),
                "no second response should follow");
    }

    @Test
    void backendEofBeforeCopyInResponseSendsErrorToClient() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "1\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k", "|", 0, false, false, null);

        Thread backend = backendThread(() -> {
            new PostgresWireDecoder(testBackend.getInputStream()).nextMessage();
            testBackend.close();
        });

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);
        assertTrue(handled);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void synthesizedErrorReportsFailedTransactionStatusWhenInABlock() throws Exception {
        when(s3.objectExists("wh", "missing")).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("missing"), isNull(), anyInt(), any(), any())).thenReturn(
                new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "missing", "|", 0, false, false, null);

        Thread backend = backendThread(() -> {
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            PostgresWireDecoder.FrontendMessage q = in.nextMessage();
            assertEquals('Q', q.type());
            assertTrue(q.getSql().contains("FLOCI_ABORT_TX"));
            OutputStream out = testBackend.getOutputStream();
            byte[] err = "SERROR\0C42601\0Msyntax error\0\0".getBytes(StandardCharsets.US_ASCII);
            out.write('E');
            out.write(intBytes(4 + err.length));
            out.write(err);
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'E'});
            out.flush();
        });

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'T');
        joinBackend(backend);

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        PostgresWireDecoder.FrontendMessage ready = in.nextMessage();
        assertEquals('Z', ready.type());
        assertEquals('E', (char) ready.body()[0]);
    }

    @Test
    void paginatesPrefixListingAcrossMultiplePages() throws Exception {
        when(s3.objectExists("wh", "multi/")).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("multi/"), isNull(), anyInt(), isNull(), isNull())).thenReturn(
                new S3Service.ListObjectsResult(List.of(
                        new S3Object("wh", "multi/1", "1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain")),
                        List.of(), true, "tok-1"));
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("multi/"), isNull(), anyInt(), eq("tok-1"), isNull())).thenReturn(
                new S3Service.ListObjectsResult(List.of(
                        new S3Object("wh", "multi/2", "2|b\n".getBytes(StandardCharsets.US_ASCII), "text/plain")),
                        List.of(), false, null));

        when(s3.getObject("wh", "multi/1")).thenReturn(
                new S3Object("wh", "multi/1", "1|a\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        when(s3.getObject("wh", "multi/2")).thenReturn(
                new S3Object("wh", "multi/2", "2|b\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));

        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "multi/", "|", 0, false, false, null);

        ByteArrayOutputStream copyData = new ByteArrayOutputStream();
        Thread backend = backendThread(() -> playHappyBackend(copyData));
        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);

        assertEquals("1|a\n2|b\n", copyData.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void backendTimeoutAwaitingCopyInResponseClosesBackend() throws Exception {
        when(s3.objectExists("wh", "k")).thenReturn(true);
        when(s3.getObject("wh", "k")).thenReturn(
                new S3Object("wh", "k", "1\n".getBytes(StandardCharsets.US_ASCII), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "k", "|", 0, false, false, null);

        simBackend.setSoTimeout(100);

        Thread backend = backendThread(() -> {
            new PostgresWireDecoder(testBackend.getInputStream()).nextMessage();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        boolean handled = S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'I');
        joinBackend(backend);
        assertTrue(handled);

        assertTrue(simBackend.isClosed(), "simBackend must be closed upon timeout");

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        assertEquals('E', in.nextMessage().type());
        assertEquals('Z', in.nextMessage().type());
    }

    @Test
    void failedBackendTransactionAbortClosesBackendAndClientWithoutReportingFailedTransaction() throws Exception {
        when(s3.objectExists("wh", "missing")).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("missing"), isNull(), anyInt(), any(), any())).thenReturn(
                new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "missing", "|", 0, false, false, null);

        // Backend closes socket when abort query arrives, simulating dropped connection
        Thread backend = backendThread(() -> {
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            PostgresWireDecoder.FrontendMessage q = in.nextMessage();
            assertEquals('Q', q.type());
            assertTrue(q.getSql().contains("FLOCI_ABORT_TX"));
            testBackend.close();
        });

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'T');
        joinBackend(backend);

        assertTrue(simBackend.isClosed(), "simBackend must be closed upon synchronization failure");
        assertTrue(simClient.isClosed(), "simClient must be closed upon synchronization failure");

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage err = in.nextMessage();
        assertEquals('E', err.type());
        assertNull(in.nextMessage(), "Client must not receive an unconfirmed ReadyForQuery");
    }

    @Test
    void backendReturnsNonETransactionStatusAfterAbortClosesBackendAndClient() throws Exception {
        when(s3.objectExists("wh", "missing")).thenReturn(false);
        when(s3.listObjectsWithPrefixes(eq("wh"), eq("missing"), isNull(), anyInt(), any(), any())).thenReturn(
                new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        CopyStatementParser.S3CopyFrom spec = new CopyStatementParser.S3CopyFrom(
                "t", List.of(), "wh", "missing", "|", 0, false, false, null);

        // Fake backend returns ReadyForQuery with 'T' (e.g. abort query unexpectedly succeeded)
        Thread backend = backendThread(() -> {
            PostgresWireDecoder in = new PostgresWireDecoder(testBackend.getInputStream());
            PostgresWireDecoder.FrontendMessage q = in.nextMessage();
            assertEquals('Q', q.type());
            assertTrue(q.getSql().contains("FLOCI_ABORT_TX"));
            OutputStream out = testBackend.getOutputStream();
            out.write(new byte[]{'Z', 0, 0, 0, 5, 'T'});
            out.flush();
        });

        S3CopySimulator.runCopyFrom(simClient, simBackend, spec, s3, 'T');
        joinBackend(backend);

        assertTrue(simBackend.isClosed(), "simBackend must be closed when abort returns non-E status");
        assertTrue(simClient.isClosed(), "simClient must be closed when abort returns non-E status");

        PostgresWireDecoder in = new PostgresWireDecoder(testClient.getInputStream());
        PostgresWireDecoder.FrontendMessage err = in.nextMessage();
        assertEquals('E', err.type());
        assertNull(in.nextMessage(), "Client must not receive an unconfirmed ReadyForQuery");
    }
}
