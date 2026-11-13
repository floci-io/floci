package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartFormParserTest {

    private static final String BOUNDARY = "boundary123";

    @TempDir
    Path tempDir;

    @Test
    void extractBoundaryReadsTheParameterRegardlessOfQuoting() {
        assertEquals(Optional.of("abc"), MultipartFormParser.extractBoundary("multipart/form-data; boundary=abc"));
        assertEquals(Optional.of("abc"),
                MultipartFormParser.extractBoundary("multipart/form-data; boundary=\"abc\""));
        assertEquals(Optional.empty(), MultipartFormParser.extractBoundary("application/json"));
        assertEquals(Optional.empty(), MultipartFormParser.extractBoundary(null));
    }

    @Test
    void parseSeparatesTextFieldsFromTheFilePart() {
        byte[] body = multipart(
                field("name", "requests"),
                field("version", "2.34.2"),
                file("content", "requests-2.34.2-py3-none-any.whl", "application/octet-stream",
                        "fake wheel bytes"));

        MultipartFormParser.ParsedForm form = MultipartFormParser.parse(body, BOUNDARY);

        assertEquals("requests", form.fields().get("name"));
        assertEquals("2.34.2", form.fields().get("version"));
        assertFalse(form.fields().containsKey("content"));
        assertTrue(form.file().isPresent());
        MultipartFormParser.FilePart file = form.file().get();
        assertEquals("content", file.fieldName());
        assertEquals("requests-2.34.2-py3-none-any.whl", file.filename());
        assertEquals("application/octet-stream", file.contentType());
        assertEquals("fake wheel bytes", new String(file.content(), StandardCharsets.UTF_8));
    }

    @Test
    void parseReturnsAnEmptyFileWhenNoPartCarriesAFilename() {
        byte[] body = multipart(field("key", "value"));

        MultipartFormParser.ParsedForm form = MultipartFormParser.parse(body, BOUNDARY);

        assertEquals("value", form.fields().get("key"));
        assertTrue(form.file().isEmpty());
    }

    @Test
    void parsePreservesBinaryContentThatContainsCrlfBytes() {
        byte[] binary = {0x50, 0x4B, 0x03, 0x04, '\r', '\n', 0x00, (byte) 0xFF};
        byte[] body = multipart(filePart("content", "binary.bin", "application/octet-stream", binary));

        MultipartFormParser.ParsedForm form = MultipartFormParser.parse(body, BOUNDARY);

        assertTrue(form.file().isPresent());
        assertArrayEqualsExactly(binary, form.file().get().content());
    }

    @Test
    void parseOnAnEmptyOrMalformedBodyReturnsNoFieldsAndNoFile() {
        MultipartFormParser.ParsedForm form = MultipartFormParser.parse(new byte[0], BOUNDARY);

        assertTrue(form.fields().isEmpty());
        assertTrue(form.file().isEmpty());
    }

    @Test
    void extractFilePartSeparatesTextFieldsFromTheFilePart() throws IOException {
        byte[] body = multipart(
                field("name", "requests"),
                field("version", "2.34.2"),
                file("content", "requests-2.34.2-py3-none-any.whl", "application/octet-stream",
                        "fake wheel bytes"));
        Path staged = stage(body);
        Path destination = tempDir.resolve("extracted");

        MultipartFormParser.ExtractedForm form = MultipartFormParser.extractFilePart(staged, BOUNDARY, destination);

        assertEquals("requests", form.fields().get("name"));
        assertEquals("2.34.2", form.fields().get("version"));
        assertFalse(form.fields().containsKey("content"));
        assertTrue(form.file().isPresent());
        MultipartFormParser.ExtractedFile file = form.file().get();
        assertEquals("content", file.fieldName());
        assertEquals("requests-2.34.2-py3-none-any.whl", file.filename());
        assertEquals("application/octet-stream", file.contentType());
        assertEquals("fake wheel bytes", Files.readString(destination, StandardCharsets.UTF_8));
    }

    @Test
    void extractFilePartReturnsAnEmptyFileWhenNoPartCarriesAFilename() throws IOException {
        byte[] body = multipart(field("key", "value"));
        Path staged = stage(body);
        Path destination = tempDir.resolve("extracted");

        MultipartFormParser.ExtractedForm form = MultipartFormParser.extractFilePart(staged, BOUNDARY, destination);

        assertEquals("value", form.fields().get("key"));
        assertTrue(form.file().isEmpty());
        assertFalse(Files.exists(destination));
    }

    @Test
    void extractFilePartPreservesBinaryContentThatContainsCrlfBytes() throws IOException {
        byte[] binary = {0x50, 0x4B, 0x03, 0x04, '\r', '\n', 0x00, (byte) 0xFF};
        byte[] body = multipart(filePart("content", "binary.bin", "application/octet-stream", binary));
        Path staged = stage(body);
        Path destination = tempDir.resolve("extracted");

        MultipartFormParser.ExtractedForm form = MultipartFormParser.extractFilePart(staged, BOUNDARY, destination);

        assertTrue(form.file().isPresent());
        assertArrayEquals(binary, Files.readAllBytes(destination));
    }

    @Test
    void extractFilePartOnAnEmptyOrMalformedBodyReturnsNoFieldsAndNoFile() throws IOException {
        Path staged = stage(new byte[0]);
        Path destination = tempDir.resolve("extracted");

        MultipartFormParser.ExtractedForm form = MultipartFormParser.extractFilePart(staged, BOUNDARY, destination);

        assertTrue(form.fields().isEmpty());
        assertTrue(form.file().isEmpty());
    }

    @Test
    void extractFilePartRejectsABodyThatNeverClosesAPart() throws IOException {
        byte[] malformed = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"x\"; filename=\"f\"\r\n\r\n"
                + "unterminated content, no closing boundary at all").getBytes(StandardCharsets.UTF_8);
        Path staged = stage(malformed);
        Path destination = tempDir.resolve("extracted");

        assertThrows(IOException.class, () -> MultipartFormParser.extractFilePart(staged, BOUNDARY, destination));
    }

    /**
     * {@code extractFilePart}'s own boundary search must still find a boundary marker correctly
     * even when it straddles two separate chunk reads of the staged file, not just when it lands
     * cleanly inside one. Rather than hand-computing the exact byte offset its internal chunk size
     * uses, this sweeps the file-part content length across a window around the one power-of-two
     * size (64 KiB) any reasonable streaming implementation would pick, so at least one case in
     * the sweep is guaranteed to straddle whatever chunk size the implementation actually uses,
     * and checks every case against {@code parse}'s own byte[]-based result as the oracle.
     */
    @Test
    void extractFilePartFindsABoundaryThatStraddlesAChunkReadBoundary() throws IOException {
        Random random = new Random(42);
        int chunkSize = 64 * 1024;
        for (int offset = -16; offset <= 16; offset++) {
            int contentLength = chunkSize + offset;
            byte[] content = new byte[contentLength];
            random.nextBytes(content);
            byte[] body = multipart(
                    field("name", "pkg"),
                    filePart("content", "asset.bin", "application/octet-stream", content));

            MultipartFormParser.ParsedForm oracle = MultipartFormParser.parse(body, BOUNDARY);
            Path staged = stage(body);
            Path destination = tempDir.resolve("extracted-" + offset);

            MultipartFormParser.ExtractedForm form = MultipartFormParser.extractFilePart(staged, BOUNDARY,
                    destination);

            assertEquals(oracle.fields().get("name"), form.fields().get("name"), "offset " + offset);
            assertArrayEquals(oracle.file().orElseThrow().content(), Files.readAllBytes(destination),
                    "offset " + offset);
        }
    }

    /**
     * The sweep above only proves the delimiter itself is still <em>found</em> correctly when it
     * straddles a chunk read; it uses random content, so the delimiter's own match position inside
     * the reconstructed buffer always lands comfortably past the first couple of bytes. This
     * covers a narrower, previously-missed case: a chunk read that happens to end with EXACTLY the
     * delimiter's own leading bytes already in hand, so the match completes at index 0 or 1 of the
     * very next read. The CRLF immediately before the delimiter must still be trimmed in that case
     * too, even though those two bytes were read in the PRIOR chunk, not the one the match itself
     * was found in — a real streaming implementation has no second chance to go back and un-write
     * bytes it already flushed to the sink.
     *
     * <p>The exact content length that lands the match at index 0/1 is not simply
     * {@code chunkSize - delimiter.length} from this test's own vantage point: the body's leading
     * {@code name} field is parsed first, and whatever few bytes {@code copyUntilDelimiter}'s own
     * pushback scanner is left holding once that field's own delimiter is found get served back out
     * on this part's very first read before the underlying file is touched again, shifting every
     * later chunk boundary by that same, otherwise-unpredictable amount. Rather than hand-deriving
     * that shift, this sweeps a window comfortably wider than one delimiter's own length around the
     * one power-of-two chunk size (64 KiB) any reasonable implementation would pick, so the sweep
     * is guaranteed to cross the real critical offset wherever the actual shift places it; confirmed
     * empirically to land at content lengths 65328/65329 bytes for this exact body shape, well
     * inside the swept window.
     */
    @Test
    void extractFilePartTrimsTheTrailingCrlfWhenTheDelimiterLandsAtTheVeryStartOfAChunkRead() throws IOException {
        int chunkSize = 64 * 1024;
        for (int offset = -300; offset <= 50; offset += 1) {
            int contentLength = chunkSize + offset;
            byte[] content = new byte[contentLength];
            Arrays.fill(content, (byte) 'F');
            byte[] body = multipart(
                    field("name", "pkg"),
                    filePart("content", "asset.bin", "application/octet-stream", content));
            Path staged = stage(body);
            Path destination = tempDir.resolve("extracted-offset-" + offset);

            MultipartFormParser.ExtractedForm form = MultipartFormParser.extractFilePart(staged, BOUNDARY,
                    destination);

            assertEquals("pkg", form.fields().get("name"), "offset " + offset);
            assertArrayEquals(content, Files.readAllBytes(destination), "offset " + offset);
        }
    }

    private Path stage(byte[] content) throws IOException {
        Path staged = Files.createTempFile(tempDir, "staged", ".bin");
        Files.write(staged, content);
        return staged;
    }

    private static void assertArrayEqualsExactly(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte at index " + i);
        }
    }

    private static byte[] multipart(byte[]... parts) {
        StringBuilder header = new StringBuilder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] part : parts) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(part);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] field(String name, String value) {
        String part = "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value;
        return part.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] file(String fieldName, String filename, String contentType, String content) {
        return filePart(fieldName, filename, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] filePart(String fieldName, String filename, String contentType, byte[] content) {
        String header = "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + content.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(content, 0, result, headerBytes.length, content.length);
        return result;
    }
}
