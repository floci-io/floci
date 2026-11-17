package io.floci.aws.testing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LambdaCodeFixturesTest {

    @Test
    void nodeJsZipPreservesUtf8SourceInOneEntry() throws IOException {
        String source = "exports.handler = async () => 'café';\n";
        assertHandlerSource(LambdaCodeFixtures.nodeJsZip(source), source);
    }

    @Test
    void nodeJsZipBase64ContainsTheHandlerArchive() throws IOException {
        String source = "exports.handler = async () => ({ statusCode: 200 });";
        byte[] archive = Base64.getDecoder().decode(LambdaCodeFixtures.nodeJsZipBase64(source));
        assertHandlerSource(archive, source);
    }

    @Test
    void nodeJsZipSupportsAnEmptyHandlerSource() throws IOException {
        assertHandlerSource(LambdaCodeFixtures.nodeJsZip(""), "");
    }

    private static void assertHandlerSource(byte[] archive, String source) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            assertEquals("index.js", zip.getNextEntry().getName());
            assertEquals(source, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            assertNull(zip.getNextEntry());
        }
    }
}
