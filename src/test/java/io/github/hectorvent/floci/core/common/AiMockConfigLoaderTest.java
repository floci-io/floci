package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiMockConfigLoaderTest {

    private static final String CONFIG = """
            {
              "comprehend": {
                "I love this": { "DetectSentiment": { "Sentiment": "POSITIVE" } }
              }
            }
            """;

    @TempDir
    Path tempDir;

    private AiMockConfigLoader loader(String content) throws IOException {
        var file = tempDir.resolve("ai-mock-config.json");
        Files.writeString(file, content);
        return new AiMockConfigLoader(Optional.of(file.toString()), new ObjectMapper());
    }

    @Test
    void resolvesMatchingEntry() throws IOException {
        var loader = loader(CONFIG);
        var result = loader.lookup("comprehend", "I love this", "DetectSentiment");
        assertTrue(result.isPresent());
        assertEquals("POSITIVE", result.get().path("Sentiment").asText());
    }

    @Test
    void fallsBackWhenServiceKeyUnmatched() throws IOException {
        var loader = loader(CONFIG);
        assertTrue(loader.lookup("rekognition", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenLookupKeyUnmatched() throws IOException {
        var loader = loader(CONFIG);
        assertTrue(loader.lookup("comprehend", "I hate this", "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenActionUnmatched() throws IOException {
        var loader = loader(CONFIG);
        assertTrue(loader.lookup("comprehend", "I love this", "DetectKeyPhrases").isEmpty());
    }

    @Test
    void fallsBackWhenLookupKeyIsNull() throws IOException {
        // Bytes-backed images/documents have no natural lookup key.
        var loader = loader(CONFIG);
        assertTrue(loader.lookup("comprehend", null, "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenNoConfigConfigured() {
        var loader = new AiMockConfigLoader(Optional.empty(), new ObjectMapper());
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenFileMissing() {
        var loader = new AiMockConfigLoader(
                Optional.of(tempDir.resolve("absent.json").toString()), new ObjectMapper());
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenConfiguredPathIsUnparseable() {
        // An embedded NUL byte makes Path.of() throw InvalidPathException, an unchecked
        // exception that a plain "catch (IOException)" does not cover.
        var loader = new AiMockConfigLoader(Optional.of("bad\0path.json"), new ObjectMapper());
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenFileIsNotAJsonObject() throws IOException {
        var loader = loader("[1, 2, 3]");
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void fallsBackWhenFileIsMalformedJson() throws IOException {
        var loader = loader("{not valid json");
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void treatsBlankConfiguredPathAsUnconfigured() {
        var loader = new AiMockConfigLoader(Optional.of("  "), new ObjectMapper());
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isEmpty());
    }

    @Test
    void reloadsFileWhenModified() throws IOException, InterruptedException {
        var file = tempDir.resolve("ai-mock-config.json");
        Files.writeString(file, CONFIG);
        var loader = new AiMockConfigLoader(Optional.of(file.toString()), new ObjectMapper());
        assertTrue(loader.lookup("comprehend", "I love this", "DetectSentiment").isPresent());
        assertFalse(loader.lookup("comprehend", "I hate this", "DetectSentiment").isPresent());

        Thread.sleep(1100);
        Files.writeString(file, CONFIG.replace("I love this", "I hate this"));
        assertTrue(loader.lookup("comprehend", "I hate this", "DetectSentiment").isPresent());
        assertFalse(loader.lookup("comprehend", "I love this", "DetectSentiment").isPresent());
    }
}
