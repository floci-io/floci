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
    void extractsBackslashEntriesAsNestedPaths(@TempDir Path target) throws IOException {
        // PowerShell Compress-Archive writes '\' separators (issue #1198).
        byte[] zip = zipWith("wwwroot\\_framework\\blazor.web.js", "// js");

        extractor.extractTo(zip, target);

        // The fix: the file lands at a real nested path, not a single flat file.
        Path nested = target.resolve("wwwroot").resolve("_framework").resolve("blazor.web.js");
        assertTrue(Files.isRegularFile(nested), "nested path should exist after extraction");
        assertEquals("// js", Files.readString(nested));
        // And NOT as a literal file named with backslashes.
        assertFalse(Files.exists(target.resolve("wwwroot\\_framework\\blazor.web.js")),
                "backslashed flat file must not be created");
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
        // "..\..\evil" normalizes to "../../evil" and must be skipped, not written.
        byte[] zip = zipWith("..\\..\\evil.sh", "rm -rf");

        extractor.extractTo(zip, target);

        assertFalse(Files.exists(target.getParent().getParent().resolve("evil.sh")),
                "traversal entry must not escape the target dir");
    }
}
