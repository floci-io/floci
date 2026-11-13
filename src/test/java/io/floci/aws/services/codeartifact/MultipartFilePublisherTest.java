package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartFilePublisherTest {

    @Test
    void concatBuildsAPublisherAroundAnExistingFile() throws Exception {
        Path content = Files.createTempFile("multipart-file-publisher-test-", ".bin");
        try {
            Files.writeString(content, "the asset's real bytes");

            HttpRequest.BodyPublisher publisher = MultipartFilePublisher.concat(
                    "head\r\n".getBytes(StandardCharsets.UTF_8), content, "\r\ntail".getBytes(StandardCharsets.UTF_8));

            assertNotNull(publisher);
        } finally {
            Files.deleteIfExists(content);
        }
    }

    /**
     * {@code BodyPublishers.ofFile} throws a checked {@code FileNotFoundException} synchronously,
     * at construction time, for a {@code Path} that doesn't exist; {@link
     * MultipartFilePublisher#concat} must turn that into the same unchecked shape every other
     * I/O failure in this package already uses, not let a checked exception escape a caller that
     * never declared one.
     */
    @Test
    void concatWrapsAMissingFileIntoAnUncheckedIOException() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "multipart-file-publisher-test-missing.bin");

        assertThrows(UncheckedIOException.class, () -> MultipartFilePublisher.concat(
                "head".getBytes(StandardCharsets.UTF_8), missing, "tail".getBytes(StandardCharsets.UTF_8)));
    }
}
