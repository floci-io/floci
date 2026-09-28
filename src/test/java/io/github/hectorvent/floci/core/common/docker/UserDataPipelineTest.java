package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDataPipelineTest {

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
