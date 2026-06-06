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

    // The ZIP spec (APPNOTE.TXT 4.4.17) mandates '/' as the entry path separator.
    // PowerShell's Compress-Archive instead writes '\', which on Linux is a literal
    // filename character — so "wwwroot\app.css" would become a single flat file
    // instead of a nested path. Normalize '\' -> '/' so such archives extract
    // correctly (matching how real AWS Lambda unpacks them).
    private static final char WINDOWS_SEPARATOR = '\\';
    private static final char UNIX_SEPARATOR = '/';

    public void extractTo(byte[] zipBytes, Path targetDir) throws IOException {
        // Resolve to absolute path so that normalize() on entry paths stays comparable
        Path absTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(absTarget);

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Normalize Windows-style separators before any path handling so the
                // traversal guard and resolution below operate on canonical names.
                String entryName = entry.getName().replace(WINDOWS_SEPARATOR, UNIX_SEPARATOR);

                // Security: prevent path traversal
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    LOG.warnv("Skipping suspicious ZIP entry: {0}", entryName);
                    zis.closeEntry();
                    continue;
                }

                // A trailing separator marks a directory entry; Compress-Archive's
                // backslash form would otherwise slip past ZipEntry.isDirectory().
                boolean isDirectory = entry.isDirectory() || entryName.endsWith(String.valueOf(UNIX_SEPARATOR));

                Path targetPath = absTarget.resolve(entryName).normalize();
                if (!targetPath.startsWith(absTarget)) {
                    LOG.warnv("Skipping out-of-bounds ZIP entry: {0}", entryName);
                    zis.closeEntry();
                    continue;
                }

                if (isDirectory) {
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
