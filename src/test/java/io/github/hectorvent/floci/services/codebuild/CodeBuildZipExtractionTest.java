package io.github.hectorvent.floci.services.codebuild;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source zips must extract with their unix modes intact: projects like the Landing
 * Zone Accelerator invoke bundled shell scripts and rely on the execute bit.
 */
class CodeBuildZipExtractionTest {

    @TempDir
    Path tempDir;

    @Test
    void extractZipPreservesExecutableBit() throws Exception {
        byte[] zip;
        try (var baos = new ByteArrayOutputStream()) {
            try (var zos = new ZipArchiveOutputStream(baos)) {
                ZipArchiveEntry script = new ZipArchiveEntry("lib/bash/bootstrap.sh");
                script.setUnixMode(0755);
                zos.putArchiveEntry(script);
                zos.write("#!/bin/bash\n".getBytes());
                zos.closeArchiveEntry();
                zos.putArchiveEntry(new ZipArchiveEntry("README.md"));
                zos.write("hello".getBytes());
                zos.closeArchiveEntry();
            }
            zip = baos.toByteArray();
        }
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null);
        runner.extractZip(zip, tempDir);

        Path script = tempDir.resolve("lib/bash/bootstrap.sh");
        assertEquals("#!/bin/bash\n", Files.readString(script));
        assertTrue(Files.isExecutable(script));
        assertFalse(Files.isExecutable(tempDir.resolve("README.md")));
    }
}
