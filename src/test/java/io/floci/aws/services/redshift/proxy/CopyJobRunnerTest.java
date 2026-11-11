package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class CopyJobRunnerTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/Load";
    private static final String COPY = "COPY t FROM 's3://bucket/prefix/' IAM_ROLE '" + ROLE + "' CSV";

    @Test
    void missingOrHiddenJobFailsBeforeReadingS3() throws Exception {
        S3Service s3 = mock(S3Service.class);
        withBackend(new ByteArrayOutputStream(), new ArrayList<>(), sql -> List.of(), backend -> {
            S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> CopyJobRunner.load(backend, "missing", s3, null, ACCOUNT, List.of()));
            assertEquals("42704", error.sqlState());
        });
        verifyNoInteractions(s3);
    }

    @Test
    void skipsOldAndCheckpointedFilesAndLoadsOnlyTheUnseenObjectAcrossPagesAsItsOwner() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = jobIam();
        S3Object old = object("prefix/old", 10);
        S3Object seen = object("prefix/seen", 30);
        S3Object fresh = object("prefix/a'b", 40);
        when(s3.listObjectsWithPrefixes("bucket", "prefix/", null, 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(old, seen), List.of(), true, "page2"));
        when(s3.listObjectsWithPrefixes("bucket", "prefix/", null, 1000, "page2", null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(fresh), List.of(), false, null));
        when(s3.objectExists("bucket", "prefix/a'b")).thenReturn(true);
        when(s3.getObject("bucket", "prefix/a'b")).thenReturn(fresh);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        List<String> statements = new ArrayList<>();
        withBackend(copied, statements, sql -> jobRows(sql, List.of(List.of("prefix/seen"))),
                backend -> assertEquals(1, runJob(backend, s3, iam)));
        assertEquals("1\n", copied.toString(StandardCharsets.UTF_8));
        assertEquals(1, statements.stream().filter(sql -> sql.contains("reserve_copy_job_file")).count());
        assertTrue(statements.contains("SET LOCAL ROLE \"job_owner\""));
        assertTrue(statements.contains("SET LOCAL ROLE \"admin\""));
    }

    @Test
    void loadsMoreThanOneHundredFilesInOneRun() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = jobIam();
        List<S3Object> files = IntStream.range(0, 101).mapToObj(index -> object("prefix/" + index, 30)).toList();
        when(s3.listObjectsWithPrefixes("bucket", "prefix/", null, 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(files.subList(0, 100), List.of(), true, "page2"));
        when(s3.listObjectsWithPrefixes("bucket", "prefix/", null, 1000, "page2", null))
                .thenReturn(new S3Service.ListObjectsResult(files.subList(100, 101), List.of(), false, null));
        for (S3Object file : files) {
            when(s3.getObject("bucket", file.getKey())).thenReturn(file);
        }
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        withBackend(copied, new ArrayList<>(), sql -> jobRows(sql, List.of()),
                backend -> assertEquals(101, runJob(backend, s3, iam)));
        assertEquals("1\n".repeat(101), copied.toString(StandardCharsets.UTF_8));
    }

    @Test
    void isolatesFailedOrOverwrittenObjectAndContinuesWithValidFiles() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = jobIam();
        S3Object overwritten = object("prefix/a", 30);
        overwritten.setETag("\"etag-listed\"");
        S3Object replaced = object("prefix/a", 31);
        replaced.setETag("\"etag-new\"");
        S3Object versioned = object("prefix/b", 35);
        versioned.setETag("\"etag-v1\"");
        versioned.setVersionId("v1");
        when(s3.listObjectsWithPrefixes("bucket", "prefix/", null, 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(overwritten, versioned), List.of(), false, null));
        when(s3.getObject("bucket", "prefix/a")).thenReturn(replaced);
        when(s3.getObject("bucket", "prefix/b", "v1")).thenReturn(versioned);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        List<String> statements = new ArrayList<>();
        withBackend(copied, statements, sql -> jobRows(sql, List.of()),
                backend -> assertEquals(1, runJob(backend, s3, iam)));
        assertEquals("1\n", copied.toString(StandardCharsets.UTF_8));
        assertTrue(statements.stream().anyMatch(sql -> sql.startsWith("ROLLBACK TO SAVEPOINT floci_job_file_")));
        assertTrue(statements.stream().anyMatch(sql -> sql.contains("record_copy_job_file_error")
                && sql.contains("'prefix/a'")));
        verify(s3).getObject("bucket", "prefix/b", "v1");
    }

    private static long runJob(CopyBackendSession backend, S3Service s3, IamService iam) throws Exception {
        backend.begin(false);
        long count = CopyJobRunner.load(backend, "job", s3, iam, ACCOUNT, List.of(ROLE));
        backend.finish(false);
        return count;
    }

    private static IamService jobIam() {
        IamService iam = mock(IamService.class);
        IamRole role = new IamRole();
        role.setArn(ROLE);
        role.setAssumeRolePolicyDocument("""
                {"Statement":[{"Effect":"Allow","Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """);
        when(iam.findRole(ACCOUNT, "Load")).thenReturn(Optional.of(role));
        return iam;
    }

    private static List<List<String>> jobRows(String sql, List<List<String>> checkpoints) {
        if (sql.contains("copy_job_for_run")) {
            return List.of(List.of("1", COPY, "bucket", "prefix/", "20", "job_owner"));
        }
        if (sql.equals("SELECT current_user")) {
            return List.of(List.of("admin"));
        }
        if (sql.contains("copy_job_loaded_keys")) {
            return checkpoints;
        }
        if (sql.contains("reserve_copy_job_file")) {
            return List.of(List.of("t"));
        }
        if (sql.contains("record_copy_job_file_error")) {
            return List.of(List.of(""));
        }
        return List.of();
    }

    @FunctionalInterface
    private interface BackendAction {
        void run(CopyBackendSession backend) throws Exception;
    }

    private static void withBackend(ByteArrayOutputStream copied, List<String> statements,
                                    Function<String, List<List<String>>> rows, BackendAction action) throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
                Socket socket = new Socket("localhost", listener.getLocalPort());
                Socket server = listener.accept()) {
            socket.setSoTimeout(3000);
            socket.setTcpNoDelay(true);
            server.setTcpNoDelay(true);
            CompletableFuture<Void> response = CompletableFuture.runAsync(() -> {
                try {
                    new FakeCopyBackend(server).serveUntilSync(copied, statements::add, rows);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            CopyBackendSession backend = new CopyBackendSession(socket, frame -> { });
            action.run(backend);
            backend.synchronize();
            response.get(5, TimeUnit.SECONDS);
        }
    }

    private static S3Object object(String key, long modified) {
        S3Object object = new S3Object("bucket", key, "1\n".getBytes(StandardCharsets.UTF_8), "text/plain");
        object.setLastModified(Instant.ofEpochSecond(modified));
        return object;
    }
}
