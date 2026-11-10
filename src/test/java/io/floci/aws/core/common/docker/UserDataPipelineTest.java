package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDataPipelineTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesConsumedScriptUploadBeforeExecutingExactlyOnce(boolean consumeCompletely) {
        String script = "#!/bin/sh\nprintf 'bootstrap complete\\n'\n";
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        List<InputStream> streams = new ArrayList<>();
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
            streams.add(invocation.getArgument(0));
            return copyCmd;
        });
        AtomicInteger attempts = new AtomicInteger();
        when(copyCmd.exec()).thenAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            InputStream stream = streams.getLast();
            if (attempt == 1 && !consumeCompletely) {
                assertEquals(513, stream.readNBytes(513).length, "consume the tar header and part of the script");
            } else {
                byte[] archive = stream.readAllBytes();
                try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
                    TarArchiveEntry entry = tar.getNextEntry();
                    assertNotNull(entry, "every attempt must begin with a complete tar header");
                    assertEquals("user-data.sh", entry.getName());
                    assertEquals(0755, entry.getMode() & 0777);
                    assertArrayEquals(script.getBytes(StandardCharsets.UTF_8), tar.readAllBytes());
                    assertNull(tar.getNextEntry(), "the archive holds only the user-data script");
                }
            }
            if (attempt == 1) {
                throw new RuntimeException(new IOException("Connection reset"));
            }
            return null;
        });
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, "container-id", 0L,
                "bootstrap complete\n", "");
        List<String> output = new ArrayList<>();

        UserDataPipeline.ExecutionResult result = UserDataPipeline.executeUserData(docker, "container-id",
                "test instance", script, Duration.ofSeconds(1), output::add, null, 0L);

        assertTrue(result.isSuccess(), result.getFailureMessage());
        assertEquals(2, attempts.get());
        assertEquals(2, streams.size());
        assertNotSame(streams.getFirst(), streams.get(1), "a consumed tar stream cannot be replayed");
        assertEquals(List.of(List.of(UserDataPipeline.USER_DATA_SCRIPT_PATH)), commands);
        assertEquals(List.of("bootstrap complete"), output);
        verify(copyCmd, times(2)).withRemotePath("/var/lib");
        verify(docker).execStartCmd("exec-container-id");
    }

    @Test
    void daemonRejectionDoesNotRetryScriptUploadOrExecuteTheScript() {
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        when(copyCmd.exec()).thenThrow(new NotFoundException("Could not find the file /var/lib in container"));

        UserDataPipeline.ExecutionResult result = UserDataPipeline.executeUserData(docker, "container-id",
                "test instance", "#!/bin/sh\nexit 0\n", Duration.ofSeconds(1), null, null);

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureMessage().contains("Could not find the file /var/lib in container"));
        verify(copyCmd).exec();
        verify(docker, never()).execCreateCmd(anyString());
    }

    @Test
    void extractsPlainShellScript() {
        String script = "#!/bin/bash\necho 'hello world'\n";
        List<String> scripts = UserDataPipeline.extractShellScripts(script);
        assertEquals(1, scripts.size());
        assertEquals(script, scripts.getFirst());
    }

    @Test
    void extractsBase64EncodedScript() {
        String script = "#!/bin/bash\necho 'from base64'\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        List<String> scripts = UserDataPipeline.extractShellScripts(encoded);
        assertEquals(1, scripts.size());
        assertEquals(script, scripts.getFirst());
    }

    @Test
    void extractsGzipBase64EncodedScript() throws IOException {
        String script = "#!/bin/bash\necho 'from gzip base64'\n";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String encoded = Base64.getEncoder().encodeToString(baos.toByteArray());
        List<String> scripts = UserDataPipeline.extractShellScripts(encoded);
        assertEquals(1, scripts.size());
        assertEquals(script, scripts.getFirst());
    }

    @Test
    void extractsOnlyShellScriptPartsFromMultipartMime() {
        String mime = "Content-Type: multipart/mixed; boundary=\"==BOUNDARY==\"\n"
                + "MIME-Version: 1.0\n"
                + "\n"
                + "--==BOUNDARY==\n"
                + "Content-Type: text/cloud-config; charset=\"us-ascii\"\n"
                + "\n"
                + "#cloud-config\n"
                + "package_upgrade: true\n"
                + "--==BOUNDARY==\n"
                + "Content-Type: text/x-shellscript; charset=\"us-ascii\"\n"
                + "\n"
                + "#!/bin/bash\n"
                + "echo 'from mime'\n"
                + "--==BOUNDARY==--\n";

        List<String> scripts = UserDataPipeline.extractShellScripts(mime);
        assertEquals(1, scripts.size());
        assertTrue(scripts.getFirst().contains("echo 'from mime'"));
    }

    @Test
    void returnsEmptyForNullOrBlankOrNonScript() {
        assertTrue(UserDataPipeline.extractShellScripts(null).isEmpty());
        assertTrue(UserDataPipeline.extractShellScripts("").isEmpty());
        assertTrue(UserDataPipeline.extractShellScripts("   ").isEmpty());
        assertTrue(UserDataPipeline.extractShellScripts("plain text without shebang").isEmpty());
    }

    @Test
    void boundedOutputAppendsAndTruncates() throws IOException {
        UserDataPipeline.BoundedOutput bounded = new UserDataPipeline.BoundedOutput(20);
        bounded.write("12345".getBytes(StandardCharsets.UTF_8));
        bounded.write("67890".getBytes(StandardCharsets.UTF_8));
        assertEquals("1234567890", bounded.utf8Tail());
        assertFalse(bounded.truncated());

        bounded.write("1234567890EXTRA".getBytes(StandardCharsets.UTF_8));
        assertTrue(bounded.truncated());
        assertEquals(25, bounded.totalBytes());
        assertTrue(bounded.utf8Tail().endsWith("EXTRA"));
    }

    @Test
    void boundedOutputHandlesMultiByteUtf8Splits() throws IOException {
        UserDataPipeline.BoundedOutput bounded = new UserDataPipeline.BoundedOutput(5);
        byte[] euro = "\u20AC".getBytes(StandardCharsets.UTF_8); // 3 bytes
        byte[] smile = "\uD83D\uDE00".getBytes(StandardCharsets.UTF_8); // 4 bytes
        bounded.write(euro);
        bounded.write(smile);
        assertTrue(bounded.truncated());
        // Tail should safely decode without corrupted leading continuation byte
        assertNotNull(bounded.utf8Tail());
    }

    @Test
    void executionResultFormats() {
        UserDataPipeline.ExecutionResult success = UserDataPipeline.ExecutionResult.success(0L, "done");
        assertTrue(success.isSuccess());
        assertEquals(0L, success.getExitCode());
        assertFalse(success.isTimedOut());

        UserDataPipeline.ExecutionResult failed = UserDataPipeline.ExecutionResult.failed(1L, 1, 2, "error log");
        assertFalse(failed.isSuccess());
        assertEquals(1L, failed.getExitCode());
        assertNotNull(failed.getFailureMessage());
        assertTrue(failed.getFailureMessage().contains("part 1/2 failed with exit code 1"));

        UserDataPipeline.ExecutionResult timeout = UserDataPipeline.ExecutionResult.timedOut(2, 2, 30L, "stuck");
        assertFalse(timeout.isSuccess());
        assertTrue(timeout.isTimedOut());
        assertNotNull(timeout.getFailureMessage());
        assertTrue(timeout.getFailureMessage().contains("part 2/2 timed out after 30 minutes"));

        UserDataPipeline.ExecutionResult skipped = UserDataPipeline.ExecutionResult.skipped("no container");
        assertTrue(skipped.isSuccess());
        assertEquals(0L, skipped.getExitCode());
    }
}
