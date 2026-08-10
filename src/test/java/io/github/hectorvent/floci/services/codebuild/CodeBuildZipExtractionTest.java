package io.github.hectorvent.floci.services.codebuild;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null);
        runner.extractZip(zip, tempDir);

        Path script = tempDir.resolve("lib/bash/bootstrap.sh");
        assertEquals("#!/bin/bash\n", Files.readString(script));
        assertTrue(Files.isExecutable(script));
        assertFalse(Files.isExecutable(tempDir.resolve("README.md")));
    }

    @Test
    void createTarFromDirCarriesFileModesIntoTheContainerArchive() throws Exception {
        byte[] zip;
        try (var baos = new ByteArrayOutputStream()) {
            try (var zos = new ZipArchiveOutputStream(baos)) {
                ZipArchiveEntry script = new ZipArchiveEntry("lib/bash/bootstrap.sh");
                script.setUnixMode(0755);
                zos.putArchiveEntry(script);
                zos.write("#!/bin/bash\n".getBytes());
                zos.closeArchiveEntry();
                ZipArchiveEntry readme = new ZipArchiveEntry("README.md");
                readme.setUnixMode(0644);
                zos.putArchiveEntry(readme);
                zos.write("hello".getBytes());
                zos.closeArchiveEntry();
            }
            zip = baos.toByteArray();
        }
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null);
        Path sourceDir = Files.createDirectories(tempDir.resolve("source"));
        runner.extractZip(zip, sourceDir);

        // The runner streams the tar to a disk-backed file rather than buffering it in
        // memory; round-trip through a real file like copySourceToContainer does.
        Path tarFile = tempDir.resolve("source.tar");
        try (var out = new BufferedOutputStream(Files.newOutputStream(tarFile))) {
            runner.createTarFromDir(sourceDir, out);
        }

        Map<String, Integer> modes = new HashMap<>();
        try (var tar = new TarArchiveInputStream(new BufferedInputStream(Files.newInputStream(tarFile)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    modes.put(entry.getName(), entry.getMode() & 07777);
                }
            }
        }
        assertEquals(0755, (int) modes.get("lib/bash/bootstrap.sh"));
        assertEquals(0644, (int) modes.get("README.md"));
    }

    @Test
    void extractZipRecreatesSymlinkEntriesAsSymlinks() throws Exception {
        byte[] zip;
        try (var baos = new ByteArrayOutputStream()) {
            try (var zos = new ZipArchiveOutputStream(baos)) {
                ZipArchiveEntry realBin = new ZipArchiveEntry("node_modules/ts-node/dist/bin.js");
                realBin.setUnixMode(0755);
                zos.putArchiveEntry(realBin);
                zos.write("#!/usr/bin/env node\n".getBytes());
                zos.closeArchiveEntry();
                ZipArchiveEntry link = new ZipArchiveEntry("node_modules/.bin/ts-node");
                link.setUnixMode(0120777);
                zos.putArchiveEntry(link);
                zos.write("../ts-node/dist/bin.js".getBytes(StandardCharsets.UTF_8));
                zos.closeArchiveEntry();
                ZipArchiveEntry readme = new ZipArchiveEntry("README.md");
                readme.setUnixMode(0644);
                zos.putArchiveEntry(readme);
                zos.write("hello".getBytes());
                zos.closeArchiveEntry();
            }
            zip = baos.toByteArray();
        }
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null);
        runner.extractZip(zip, tempDir);

        Path link = tempDir.resolve("node_modules/.bin/ts-node");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(Path.of("../ts-node/dist/bin.js"), Files.readSymbolicLink(link));
        assertEquals("#!/usr/bin/env node\n",
                Files.readString(tempDir.resolve("node_modules/ts-node/dist/bin.js")));
        assertFalse(Files.isSymbolicLink(tempDir.resolve("README.md")));
    }

    @Test
    void extractZipSkipsSymlinksWhoseTargetEscapesTheRoot() throws Exception {
        byte[] zip;
        try (var baos = new ByteArrayOutputStream()) {
            try (var zos = new ZipArchiveOutputStream(baos)) {
                ZipArchiveEntry evil = new ZipArchiveEntry("evil-link");
                evil.setUnixMode(0120777);
                zos.putArchiveEntry(evil);
                zos.write("../../etc/x".getBytes(StandardCharsets.UTF_8));
                zos.closeArchiveEntry();
                ZipArchiveEntry ok = new ZipArchiveEntry("README.md");
                ok.setUnixMode(0644);
                zos.putArchiveEntry(ok);
                zos.write("hello".getBytes());
                zos.closeArchiveEntry();
            }
            zip = baos.toByteArray();
        }
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null);
        runner.extractZip(zip, tempDir);

        Path evil = tempDir.resolve("evil-link");
        assertFalse(Files.exists(evil, LinkOption.NOFOLLOW_LINKS), "Escaping symlink must not be created");
        assertEquals("hello", Files.readString(tempDir.resolve("README.md")));
    }

    @Test
    void createTarFromDirArchivesSymlinksAsSymlinks() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("source"));
        Files.createDirectories(sourceDir.resolve("node_modules/ts-node/dist"));
        Files.writeString(sourceDir.resolve("node_modules/ts-node/dist/bin.js"), "#!/usr/bin/env node\n");
        Path binDir = Files.createDirectories(sourceDir.resolve("node_modules/.bin"));
        Files.createSymbolicLink(binDir.resolve("ts-node"), Path.of("../ts-node/dist/bin.js"));

        Path tarFile = tempDir.resolve("source.tar");
        try (var out = new BufferedOutputStream(Files.newOutputStream(tarFile))) {
            new CodeBuildRunner(null, null, null, null, null, null, null, null, null, null, null)
                    .createTarFromDir(sourceDir, out);
        }

        String linkName = null;
        boolean sawRegularBin = false;
        try (var tar = new TarArchiveInputStream(new BufferedInputStream(Files.newInputStream(tarFile)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.getName().equals("node_modules/.bin/ts-node")) {
                    assertTrue(entry.isSymbolicLink(), "Link entry must be archived as a symlink");
                    linkName = entry.getLinkName();
                } else if (entry.getName().equals("node_modules/ts-node/dist/bin.js")) {
                    sawRegularBin = !entry.isSymbolicLink();
                }
            }
        }
        assertEquals("../ts-node/dist/bin.js", linkName);
        assertTrue(sawRegularBin, "The real target file must still be archived as a regular file");
    }
}
