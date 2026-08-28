package io.github.hectorvent.floci.services.lambda.zip;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Extracts ZIP bytes to a target directory.
 * Guards against path traversal attacks by validating entry names.
 */
@ApplicationScoped
public class ZipExtractor {

    private static final Logger LOG = Logger.getLogger(ZipExtractor.class);

    private static final char BACKSLASH = '\\';

    public void extractTo(byte[] zipBytes, Path targetDir) throws IOException {
        // Resolve to absolute path so that normalize() on entry paths stays comparable
        Path absTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(absTarget);

        // Read the archive through its central directory (ZipFile) rather than sequentially
        // over the local file headers (ZipInputStream). A streaming packager cannot seek back
        // to patch an entry's sizes, so it sets the data-descriptor flag (general-purpose bit
        // 3) and writes the real CRC and sizes after the data instead; anything that does not
        // compress is written STORED. ZipInputStream rejects that pairing outright with "only
        // DEFLATED entries can have EXT descriptor", because for a STORED entry it has no way
        // to find where the data ends. The central directory always carries the true method,
        // CRC and sizes regardless of the flag, so reading it accepts the same archives real
        // AWS Lambda does — e.g. a Serverless Framework package bundling node_modules
        // (issue #2593).
        //
        // ZipFile needs a seekable file, so the bytes are staged on disk. Stage inside the code
        // store rather than java.io.tmpdir: the shared temp filesystem must not become a new
        // unbounded consumer that concurrent deployments can exhaust and that unrelated
        // services would then fail on. The code store already has to hold this package's
        // *uncompressed* contents — always at least as large as the archive — so it is already
        // provisioned for the size, and the staged copy is released as soon as extraction ends.
        Path staged = Files.createTempFile(absTarget.getParent(), ".floci-staging-", ".zip");
        try {
            Files.write(staged, zipBytes);
            try (ZipFile zip = new ZipFile(staged.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    // PowerShell 5 Compress-Archive writes '\' as a literal filename byte on
                    // Linux. Real AWS Lambda does NOT normalize this (the archive extracts to
                    // a flat "wwwroot\app.css" file), so neither do we; masking it would let
                    // a broken package pass locally and then fail on deploy.
                    if (entryName.indexOf(BACKSLASH) >= 0) {
                        LOG.warnv("ZIP entry \"{0}\" uses backslash separators (PowerShell Compress-Archive). "
                                + "It extracts as a literal filename and will also fail on real AWS Lambda. "
                                + "Repackage with tar, PowerShell Core (pwsh), or the dotnet lambda CLI.", entryName);
                    }

                    // Security: prevent path traversal
                    if (entryName.contains("..") || entryName.startsWith("/")) {
                        LOG.warnv("Skipping suspicious ZIP entry: {0}", entryName);
                        continue;
                    }

                    Path targetPath = absTarget.resolve(entryName).normalize();
                    if (!targetPath.startsWith(absTarget)) {
                        LOG.warnv("Skipping out-of-bounds ZIP entry: {0}", entryName);
                        continue;
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        copyVerified(zip, entry, targetPath);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(staged);
        }

        LOG.debugv("Extracted ZIP to: {0}", absTarget);
    }

    /**
     * Copies one entry to disk, checking it against the CRC recorded for it in the archive.
     * ZipInputStream verified this on its own; ZipFile does not, so without an explicit check
     * a package corrupted in transit would be deployed silently as garbage code rather than
     * rejected. The message mirrors the JDK's own so existing reports stay recognisable.
     */
    private static void copyVerified(ZipFile zip, ZipEntry entry, Path targetPath) throws IOException {
        CRC32 checksum = new CRC32();
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                checksum.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        long expected = entry.getCrc();
        if (expected != -1L && checksum.getValue() != expected) {
            throw new ZipException(String.format(
                    "invalid entry CRC for \"%s\" (expected 0x%08x but got 0x%08x)",
                    entry.getName(), expected, checksum.getValue()));
        }
    }
}
