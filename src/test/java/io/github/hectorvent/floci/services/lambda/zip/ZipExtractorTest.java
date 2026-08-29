package io.github.hectorvent.floci.services.lambda.zip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipException;
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

    /**
     * One entry for {@link #streamedZip}: {@code stored} selects STORED over DEFLATED,
     * as a packager does for content that does not compress.
     */
    private record StreamedEntry(String name, String content, boolean stored) {}

    /**
     * Builds a ZIP the way a streaming packager does — the Serverless Framework's
     * archiver, for one. Such a writer cannot seek back to patch an entry's header, so it
     * sets the data-descriptor flag (general-purpose bit 3), zeroes the CRC and sizes in
     * the local header, and writes the real values after the data. The central directory
     * still carries the true values. {@link ZipOutputStream} cannot emit this shape, so
     * the bytes are assembled by hand.
     */
    private static byte[] streamedZip(StreamedEntry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<int[]> localOffsets = new ArrayList<>();
        List<byte[]> names = new ArrayList<>();
        List<long[]> meta = new ArrayList<>();

        for (StreamedEntry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            byte[] raw = entry.content().getBytes(StandardCharsets.UTF_8);
            byte[] payload = entry.stored() ? raw : deflate(raw);
            CRC32 crc = new CRC32();
            crc.update(raw);
            int method = entry.stored() ? 0 : 8;

            localOffsets.add(new int[]{out.size()});
            le32(out, 0x04034b50L);          // local file header signature
            le16(out, 20);                   // version needed
            le16(out, 0x0008);               // general-purpose bit 3: data descriptor follows
            le16(out, method);
            le16(out, 0);                    // mod time
            le16(out, 0);                    // mod date
            le32(out, 0);                    // crc-32       — deferred to the descriptor
            le32(out, 0);                    // compressed   — deferred to the descriptor
            le32(out, 0);                    // uncompressed — deferred to the descriptor
            le16(out, name.length);
            le16(out, 0);                    // extra length
            out.write(name);
            out.write(payload);
            le32(out, 0x08074b50L);          // data descriptor signature
            le32(out, crc.getValue());
            le32(out, payload.length);
            le32(out, raw.length);

            names.add(name);
            meta.add(new long[]{crc.getValue(), payload.length, raw.length, method});
        }

        int centralOffset = out.size();
        for (int i = 0; i < entries.length; i++) {
            byte[] name = names.get(i);
            long[] m = meta.get(i);
            le32(out, 0x02014b50L);          // central directory header signature
            le16(out, 20);                   // version made by
            le16(out, 20);                   // version needed
            le16(out, 0x0008);               // same descriptor flag as the local header
            le16(out, (int) m[3]);
            le16(out, 0);
            le16(out, 0);
            le32(out, m[0]);                 // crc-32       — the real value lives here
            le32(out, m[1]);                 // compressed   — the real value lives here
            le32(out, m[2]);                 // uncompressed — the real value lives here
            le16(out, name.length);
            le16(out, 0);                    // extra length
            le16(out, 0);                    // comment length
            le16(out, 0);                    // disk number start
            le16(out, 0);                    // internal attributes
            le32(out, 0);                    // external attributes
            le32(out, localOffsets.get(i)[0]);
            out.write(name);
        }
        int centralSize = out.size() - centralOffset;

        le32(out, 0x06054b50L);              // end of central directory signature
        le16(out, 0);                        // this disk
        le16(out, 0);                        // disk with central directory
        le16(out, entries.length);
        le16(out, entries.length);
        le32(out, centralSize);
        le32(out, centralOffset);
        le16(out, 0);                        // comment length
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void le32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xff));
        out.write((int) ((value >>> 8) & 0xff));
        out.write((int) ((value >>> 16) & 0xff));
        out.write((int) ((value >>> 24) & 0xff));
    }

    @Test
    void extractsStoredEntryCarryingADataDescriptor(@TempDir Path target) throws IOException {
        // Issue #2593: a Serverless Framework package bundling node_modules failed with
        // "only DEFLATED entries can have EXT descriptor". ZipInputStream reads local file
        // headers in sequence, and for a STORED entry whose sizes were deferred to a trailing
        // descriptor it cannot tell where the data ends, so it refuses the entry outright.
        // Real AWS Lambda accepts the archive. Reading the central directory does too.
        byte[] zip = streamedZip(
                new StreamedEntry("index.js", "exports.handler = async () => ({statusCode: 200});", false),
                new StreamedEntry("node_modules/sharp/sharp.node", "\0\0BINARY-INCOMPRESSIBLE", true));

        extractor.extractTo(zip, target);

        Path handler = target.resolve("index.js");
        assertTrue(Files.isRegularFile(handler), "handler must extract from a streamed package");
        assertEquals("exports.handler = async () => ({statusCode: 200});", Files.readString(handler));

        // The STORED entry is the one ZipInputStream rejected; it must land intact.
        Path stored = target.resolve("node_modules").resolve("sharp").resolve("sharp.node");
        assertTrue(Files.isRegularFile(stored), "STORED entry with a data descriptor must extract");
        assertEquals("\0\0BINARY-INCOMPRESSIBLE", Files.readString(stored));
    }

    @Test
    void extractsDeflatedEntryCarryingADataDescriptor(@TempDir Path target) throws IOException {
        // The descriptor flag alone was always tolerated for DEFLATED entries. Pin it, so the
        // move to the central directory is not silently narrowing what already worked.
        byte[] zip = streamedZip(new StreamedEntry("app/main.py", "def handler(e, c): return 1", false));

        extractor.extractTo(zip, target);

        Path nested = target.resolve("app").resolve("main.py");
        assertTrue(Files.isRegularFile(nested));
        assertEquals("def handler(e, c): return 1", Files.readString(nested));
    }

    @Test
    void extractsExplicitDirectoryEntries(@TempDir Path target) throws IOException {
        // Directory entries are flagged by a trailing slash in the central directory just as
        // they are in a local header; make sure they still create a directory, not a file.
        byte[] zip = streamedZip(
                new StreamedEntry("lib/", "", true),
                new StreamedEntry("lib/util.js", "module.exports = {};", false));

        extractor.extractTo(zip, target);

        assertTrue(Files.isDirectory(target.resolve("lib")));
        assertEquals("module.exports = {};", Files.readString(target.resolve("lib").resolve("util.js")));
    }

    /**
     * Builds a structurally valid ZIP whose entry data has been corrupted after the fact, so
     * every header (including the CRC recorded for the entry) is intact but the bytes no
     * longer match it — what a package truncated or mangled in transit looks like.
     */
    private static byte[] corruptedPayloadZip(String entryName, String content) throws IOException {
        byte[] zip = zipWith(entryName, content);
        int nameLength = (zip[26] & 0xff) | ((zip[27] & 0xff) << 8);
        int extraLength = (zip[28] & 0xff) | ((zip[29] & 0xff) << 8);
        int dataStart = 30 + nameLength + extraLength;
        for (int i = dataStart + 1; i < dataStart + 6 && i < zip.length; i++) {
            zip[i] ^= 0x7F;
        }
        return zip;
    }

    @Test
    void rejectsAnEntryThatFailsItsChecksum(@TempDir Path target) throws IOException {
        // ZipInputStream verified each entry's CRC on its own; ZipFile does not. Without an
        // explicit check, a deployment package corrupted in transit would extract silently and
        // the function would run garbage instead of failing loudly at deploy time.
        byte[] zip = corruptedPayloadZip("payload.js", "REAL-CODE-REAL-CODE-REAL-CODE");

        ZipException thrown = assertThrows(ZipException.class, () -> extractor.extractTo(zip, target));
        assertTrue(thrown.getMessage().contains("invalid entry CRC"),
                "corrupt entry must be reported as a checksum failure, was: " + thrown.getMessage());
    }

    @Test
    void replacesThePreviousExtractionInsteadOfOverlayingIt(@TempDir Path target) throws IOException {
        // Issue #2647: a deployment package is the complete contents of the function, so a file
        // present in one deploy and absent from the next has to disappear. Extracting in place
        // only ever adds and overwrites, so removed files survived and stayed loadable — a
        // handler pointing at a deleted module kept resolving and running stale code.
        byte[] v1 = zipWith("index.js", "v1");
        extractor.extractTo(v1, target);
        Files.writeString(target.resolve("removed.js"), "STALE");
        Files.createDirectories(target.resolve("legacy"));
        Files.writeString(target.resolve("legacy").resolve("old.js"), "STALE");

        byte[] v2 = zipWith("index.js", "v2");
        extractor.extractTo(v2, target);

        assertEquals("v2", Files.readString(target.resolve("index.js")), "new package must land");
        assertFalse(Files.exists(target.resolve("removed.js")),
                "a file absent from the new package must not survive the deploy");
        assertFalse(Files.exists(target.resolve("legacy")),
                "a directory absent from the new package must not survive the deploy");
    }

    @Test
    void leavesThePreviousExtractionIntactWhenExtractionFailsPartWay(@TempDir Path target) throws IOException {
        // Extraction is all-or-nothing: a failure must not leave the function holding a mixture
        // of the old package and part of the new one. The archive below opens cleanly and only
        // fails once its entry has been streamed out, which is exactly when writing directly
        // into the target would already have clobbered the previous deploy.
        extractor.extractTo(zipWith("index.js", "v1"), target);

        byte[] corrupt = corruptedPayloadZip("index.js", "v2-but-corrupted-in-transit");
        assertThrows(IOException.class, () -> extractor.extractTo(corrupt, target));

        assertEquals("v1", Files.readString(target.resolve("index.js")),
                "a failed deploy must leave the previous package in place");
    }

    @Test
    void leavesNoStagingArtefactsInTheCodeStore(@TempDir Path codeStore) throws IOException {
        // Extraction stages two things beside the target rather than in java.io.tmpdir: the
        // archive itself (so ZipFile has a seekable source) and the directory it unpacks into
        // before the swap. Both sit one level above the function's code directory, so pin that
        // neither survives — an orphan would accumulate one per deploy, and anything leaked
        // *inside* a function directory would be tar-copied into its container at launch.
        Path target = codeStore.resolve("fn");
        extractor.extractTo(zipWith("index.js", "v1"), target);
        assertNoStagingArtefacts(codeStore);

        byte[] corrupt = corruptedPayloadZip("index.js", "v2-but-corrupted-in-transit");
        assertThrows(IOException.class, () -> extractor.extractTo(corrupt, target));
        assertNoStagingArtefacts(codeStore);
    }

    private static void assertNoStagingArtefacts(Path codeStore) throws IOException {
        try (var entries = Files.list(codeStore)) {
            assertEquals(List.of("fn"), entries.map(p -> p.getFileName().toString()).sorted().toList(),
                    "no staged archive or staging directory may survive extraction, successful or failed");
        }
    }

    @Test
    void rejectsAnEntryWhoseCompressionMethodCannotBeRead(@TempDir Path target) throws IOException {
        // A package must never be half-deployed because an entry used a codec the reader does
        // not implement — writing it out empty would leave a function whose code silently
        // vanished. Method 99 is WinZip AES: structurally valid, not decodable here.
        byte[] zip = zipWithCompressionMethod(99, "secret.js", "exports.handler = () => 1;");

        assertThrows(ZipException.class, () -> extractor.extractTo(zip, target));
        assertFalse(Files.exists(target.resolve("secret.js")),
                "an entry that cannot be decoded must not be written out");
    }

    /** A structurally valid archive declaring an arbitrary compression method. */
    private static byte[] zipWithCompressionMethod(int method, String entryName, String content)
            throws IOException {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int localOffset = out.size();
        le32(out, 0x04034b50L);
        le16(out, 20);
        le16(out, 0);
        le16(out, method);
        le16(out, 0);
        le16(out, 0);
        le32(out, crc.getValue());
        le32(out, data.length);
        le32(out, data.length);
        le16(out, name.length);
        le16(out, 0);
        out.write(name);
        out.write(data);

        int centralOffset = out.size();
        le32(out, 0x02014b50L);
        le16(out, 20);
        le16(out, 20);
        le16(out, 0);
        le16(out, method);
        le16(out, 0);
        le16(out, 0);
        le32(out, crc.getValue());
        le32(out, data.length);
        le32(out, data.length);
        le16(out, name.length);
        le16(out, 0);
        le16(out, 0);
        le16(out, 0);
        le16(out, 0);
        le32(out, 0);
        le32(out, localOffset);
        out.write(name);
        int centralSize = out.size() - centralOffset;

        le32(out, 0x06054b50L);
        le16(out, 0);
        le16(out, 0);
        le16(out, 1);
        le16(out, 1);
        le32(out, centralSize);
        le32(out, centralOffset);
        le16(out, 0);
        return out.toByteArray();
    }

    @Test
    void aFailedInstallPutsThePreviousPackageBack(@TempDir Path codeStore) throws IOException {
        // The install used to delete the live tree and then move the new one in, so a failure in
        // the move left the function with no code and nothing to restore: a failed update
        // destroyed a working deployment. Driving install() directly is the only way to reach
        // that branch, since a rename between siblings does not fail under any condition a test
        // can arrange through extractTo.
        Path target = Files.createDirectories(codeStore.resolve("fn"));
        Files.writeString(target.resolve("index.js"), "v1");
        Path staging = codeStore.resolve("fn.floci-staging-never-created");

        assertThrows(IOException.class, () -> ZipExtractor.install(staging, target));

        assertTrue(Files.isRegularFile(target.resolve("index.js")),
                "a failed install must put the previous package back, not leave the function empty");
        assertEquals("v1", Files.readString(target.resolve("index.js")));
        try (var entries = Files.list(codeStore)) {
            assertEquals(List.of("fn"), entries.map(p -> p.getFileName().toString()).sorted().toList(),
                    "the restored package must not leave a renamed copy behind");
        }
    }

    @Test
    void aSuccessfulInstallReplacesAndLeavesNoCopyBehind(@TempDir Path codeStore) throws IOException {
        Path target = Files.createDirectories(codeStore.resolve("fn"));
        Files.writeString(target.resolve("index.js"), "v1");
        Files.writeString(target.resolve("removed.js"), "STALE");
        Path staging = Files.createDirectories(codeStore.resolve("fn.floci-staging-x"));
        Files.writeString(staging.resolve("index.js"), "v2");

        ZipExtractor.install(staging, target);

        assertEquals("v2", Files.readString(target.resolve("index.js")));
        assertFalse(Files.exists(target.resolve("removed.js")), "install must replace, not merge");
        try (var entries = Files.list(codeStore)) {
            assertEquals(List.of("fn"), entries.map(p -> p.getFileName().toString()).sorted().toList(),
                    "neither the staging tree nor the superseded package may survive");
        }
    }

    @Test
    void aFailureToDropTheSupersededPackageDoesNotFailTheDeployment(@TempDir Path codeStore)
            throws IOException {
        // Once the new tree is installed the deployment has happened. Reporting failure because
        // the superseded copy could not be deleted would make the caller skip its metadata and
        // warm pool updates while the new code is already serving, which is worse than leaving a
        // directory behind. Deletion is blocked by removing write permission on a subdirectory,
        // so its contents cannot be unlinked.
        Path target = Files.createDirectories(codeStore.resolve("fn"));
        Files.writeString(target.resolve("index.js"), "v1");
        Path locked = Files.createDirectories(target.resolve("locked"));
        Files.writeString(locked.resolve("held.txt"), "cannot be removed");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));

        Path staging = Files.createDirectories(codeStore.resolve("fn.floci-staging-x"));
        Files.writeString(staging.resolve("index.js"), "v2");

        try {
            ZipExtractor.install(staging, target);

            assertEquals("v2", Files.readString(target.resolve("index.js")),
                    "the new package must be installed even though the old one could not be removed");
        } finally {
            try (var stream = Files.list(codeStore)) {
                for (Path p : stream.toList()) {
                    Path stuck = p.resolve("locked");
                    if (Files.isDirectory(stuck)) {
                        Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("rwxr-xr-x"));
                    }
                }
            }
        }
    }
}
