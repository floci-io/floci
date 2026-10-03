package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartFormParserTest {

    private static final String BOUNDARY = "boundary123";

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
