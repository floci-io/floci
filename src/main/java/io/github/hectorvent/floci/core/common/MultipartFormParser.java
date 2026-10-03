package io.github.hectorvent.floci.core.common;

import java.nio.charset.StandardCharsets;
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
