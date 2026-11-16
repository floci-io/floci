package io.floci.aws.core.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a {@code multipart/form-data} body into its plain text fields and, at most, one file
 * part, without RESTEasy's {@code MultipartFormDataInput}: that reads reflectively in a way that
 * needs its own native-image registration per caller, where this is a handful of byte-array
 * scans. Pulled out of {@code S3Controller}'s presigned-POST handling once
 * {@code CodeArtifactPypiController} needed the identical parsing for a twine upload's own
 * multipart body.
 */
public final class MultipartFormParser {

    private MultipartFormParser() {
    }

    /** One named field's value, or the one file part carrying a {@code filename} parameter. */
    public record FilePart(String fieldName, String filename, String contentType, byte[] content) {}

    /**
     * {@code fields} excludes the file part. {@code fileOrNull} is the raw, possibly-absent
     * backing value; {@link #file()} is the public accessor and is never {@code null} itself.
     * The component can't be named {@code file} and still have an {@code Optional}-returning
     * accessor of that name: a record's generated accessor must return exactly the component's
     * declared type, so an {@code Optional<FilePart>} component would make {@code file()} itself
     * return {@code Optional<FilePart>} directly, which is the field AGENTS.md forbids.
     */
    public record ParsedForm(Map<String, String> fields, FilePart fileOrNull) {
        public Optional<FilePart> file() {
            return Optional.ofNullable(fileOrNull);
        }
    }

    /** The {@code boundary} parameter of a {@code multipart/form-data} Content-Type, if present. */
    public static Optional<String> extractBoundary(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return Optional.of(boundary);
            }
        }
        return Optional.empty();
    }

    public static ParsedForm parse(byte[] body, String boundary) {
        Map<String, String> fields = new LinkedHashMap<>();
        FilePart file = null;

        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        for (byte[] part : splitParts(body, boundaryBytes)) {
            int headerEnd = indexOfDoubleNewline(part);
            if (headerEnd < 0) {
                continue;
            }
            String headers = new String(part, 0, headerEnd, StandardCharsets.UTF_8);
            int bodyStart = headerEnd + 4; // skip \r\n\r\n
            byte[] partBody = Arrays.copyOfRange(part, bodyStart, part.length);

            // Trim the trailing CRLF the boundary split leaves on every part body.
            if (partBody.length >= 2
                    && partBody[partBody.length - 2] == '\r'
                    && partBody[partBody.length - 1] == '\n') {
                partBody = Arrays.copyOf(partBody, partBody.length - 2);
            }

            String disposition = extractHeaderValue(headers, "Content-Disposition");
            if (disposition == null) {
                continue;
            }
            String fieldName = extractDispositionParam(disposition, "name");
            if (fieldName == null) {
                continue;
            }

            String filename = extractDispositionParam(disposition, "filename");
            if (filename != null) {
                String partContentType = extractHeaderValue(headers, "Content-Type");
                file = new FilePart(fieldName, filename,
                        partContentType == null ? null : partContentType.trim(), partBody);
            } else {
                fields.put(fieldName, new String(partBody, StandardCharsets.UTF_8));
            }
        }
        return new ParsedForm(fields, file);
    }

    private static List<byte[]> splitParts(byte[] body, byte[] boundary) {
        List<byte[]> parts = new ArrayList<>();
        int pos = indexOf(body, boundary, 0);
        if (pos < 0) {
            return parts;
        }
        pos += boundary.length;
        if (pos < body.length - 1 && body[pos] == '-' && body[pos + 1] == '-') {
            return parts; // closing boundary immediately, no parts
        }
        if (pos < body.length - 1 && body[pos] == '\r' && body[pos + 1] == '\n') {
            pos += 2;
        }

        while (pos < body.length) {
            int nextBoundary = indexOf(body, boundary, pos);
            if (nextBoundary < 0) {
                break;
            }
            parts.add(Arrays.copyOfRange(body, pos, nextBoundary));
            pos = nextBoundary + boundary.length;
            if (pos < body.length - 1 && body[pos] == '-' && body[pos + 1] == '-') {
                break;
            }
            if (pos < body.length - 1 && body[pos] == '\r' && body[pos + 1] == '\n') {
                pos += 2;
            }
        }
        return parts;
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int indexOfDoubleNewline(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String extractHeaderValue(String headers, String headerName) {
        String lowerHeaders = headers.toLowerCase(Locale.ROOT);
        String lowerName = headerName.toLowerCase(Locale.ROOT) + ":";
        int idx = lowerHeaders.indexOf(lowerName);
        if (idx < 0) {
            return null;
        }
        int valueStart = idx + lowerName.length();
        int lineEnd = headers.indexOf('\r', valueStart);
        if (lineEnd < 0) {
            lineEnd = headers.indexOf('\n', valueStart);
        }
        if (lineEnd < 0) {
            lineEnd = headers.length();
        }
        return headers.substring(valueStart, lineEnd).trim();
    }

    /** Small text fields plus, when present, the one file part's name/filename/content-type. */
    public record ExtractedFile(String fieldName, String filename, String contentType) {}

    /** {@link ParsedForm}'s streamed sibling; see {@link #file()} for why this isn't a record component. */
    public record ExtractedForm(Map<String, String> fields, ExtractedFile fileOrNull) {
        public Optional<ExtractedFile> file() {
            return Optional.ofNullable(fileOrNull);
        }
    }

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    /**
     * Raised from an earlier 64 KiB (Copilot's review of this PR): a real {@code twine upload}
     * sends a project's long description/README as an ordinary text field, not the file part, and
     * a real README can comfortably exceed 64 KiB, so that cap rejected legitimate uploads this
     * parser's own predecessor ({@link #parse}) never capped at all.
     */
    private static final int MAX_FIELD_VALUE_BYTES = 8 * 1024 * 1024;
    /**
     * Bounds the combined size of every text field together, independent of {@link
     * #MAX_FIELD_VALUE_BYTES}'s own per-field cap (Copilot's review of this PR): without this, a
     * multipart body carrying many distinct, uniquely-named fields each just under the per-field
     * cap could still accumulate nearly the whole multi-GB envelope in {@code fields} on the heap,
     * defeating the reason this streamed sibling of {@link #parse} exists at all.
     */
    private static final int MAX_TOTAL_FIELD_BYTES = 16 * 1024 * 1024;

    /**
     * {@link #parse}'s streamed sibling, for a {@code multipart/form-data} body too large to hold
     * in one {@code byte[]}: {@code stagedWholeBody} is the whole raw request body already written
     * to disk by the caller, and only the one part whose {@code Content-Disposition} carries a
     * {@code filename} parameter is ever copied anywhere further, out of {@code stagedWholeBody}
     * and into {@code destinationFile}, so neither the whole multipart envelope nor that one
     * part's own content is ever held in a single JVM array, however large either is. Small text
     * fields (name/version and the like) are still buffered directly, since those are always a
     * handful of bytes in practice; a boundary marker is still found correctly even when it
     * straddles two separate chunk reads of the staged file, via an explicit carried-over overlap
     * window (see {@link #copyUntilDelimiter}). If more than one part carries a {@code filename}
     * parameter, the last one wins and is the one left in {@code destinationFile}, matching {@link
     * #parse}'s own last-file-wins behavior. Inherits {@link #parse}'s own boundary-matching
     * limitation as-is: a boundary string that happens to occur literally inside binary file
     * content is not escaped against, the same as today.
     */
    public static ExtractedForm extractFilePart(Path stagedWholeBody, String boundary, Path destinationFile)
            throws IOException {
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        ExtractedFile file = null;
        long totalFieldBytes = 0;
        if (destinationFile.getParent() != null) {
            Files.createDirectories(destinationFile.getParent());
        }
        try (InputStream raw = Files.newInputStream(stagedWholeBody)) {
            PushbackScanner scanner = new PushbackScanner(raw);
            // The body's own preamble (anything before the first boundary) is discarded, matching
            // parse's own indexOf-from-zero: a leading boundary need not be preceded by content.
            if (!skipToNextOccurrence(scanner, boundaryBytes)) {
                return new ExtractedForm(fields, null);
            }
            while (!isClosingBoundary(scanner)) {
                consumeOptionalCrlf(scanner);
                ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                copyUntilDelimiter(scanner, CRLFCRLF, headerBytes, MAX_HEADER_BYTES, false);
                String headers = headerBytes.toString(StandardCharsets.UTF_8);
                String disposition = extractHeaderValue(headers, "Content-Disposition");
                String fieldName = disposition == null ? null : extractDispositionParam(disposition, "name");
                String filename = disposition == null ? null : extractDispositionParam(disposition, "filename");
                if (fieldName != null && filename != null) {
                    String partContentType = extractHeaderValue(headers, "Content-Type");
                    try (OutputStream out = Files.newOutputStream(destinationFile, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        copyUntilDelimiter(scanner, boundaryBytes, out, Long.MAX_VALUE, true);
                    }
                    file = new ExtractedFile(fieldName, filename, partContentType == null ? null
                            : partContentType.trim());
                } else if (fieldName != null) {
                    ByteArrayOutputStream value = new ByteArrayOutputStream();
                    copyUntilDelimiter(scanner, boundaryBytes, value, MAX_FIELD_VALUE_BYTES, true);
                    totalFieldBytes += value.size();
                    if (totalFieldBytes > MAX_TOTAL_FIELD_BYTES) {
                        throw new IOException("Multipart body's text fields together exceeded the maximum "
                                + "allowed combined size.");
                    }
                    fields.put(fieldName, value.toString(StandardCharsets.UTF_8));
                } else {
                    // A part with no Content-Disposition name at all is skipped, matching parse's
                    // own "no fieldName -> continue" behavior.
                    copyUntilDelimiter(scanner, boundaryBytes, OutputStream.nullOutputStream(), Long.MAX_VALUE,
                            true);
                }
            }
        }
        return new ExtractedForm(fields, file);
    }

    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    /** True once a closing {@code --boundary--} is seen; otherwise consumes nothing further. */
    private static boolean isClosingBoundary(PushbackScanner scanner) throws IOException {
        byte[] two = scanner.readExactly(2);
        if (two != null && two[0] == '-' && two[1] == '-') {
            return true;
        }
        if (two != null) {
            scanner.unread(two, 0);
        }
        return false;
    }

    private static void consumeOptionalCrlf(PushbackScanner scanner) throws IOException {
        byte[] two = scanner.readExactly(2);
        if (two == null) {
            return;
        }
        if (two[0] != '\r' || two[1] != '\n') {
            scanner.unread(two, 0);
        }
    }

    /** Discards everything up to and including the first occurrence of {@code delimiter}. */
    private static boolean skipToNextOccurrence(PushbackScanner scanner, byte[] delimiter) throws IOException {
        try {
            copyUntilDelimiter(scanner, delimiter, OutputStream.nullOutputStream(), Long.MAX_VALUE, false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies everything read from {@code scanner} into {@code sink} up to (never including) the
     * next occurrence of {@code delimiter}, leaving the scanner positioned exactly after the
     * matched delimiter bytes. Never holds more than one chunk plus {@code delimiter.length - 1}
     * carried-over bytes in memory at once, however far apart two reads of the underlying stream
     * place a delimiter that straddles them. When {@code trimTrailingCrlf} is set (every real
     * field/file part's content, never a header block), a {@code \r\n} immediately before the
     * delimiter is dropped rather than written, matching the real content that preceded it: a
     * carried-over window is always at least {@code delimiter.length - 1} bytes, several times
     * larger than 2, so those two bytes are never written out before the delimiter they precede is
     * confirmed to be the real match.
     */
    private static void copyUntilDelimiter(PushbackScanner scanner, byte[] delimiter, OutputStream sink,
            long maxBytes, boolean trimTrailingCrlf) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        byte[] carry = new byte[0];
        long written = 0;
        while (true) {
            int read = scanner.read(chunk);
            if (read < 0) {
                throw new IOException("Multipart body ended before its own delimiter was found.");
            }
            byte[] combined = new byte[carry.length + read];
            System.arraycopy(carry, 0, combined, 0, carry.length);
            System.arraycopy(chunk, 0, combined, carry.length, read);
            int idx = indexOf(combined, delimiter, 0);
            if (idx >= 0) {
                int end = idx;
                if (trimTrailingCrlf && end >= 2 && combined[end - 2] == '\r' && combined[end - 1] == '\n') {
                    end -= 2;
                }
                written += end;
                if (written > maxBytes) {
                    throw new IOException("Multipart part exceeded the maximum allowed size before its own "
                            + "delimiter was found.");
                }
                sink.write(combined, 0, end);
                scanner.unread(combined, idx + delimiter.length);
                return;
            }
            // When trimTrailingCrlf is set, the carried-over window must hold 2 bytes more than the
            // minimum needed to find a split delimiter: a match can land as early as index 0 of the
            // NEXT combined buffer (when a chunk read happens to end exactly delimiter.length - 1
            // bytes before the delimiter's own start), and the trim check below only ever looks at
            // bytes still inside combined. Without this extra margin, the CRLF immediately before
            // such a match would already have been flushed to sink by a prior iteration as if it
            // were real content, before this method ever gets a chance to inspect, let alone trim,
            // it.
            int reserve = delimiter.length - 1 + (trimTrailingCrlf ? 2 : 0);
            int keep = Math.min(combined.length, reserve);
            int flush = combined.length - keep;
            written += flush;
            if (written > maxBytes) {
                throw new IOException("Multipart part exceeded the maximum allowed size before its own "
                        + "delimiter was found.");
            }
            sink.write(combined, 0, flush);
            carry = Arrays.copyOfRange(combined, flush, combined.length);
        }
    }

    /**
     * A thin pushback wrapper so {@link #copyUntilDelimiter} can hand back the bytes it read past
     * a delimiter match for the next caller to consume, without the two needing to share any other
     * state: {@link java.io.PushbackInputStream} only supports pushing back as many bytes as its
     * own fixed buffer size, declared up front, which does not fit this method's variable-length
     * unread.
     */
    private static final class PushbackScanner {
        private final InputStream in;
        private byte[] pending = new byte[0];
        private int pendingPos;

        PushbackScanner(InputStream in) {
            this.in = in;
        }

        int read(byte[] buffer) throws IOException {
            if (pendingPos < pending.length) {
                int n = Math.min(buffer.length, pending.length - pendingPos);
                System.arraycopy(pending, pendingPos, buffer, 0, n);
                pendingPos += n;
                return n;
            }
            return in.read(buffer);
        }

        /**
         * Puts {@code data[from:]} back so the next {@link #read} serves it first, ahead of
         * whatever this scanner was still holding unconsumed in {@code pending} at the time this
         * is called (a single caller-facing read, like {@link #readExactly}'s own 2-byte peeks,
         * only ever drains part of a larger {@code pending}, so the rest must be preserved, not
         * replaced).
         */
        void unread(byte[] data, int from) {
            byte[] unreadPart = Arrays.copyOfRange(data, from, data.length);
            byte[] remaining = Arrays.copyOfRange(pending, pendingPos, pending.length);
            byte[] combined = new byte[unreadPart.length + remaining.length];
            System.arraycopy(unreadPart, 0, combined, 0, unreadPart.length);
            System.arraycopy(remaining, 0, combined, unreadPart.length, remaining.length);
            pending = combined;
            pendingPos = 0;
        }

        /** Reads exactly {@code count} bytes, or returns {@code null} on reaching end of stream first. */
        byte[] readExactly(int count) throws IOException {
            byte[] result = new byte[count];
            int total = 0;
            while (total < count) {
                int read = read(result, total, count - total);
                if (read < 0) {
                    return null;
                }
                total += read;
            }
            return result;
        }

        private int read(byte[] buffer, int offset, int length) throws IOException {
            byte[] tmp = new byte[length];
            int n = read(tmp);
            if (n > 0) {
                System.arraycopy(tmp, 0, buffer, offset, n);
            }
            return n;
        }
    }

    private static String extractDispositionParam(String disposition, String paramName) {
        String search = paramName + "=";
        int idx = disposition.indexOf(search);
        if (idx < 0) {
            return null;
        }
        int valueStart = idx + search.length();
        if (valueStart >= disposition.length()) {
            return null;
        }
        if (disposition.charAt(valueStart) == '"') {
            valueStart++;
            int valueEnd = disposition.indexOf('"', valueStart);
            if (valueEnd < 0) {
                return disposition.substring(valueStart);
            }
            return disposition.substring(valueStart, valueEnd);
        } else {
            int valueEnd = disposition.indexOf(';', valueStart);
            if (valueEnd < 0) {
                valueEnd = disposition.length();
            }
            return disposition.substring(valueStart, valueEnd).trim();
        }
    }
}
