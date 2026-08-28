package io.github.hectorvent.floci.services.lambda.zip;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

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

        // Read the archive through its central directory rather than sequentially over the
        // local file headers. A streaming packager cannot seek back to patch an entry's sizes,
        // so it sets the data-descriptor flag (general-purpose bit 3) and writes the real CRC
        // and sizes after the data instead; anything that does not compress is written STORED.
        // java.util.zip.ZipInputStream rejects that pairing outright with "only DEFLATED
        // entries can have EXT descriptor", because for a STORED entry it has no way to find
        // where the data ends. The central directory always carries the true method, CRC and
        // sizes regardless of the flag, so reading it accepts the same archives real AWS Lambda
        // does — e.g. a Serverless Framework package bundling node_modules (issue #2593).
        //
        // Commons Compress rather than java.util.zip.ZipFile: the JDK's central-directory
        // reader only accepts a File, which would mean writing the whole package to disk purely
        // to read it back. Commons Compress reads the same structure straight off the bytes we
        // already hold, so extraction adds no second copy of the archive anywhere — the disk
        // cost stays exactly what the extracted output needs, as it was before this change.
        try (ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zipBytes))
                .get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String entryName = entryName(entry);

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
                    // An entry using a compression method (or encryption) this build cannot
                    // decode would otherwise be written out as an empty file, silently
                    // deploying a function whose code is missing.
                    if (!zip.canReadEntryData(entry)) {
                        throw new ZipException("Unsupported compression or encryption for ZIP entry \""
                                + entryName + "\"");
                    }
                    Files.createDirectories(targetPath.getParent());
                    copyVerified(zip, entry, targetPath);
                }
            }
        }

        LOG.debugv("Extracted ZIP to: {0}", absTarget);
    }

    /**
     * The entry's name exactly as the archive stores it.
     *
     * <p>Commons Compress rewrites every {@code '\'} to {@code '/'} for a FAT-platform entry
     * whose name contains no {@code '/'} at all. That would turn a PowerShell-produced literal
     * filename into a nested path — precisely what #1215 established Floci must <em>not</em> do,
     * because real AWS Lambda extracts {@code "wwwroot\app.css"} as one flat file and silently
     * "fixing" it here would let a broken package pass locally and then fail on deploy. The raw
     * name field is the bytes as written, so when it carries a backslash and no forward slash the
     * rewrite is undone. Reversing it on the decoded name rather than decoding the raw bytes
     * keeps Commons Compress's own charset handling (UTF-8 flag vs. CP437) intact.
     */
    private static String entryName(ZipArchiveEntry entry) {
        byte[] raw = entry.getRawName();
        String name = entry.getName();
        if (raw == null) {
            return name;
        }
        boolean rawHasBackslash = false;
        boolean rawHasSlash = false;
        for (byte b : raw) {
            if (b == '\\') {
                rawHasBackslash = true;
            } else if (b == '/') {
                rawHasSlash = true;
            }
        }
        return rawHasBackslash && !rawHasSlash ? name.replace('/', BACKSLASH) : name;
    }

    /**
     * Copies one entry to disk, checking it against the CRC recorded for it in the archive.
     * ZipInputStream verified this on its own; ZipFile does not, so without an explicit check
     * a package corrupted in transit would be deployed silently as garbage code rather than
     * rejected. The message mirrors the JDK's own so existing reports stay recognisable.
     */
    private static void copyVerified(ZipFile zip, ZipArchiveEntry entry, Path targetPath) throws IOException {
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
