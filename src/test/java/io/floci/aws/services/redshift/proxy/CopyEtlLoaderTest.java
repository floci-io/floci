package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.redshift.proxy.CopyBulkLoader.Cursor;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopyEtlLoaderTest {
    @Test
    void bulkLoadingDecodesEscapedDecimalsUsingCatalogScales() throws Exception {
        S3Service s3 = mock(S3Service.class);
        when(s3.objectExists("bucket", "data")).thenReturn(true);
        when(s3.getObject("bucket", "data")).thenReturn(new S3Object("bucket", "data",
                "12\\.345\n\\0612.345\n12\\056345\n".getBytes(StandardCharsets.UTF_8), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY events FROM 's3://bucket/data'");
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("localhost", listener.getLocalPort()); Socket server = listener.accept()) {
            client.setSoTimeout(5000);
            server.setSoTimeout(5000);
            FakeCopyBackend fake = new FakeCopyBackend(server);
            fake.countRowsWith(rows -> rows.lines().count());
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    fake.serveUntilSync(copied, sql -> { }, sql -> sql.startsWith("SELECT a.attname")
                            ? List.of(Arrays.asList("amount", null, "1700", "2")) : List.of());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            CopyBackendSession backend = new CopyBackendSession(client, frame -> { });
            assertEquals(3, CopyEtlLoader.execute(backend, spec, s3, null, null, List.of(), false));
            backend.finish(false);
            backend.synchronize();
            serving.get(5, TimeUnit.SECONDS);
            assertEquals("12.34\n12.34\n12.34\n", copied.toString(StandardCharsets.UTF_8));
            assertEquals(1, fake.copyCommands());
        }
    }

    @Test
    void bulkDiagnosticsMapPastSkippedHeadersAndBlankLines() throws Exception {
        runLoad("IGNOREHEADER 1 IGNOREBLANKLINES", "header\n\n1\nbad\n2\n", 0, 1, true);
    }

    @Test
    void bulkDiagnosticsMapPastQuotedCsvNewlines() throws Exception {
        // PostgreSQL does not count a quoted LF before the first record establishes its line-ending mode.
        runLoad("CSV", "1,\"first\nsecond\"\nbad,value\n", 0, 1, true, 1207, "bad,value", 3, 2);
    }

    @Test
    void bulkDiagnosticsMapQuotedNewlinesAfterTheFirstCsvRecord() throws Exception {
        runLoad("CSV", "0,simple\n1,\"first\nsecond\"\nbad,value\n", 0, 1, true, 1207, "bad,value", 4);
    }

    @Test
    void bulkDiagnosticsMapQuotedCsvCarriageReturns() throws Exception {
        runLoad("CSV", "1,\"first\r\nsecond\"\r\nbad,value\r\n", 0, 1, true, 1207, "bad,value", 3);
    }

    @Test
    void bulkBackendFailurePrecedesALaterLocalRecordError() throws Exception {
        runLoad("", "bad\n" + "1\n".repeat(5000) + "\n", 0, 1, true, 1207, "bad", 1);
    }

    @Test
    void bulkDiagnosticsMapPastEscapedTextNewlines() throws Exception {
        runLoad("", "1|first\\\nsecond\nbad|value\n", 0, 1, true, 1207, "bad|value", 3);
    }
    @Test
    void diagnosticsDoNotTurnAnOrdinaryLoadIntoPerRowBackendCommands() throws Exception {
        S3Service s3 = mock(S3Service.class);
        when(s3.objectExists("bucket", "data")).thenReturn(true);
        when(s3.getObject("bucket", "data")).thenReturn(new S3Object("bucket", "data",
                "1\n2\n3\n".getBytes(StandardCharsets.UTF_8), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY events FROM 's3://bucket/data'");
        List<CopyLoadError> errors = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("localhost", listener.getLocalPort()); Socket server = listener.accept()) {
            client.setSoTimeout(5000);
            server.setSoTimeout(5000);
            FakeCopyBackend fake = new FakeCopyBackend(server);
            fake.countRowsWith(rows -> rows.lines().count());
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    fake.serveUntilSync(new ByteArrayOutputStream(), sql -> { },
                            sql -> sql.startsWith("SELECT (SELECT oid") ? List.of(List.of("10", "42", "99")) : List.of());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            CopyBackendSession backend = new CopyBackendSession(client, frame -> { });
            backend.loadErrorSink(errors::add);
            assertEquals(3, CopyEtlLoader.execute(backend, spec, s3, null, null, List.of(), false));
            backend.finish(false);
            backend.synchronize();
            serving.get(5, TimeUnit.SECONDS);
            assertEquals(1, fake.copyCommands());
            assertEquals(0, fake.rowSavepoints());
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void ignoreAllErrorsLoadsGoodRecordsAndLogsRejectedRows() throws Exception {
        runLoad("IGNOREHEADER 1 IGNOREALLERRORS", "header\n1\nbad\n2\nbad\n3\n", 3, 2, false);
    }

    @Test
    void firstErrorIsLoggedEvenWhenTheWholeLoadFails() throws Exception {
        runLoad("", "1\nbad\n2\n", 0, 1, true);
    }

    @Test
    void maxErrorStillFailsAtItsThreshold() throws Exception {
        runLoad("MAXERROR 2", "1\nbad\n2\nbad\n", 0, 2, true);
    }

    @Test
    void ignoreBlankLinesKeepsPhysicalErrorLineNumbers() throws Exception {
        runLoad("IGNOREBLANKLINES IGNOREALLERRORS", "\n1\n\r\nbad\n2\n", 2, 1, false);
    }

    @Test
    void blankLinesFailByDefaultAndAreLogged() throws Exception {
        runLoad("", "1\n\n2\n", 0, 1, true, 1211);
    }

    @Test
    void ignoreAllErrorsToleratesBlankLinesWhenNotBlankSkipped() throws Exception {
        runLoad("IGNOREALLERRORS", "1\n\n2\n", 2, 1, false, 1211, "", 2);
    }

    @Test
    void ignoreAllErrorsDecompressesGzipThroughCursor() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("1\nbad\n2\n".getBytes(StandardCharsets.UTF_8));
        }
        S3Service s3 = mock(S3Service.class);
        when(s3.objectExists("bucket", "data")).thenReturn(true);
        when(s3.getObject("bucket", "data")).thenReturn(new S3Object("bucket", "data",
                compressed.toByteArray(), "application/gzip"));
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY events FROM 's3://bucket/data' GZIP IGNOREALLERRORS");
        List<CopyLoadError> errors = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("localhost", listener.getLocalPort()); Socket server = listener.accept()) {
            client.setSoTimeout(5000);
            server.setSoTimeout(5000);
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    FakeCopyBackend fake = new FakeCopyBackend(server);
                    fake.rejectRows(row -> row.startsWith("bad"));
                    fake.serveUntilSync(new ByteArrayOutputStream(), sql -> { },
                            sql -> sql.startsWith("SELECT (SELECT oid") ? List.of(List.of("10", "42", "99")) : List.of());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            CopyBackendSession backend = new CopyBackendSession(client, frame -> { });
            backend.loadErrorSink(errors::add);
            assertEquals(2, CopyEtlLoader.execute(backend, spec, s3, null, null, List.of(), false));
            backend.finish(false);
            backend.synchronize();
            serving.get(5, TimeUnit.SECONDS);
            assertEquals(1, errors.size());
            assertEquals("bad", errors.getFirst().rawLine());
            assertEquals(2, errors.getFirst().lineNumber());
        }
    }

    @Test
    void cursorReleasesHeapPermitsEvenWhenRecordParsingFails() throws Exception {
        int permitsBefore = S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits();
        try {
            runLoad("", "1\n\n2\n", 0, 1, true, 1211);
        } finally {
            assertEquals(permitsBefore, S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits());
        }
    }

    @Test
    void writeRecordChunksLongRowsAcrossPostgresCopyDataFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] longRecord = "a".repeat(20000).getBytes(StandardCharsets.UTF_8);
        CopyBulkLoader.writeRecord(output, longRecord);
        byte[] bytes = output.toByteArray();
        assertEquals(20015, bytes.length);
        assertEquals('d', bytes[0]);
        assertEquals('d', bytes[8197]);
        assertEquals('d', bytes[8197 * 2]);
    }

    @Test
    void cursorIteratesTransformedRecordsAndTracksLineAndRaw() throws Exception {
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY events FROM 's3://bucket/data' CSV IGNOREHEADER 1");
        byte[] data = "h1,h2\n1,first\n2,second\n".getBytes(StandardCharsets.UTF_8);
        try (Cursor cursor = new Cursor(spec, data, null, null, null, null)) {
            byte[] first = cursor.next();
            assertNotNull(first);
            assertEquals("1,first\n", new String(first, StandardCharsets.UTF_8));
            assertEquals(2, cursor.line());
            assertEquals("1,first\n", new String(cursor.raw(), StandardCharsets.UTF_8));

            byte[] second = cursor.next();
            assertNotNull(second);
            assertEquals("2,second\n", new String(second, StandardCharsets.UTF_8));
            assertEquals(3, cursor.line());
            assertEquals("2,second\n", new String(cursor.raw(), StandardCharsets.UTF_8));

            assertNull(cursor.next());
        }
    }

    private static void runLoad(String options, String data, long expectedCount, int expectedErrors, boolean failure)
            throws Exception {
        runLoad(options, data, expectedCount, expectedErrors, failure, 1207);
    }

    private static void runLoad(String options, String data, long expectedCount, int expectedErrors, boolean failure, int code)
            throws Exception {
        runLoad(options, data, expectedCount, expectedErrors, failure, code, code == 1211 ? "" : "bad",
                options.contains("IGNOREBLANKLINES") ? 4 : options.contains("IGNOREHEADER") ? 3 : 2);
    }

    private static void runLoad(String options, String data, long expectedCount, int expectedErrors, boolean failure,
                                 int code, String expectedRaw, long expectedLine) throws Exception {
        runLoad(options, data, expectedCount, expectedErrors, failure, code, expectedRaw, expectedLine, 0);
    }

    private static void runLoad(String options, String data, long expectedCount, int expectedErrors, boolean failure,
                                 int code, String expectedRaw, long expectedLine, int backendLine) throws Exception {
        S3Service s3 = mock(S3Service.class);
        when(s3.objectExists("bucket", "data")).thenReturn(true);
        when(s3.getObject("bucket", "data")).thenReturn(new S3Object("bucket", "data",
                data.getBytes(StandardCharsets.UTF_8), "text/plain"));
        CopyStatementParser.S3CopyFrom spec = (CopyStatementParser.S3CopyFrom) CopyStatementParser.parse(
                "COPY events FROM 's3://bucket/data' " + options);
        List<CopyLoadError> errors = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket(0);
                Socket client = new Socket("localhost", listener.getLocalPort()); Socket server = listener.accept()) {
            client.setSoTimeout(5000);
            server.setSoTimeout(5000);
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    FakeCopyBackend fake = new FakeCopyBackend(server);
                    fake.rejectRows(row -> row.startsWith("bad"));
                    fake.reportedErrorLine(backendLine);
                    fake.serveUntilSync(new ByteArrayOutputStream(), sql -> { },
                            sql -> sql.startsWith("SELECT (SELECT oid") ? List.of(List.of("10", "42", "99")) : List.of());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            CopyBackendSession backend = new CopyBackendSession(client, frame -> { });
            backend.loadErrorSink(errors::add);
            if (failure) {
                S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                        () -> CopyEtlLoader.execute(backend, spec, s3, null, null, List.of(), false));
                assertEquals(code == 1211 ? "22P04" : "22P02", error.sqlState());
            } else {
                assertEquals(expectedCount, CopyEtlLoader.execute(backend, spec, s3, null, null, List.of(), false));
            }
            backend.finish(failure);
            backend.synchronize();
            serving.get(5, TimeUnit.SECONDS);
            assertEquals(expectedErrors, errors.size());
            for (CopyLoadError error : errors) {
                assertEquals("s3://bucket/data", error.filename());
                assertEquals(code == 1211 ? "" : "id", error.column());
                assertEquals(expectedRaw, error.rawLine());
                assertEquals(code == 1211 ? "" : "bad", error.rawFieldValue());
                assertEquals(code, error.code());
                assertEquals(42, error.context().sessionId());
                assertEquals(99, error.context().tableId());
            }
            assertEquals(expectedLine, errors.getFirst().lineNumber());
        }
    }
}
