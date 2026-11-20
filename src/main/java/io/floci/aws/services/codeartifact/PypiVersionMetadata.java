package io.floci.aws.services.codeartifact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a pypi release's {@code Requires-Dist} metadata for {@code
 * ListPackageVersionDependencies}: pypiserver's own {@code /<project>/json} API carries only
 * filenames and URLs, never a distribution's embedded metadata, so this opens the actual asset
 * bytes a caller already fetched (a wheel's real {@code .dist-info/METADATA}, or an sdist's real
 * {@code PKG-INFO}) and parses the RFC822-style header text itself.
 */
final class PypiVersionMetadata {

    // A real METADATA/PKG-INFO file is at most a few KB; generous headroom, not a realistic size,
    // so a crafted entry cannot drive an unbounded allocation, the same reasoning NuspecXml's own
    // MAX_NUSPEC_BYTES bound uses.
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    // Bounds the TOTAL bytes this reader will ever decompress while scanning one archive,
    // including every entry it skips before (or without ever) finding the real metadata entry:
    // without this, a small, highly compressible archive with a large entry ahead of the real
    // one would make every dependency request for that version decompress an effectively
    // unbounded amount of data (a real decompression-bomb denial-of-service risk, not contrived).
    private static final long MAX_TOTAL_SCAN_BYTES = 64L * 1024 * 1024;
    private static final int TAR_BLOCK_SIZE = 512;
    private static final int TAR_NAME_OFFSET = 0;
    private static final int TAR_NAME_LENGTH = 100;
    private static final int TAR_SIZE_OFFSET = 124;
    private static final int TAR_SIZE_LENGTH = 12;

    private PypiVersionMetadata() {
    }

    /**
     * A wheel is a plain ZIP archive; this finds the one entry matching exactly
     * {@code <single-segment>.dist-info/METADATA} (one {@code /}, confirmed by requiring the
     * first and last slash indices to coincide). Requiring exactly one path segment before the
     * filename is deliberate: a wheel that vendors another package's own dist-info alongside its
     * own (a real, documented setuptools pattern, e.g. {@code setuptools/_vendor/packaging-*
     * .dist-info/METADATA}) would otherwise match that nested entry instead of the real top-level
     * one, reporting the vendored package's dependencies rather than the wheel's own. Empty when
     * no such entry exists.
     */
    static Optional<byte[]> extractWheelMetadata(byte[] wheelBytes) {
        return extractFromZip(wheelBytes, name -> isTopLevelEntry(name, ".dist-info/METADATA"),
                "wheel", "METADATA");
    }

    /**
     * A {@code .zip}-packaged sdist (an older, still AWS/pypiserver-recognized source-distribution
     * format alongside the far more common {@code .tar.gz}), scanned the same way as a wheel but
     * matching exactly {@code <single-segment>/PKG-INFO}.
     */
    static Optional<byte[]> extractZipSdistMetadata(byte[] zipBytes) {
        return extractFromZip(zipBytes, name -> isTopLevelEntry(name, "/PKG-INFO"), "sdist", "PKG-INFO");
    }

    private static Optional<byte[]> extractFromZip(byte[] archiveBytes, Predicate<String> matches,
            String archiveKind, String entryKind) {
        long[] remaining = {MAX_TOTAL_SCAN_BYTES};
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory() && matches.test(entry.getName())) {
                    return Optional.of(readBounded(zip, remaining, entryKind));
                }
                discardBounded(zip, remaining);
                entry = zip.getNextEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the package version's " + archiveKind
                    + " archive.", e);
        }
        return Optional.empty();
    }

    /** True when {@code name} has exactly one {@code /} and ends with {@code suffix}. */
    private static boolean isTopLevelEntry(String name, String suffix) {
        int firstSlash = name.indexOf('/');
        return firstSlash >= 0 && firstSlash == name.lastIndexOf('/') && name.endsWith(suffix);
    }

    /**
     * A {@code .tar.gz}/{@code .tgz} sdist (gzip-compressed TAR, the modern default pip/setuptools
     * produce); this decompresses it and walks a minimal subset of the TAR format (classic
     * 512-byte header blocks, the null-terminated name field and octal size field every real
     * pip/setuptools-produced archive has) to find the one entry matching exactly
     * {@code <single-segment>/PKG-INFO} (one {@code /}: a nested {@code PKG-INFO} belonging to a
     * vendored or bundled package must not be mistaken for the sdist's own top-level one).
     * Deliberately does not support symlinks, GNU long-name ({@code @LongLink}), or PAX extended
     * headers: a documented, bounded limitation, not a general-purpose TAR reader. {@code
     * .tar.bz2}/{@code .tar.xz} sdists are real, pypiserver-recognized formats this does not
     * parse at all (bzip2/xz decompression would need a new dependency this codebase deliberately
     * avoids), so callers must not route those bytes through this method; see {@link
     * PypiserverSidecarClient#listPackageVersionDependencies} for how a release available only in
     * one of those formats is handled. Empty when no matching entry is found or the archive ends
     * before one is.
     */
    static Optional<byte[]> extractSdistMetadata(byte[] sdistBytes) {
        long[] remaining = {MAX_TOTAL_SCAN_BYTES};
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(sdistBytes))) {
            byte[] header = new byte[TAR_BLOCK_SIZE];
            while (readFully(gzip, header, remaining) == TAR_BLOCK_SIZE && !isZeroBlock(header)) {
                String name = tarEntryName(header);
                long size = tarEntrySize(header);
                if (size < 0) {
                    break;
                }
                long paddedSize = ((size + TAR_BLOCK_SIZE - 1) / TAR_BLOCK_SIZE) * TAR_BLOCK_SIZE;
                if (isTopLevelEntry(name, "/PKG-INFO")) {
                    if (size > MAX_METADATA_BYTES) {
                        throw new IllegalStateException("The package version's PKG-INFO exceeds "
                                + MAX_METADATA_BYTES + " bytes.");
                    }
                    byte[] content = new byte[(int) size];
                    readFully(gzip, content, remaining);
                    skipFully(gzip, paddedSize - size, remaining);
                    return Optional.of(content);
                }
                skipFully(gzip, paddedSize, remaining);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the package version's sdist archive.", e);
        }
        return Optional.empty();
    }

    /**
     * Every {@code Requires-Dist} header in {@code metadata}'s RFC822-style text, folding a
     * continuation line (one starting with whitespace) into the header it continues first.
     * Scanning stops at the first blank line, the real RFC822 boundary between the header block
     * and the message body (a package's own long description): without that boundary, a
     * description that happens to contain a line looking like {@code Requires-Dist: ...} (a real
     * risk, since that is literally itself a documented example string in PyPA's own packaging
     * guide) would be wrongly reported as a real dependency. The bare package name is the leading
     * run of name characters; everything after it, including any {@code ; extra == "..."}
     * environment marker, is returned verbatim as {@code versionRequirement} rather than
     * stripped: {@code PackageDependency} has no marker field, and nothing in AWS's own
     * documentation of this action indicates CodeArtifact itself strips one.
     */
    static List<CodeArtifactService.PackageDependency> parseRequiresDist(byte[] metadata) {
        List<CodeArtifactService.PackageDependency> dependencies = new ArrayList<>();
        for (String line : foldHeaderLines(new String(metadata, StandardCharsets.UTF_8))) {
            int colon = line.indexOf(':');
            if (colon < 0 || !"Requires-Dist".equalsIgnoreCase(line.substring(0, colon).trim())) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            int nameEnd = 0;
            while (nameEnd < value.length() && isPackageNameChar(value.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == 0) {
                continue;
            }
            String versionRequirement = value.substring(nameEnd).trim();
            dependencies.add(new CodeArtifactService.PackageDependency(null, value.substring(0, nameEnd),
                    "Requires-Dist", versionRequirement.isEmpty() ? null : versionRequirement));
        }
        return dependencies;
    }

    private static boolean isPackageNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_';
    }

    private static List<String> foldHeaderLines(String text) {
        List<String> logicalLines = new ArrayList<>();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                break;
            }
            if (!logicalLines.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                int last = logicalLines.size() - 1;
                logicalLines.set(last, logicalLines.get(last) + " " + line.strip());
            } else {
                logicalLines.add(line);
            }
        }
        return logicalLines;
    }

    private static String tarEntryName(byte[] header) {
        int end = TAR_NAME_OFFSET;
        while (end < TAR_NAME_OFFSET + TAR_NAME_LENGTH && header[end] != 0) {
            end++;
        }
        return new String(header, TAR_NAME_OFFSET, end - TAR_NAME_OFFSET, StandardCharsets.UTF_8);
    }

    /** {@code -1} on a header this reader cannot make sense of, so the caller stops scanning. */
    private static long tarEntrySize(byte[] header) {
        int start = TAR_SIZE_OFFSET;
        int end = TAR_SIZE_OFFSET + TAR_SIZE_LENGTH;
        while (start < end && header[start] == ' ') {
            start++;
        }
        while (end > start && (header[end - 1] == 0 || header[end - 1] == ' ')) {
            end--;
        }
        if (end <= start) {
            return 0;
        }
        try {
            return Long.parseLong(new String(header, start, end - start, StandardCharsets.US_ASCII), 8);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void chargeBudget(long[] remaining, int bytes) {
        remaining[0] -= bytes;
        if (remaining[0] < 0) {
            throw new IllegalStateException("The package version's archive exceeds this reader's "
                    + MAX_TOTAL_SCAN_BYTES + "-byte total decompression budget.");
        }
    }

    private static int readFully(InputStream in, byte[] buffer, long[] remaining) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            chargeBudget(remaining, read);
            total += read;
        }
        return total;
    }

    private static void skipFully(InputStream in, long bytes, long[] remaining) throws IOException {
        long toSkip = bytes;
        byte[] buffer = new byte[8192];
        while (toSkip > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, toSkip));
            if (read < 0) {
                return;
            }
            chargeBudget(remaining, read);
            toSkip -= read;
        }
    }

    private static void discardBounded(InputStream in, long[] remaining) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            chargeBudget(remaining, read);
        }
    }

    private static byte[] readBounded(InputStream in, long[] remaining, String entryKind) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            chargeBudget(remaining, read);
            if (buffer.size() + read > MAX_METADATA_BYTES) {
                throw new IllegalStateException("The package version's " + entryKind + " exceeds "
                        + MAX_METADATA_BYTES + " bytes decompressed.");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
