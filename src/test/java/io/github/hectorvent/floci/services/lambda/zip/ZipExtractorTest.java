package io.github.hectorvent.floci.services.lambda.zip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipExtractorTest {

    private final ZipExtractor extractor = new ZipExtractor();

    /** Build a ZIP whose entry names use the given separator, as PowerShell does. */
    private static byte[] zipWith(String entryName, String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    void extractsBackslashEntriesAsLiteralFilename(@TempDir Path target) throws IOException {
        // PowerShell 5 Compress-Archive writes '\' separators (issue #1198).
        // Real AWS Lambda does NOT normalize these — the entry extracts as a single
        // literal-named file. Floci must match AWS, not silently fix broken packages.
        byte[] zip = zipWith("wwwroot\\_framework\\blazor.web.js", "// js");

        extractor.extractTo(zip, target);

        // AWS-congruent: the backslashed entry lands as a literal filename, not nested.
        Path flat = target.resolve("wwwroot\\_framework\\blazor.web.js");
        assertTrue(Files.isRegularFile(flat),
                "backslashed entry must extract as a literal filename (matching AWS Lambda)");
        assertEquals("// js", Files.readString(flat));
        // It must NOT create a nested path.
        Path nested = target.resolve("wwwroot").resolve("_framework").resolve("blazor.web.js");
        assertFalse(Files.exists(nested),
                "backslashed entry must NOT create a nested path (AWS does not normalize)");
    }

    @Test
    void stillExtractsStandardForwardSlashEntries(@TempDir Path target) throws IOException {
        byte[] zip = zipWith("conf/app.css", "body{}");

        extractor.extractTo(zip, target);

        Path nested = target.resolve("conf").resolve("app.css");
        assertTrue(Files.isRegularFile(nested));
        assertEquals("body{}", Files.readString(nested));
    }

    @Test
    void rejectsBackslashTraversalEntries(@TempDir Path target) throws IOException {
        // "..\..\evil" contains ".." in the original entry name, so the traversal
        // guard catches it even without normalization.
        byte[] zip = zipWith("..\\..\\evil.sh", "rm -rf");

        extractor.extractTo(zip, target);

        assertFalse(Files.exists(target.getParent().getParent().resolve("evil.sh")),
                "traversal entry must not escape the target dir");
    }
}
