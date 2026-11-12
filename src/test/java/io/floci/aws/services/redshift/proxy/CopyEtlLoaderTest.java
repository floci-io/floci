package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopyEtlLoaderTest {
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

    private static void runLoad(String options, String data, long expectedCount, int expectedErrors, boolean failure)
            throws Exception {
        runLoad(options, data, expectedCount, expectedErrors, failure, 1207);
    }

    private static void runLoad(String options, String data, long expectedCount, int expectedErrors, boolean failure, int code)
            throws Exception {
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
                assertEquals(code == 1211 ? "" : "bad", error.rawLine());
                assertEquals(code == 1211 ? "" : "bad", error.rawFieldValue());
                assertEquals(code, error.code());
                assertEquals(42, error.context().sessionId());
                assertEquals(99, error.context().tableId());
            }
            assertEquals(options.contains("IGNOREBLANKLINES") ? 4 : options.contains("IGNOREHEADER") ? 3 : 2,
                    errors.getFirst().lineNumber());
        }
    }
}
