package io.github.hectorvent.floci.services.lambda.zip;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Extracts ZIP bytes to a target directory, replacing whatever was there before.
 * Guards against path traversal attacks by validating entry names.
 */
@ApplicationScoped
public class ZipExtractor {

    private static final Logger LOG = Logger.getLogger(ZipExtractor.class);

    private static final char BACKSLASH = '\\';

    public void extractTo(byte[] zipBytes, Path targetDir) throws IOException {
        // Resolve to absolute path so that normalize() on entry paths stays comparable
        Path absTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(absTarget.getParent());

        // Extract into a staging directory and swap it in, rather than writing over the target
        // in place. A deployment package is the complete contents of the function, so a file
        // dropped between two deploys has to disappear — writing in place only ever adds and
        // overwrites, leaving removed files behind to stay loadable (issue #2647). Staging also
        // makes extraction all-or-nothing: a failure part-way through leaves the previous
        // deployment intact instead of a half-replaced mixture of the two.
        Path staging = absTarget.resolveSibling(
                absTarget.getFileName() + ".floci-staging-" + UUID.randomUUID());
        Files.createDirectories(staging);

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

                    Path targetPath = staging.resolve(entryName).normalize();
                    if (!targetPath.startsWith(staging)) {
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
            // Extraction succeeded in full: install the new tree.
            install(staging, absTarget);
        } finally {
            Files.deleteIfExists(staged);
            // A no-op once install() moved it; on failure this discards the partial staging tree
            // and leaves the previous extraction untouched.
            deleteRecursively(staging);
        }

        LOG.debugv("Extracted ZIP to: {0}", absTarget);
    }

    /**
     * Installs the freshly extracted tree at {@code absTarget}, keeping the package already there
     * until the new one is in place.
     *
     * <p>The old tree is renamed aside and deleted only after the new one lands, rather than
     * deleted up front. Deleting first means a failure in the move leaves the function with no
     * code at all and nothing to restore, turning a failed update into a destroyed deployment.
     * Every failure path here ends with one complete package on disk.
     *
     * <p>Both steps are renames within one directory, so the interval in which the target is
     * absent is two metadata operations rather than a recursive delete followed by a move. It is
     * not zero: POSIX cannot atomically exchange two directories, so a launch landing exactly
     * between the renames can still observe a missing code path. Closing that window entirely
     * needs a lock shared with the launcher, which is wider than this change.
     *
     * <p>Package-private so the failure path can be exercised directly; it is not reachable
     * otherwise, because a move between siblings only fails under conditions a test cannot
     * arrange through {@link #extractTo}.
     */
    static void install(Path staging, Path absTarget) throws IOException {
        Path previous = null;
        if (Files.exists(absTarget)) {
            previous = absTarget.resolveSibling(
                    absTarget.getFileName() + ".floci-previous-" + UUID.randomUUID());
            moveIntoPlace(absTarget, previous);
        }
        try {
            moveIntoPlace(staging, absTarget);
        } catch (IOException e) {
            if (previous != null) {
                try {
                    // Put the working package back before giving up.
                    moveIntoPlace(previous, absTarget);
                } catch (IOException restoreFailed) {
                    // Both renames failed, so the function has no code at the canonical path and
                    // the working package is sitting under a generated name nobody will look for.
                    // Keep the original failure as the one thrown, attach this one, and say
                    // exactly where the package went so it can be put back by hand.
                    e.addSuppressed(restoreFailed);
                    LOG.errorv("Could not restore the previous deployment at {0}. The working "
                            + "package is stranded at {1}; move it back to recover.",
                            absTarget, previous);
                }
            }
            throw e;
        }
        if (previous != null) {
            // Best effort from here. The new package is already live, so failing to drop the
            // superseded tree must not be reported as a failed deployment: the caller would skip
            // its metadata and warm pool updates while the new code is already serving. Leaving
            // the tree behind costs disk and is recoverable; lying about the outcome is not.
            try {
                deleteRecursively(previous);
            } catch (IOException e) {
                LOG.warnv("Installed {0} but could not remove the superseded package at {1}: {2}",
                        absTarget, previous, e.getMessage());
            }
        }
    }

    private static void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems (and bind mounts inside containers) cannot rename atomically.
            Files.move(source, destination);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
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
