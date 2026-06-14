package io.github.hectorvent.floci.services.lambda.zip;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
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
                    zis.closeEntry();
                    continue;
                }

                Path targetPath = absTarget.resolve(entryName).normalize();
                if (!targetPath.startsWith(absTarget)) {
                    LOG.warnv("Skipping out-of-bounds ZIP entry: {0}", entryName);
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    try (OutputStream out = Files.newOutputStream(targetPath)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }

        LOG.debugv("Extracted ZIP to: {0}", absTarget);
    }
}
