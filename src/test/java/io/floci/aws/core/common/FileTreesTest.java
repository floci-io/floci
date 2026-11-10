package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTreesTest {

    @Test
    void deletesADirectoryAndEverythingUnderIt(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("root");
        Files.createDirectories(root.resolve("a").resolve("b"));
        Files.writeString(root.resolve("a").resolve("b").resolve("file.txt"), "x");
        Files.writeString(root.resolve("top.txt"), "y");
        Files.writeString(dir.resolve("sibling.txt"), "z");

        FileTrees.delete(root);

        assertFalse(Files.exists(root));
        assertTrue(Files.exists(dir.resolve("sibling.txt")));
    }

    @Test
    void deletesASingleFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        FileTrees.delete(file);

        assertFalse(Files.exists(file));
    }

    @Test
    void aMissingPathIsNotAnError(@TempDir Path dir) {
        assertDoesNotThrow(() -> FileTrees.delete(dir.resolve("missing")));
    }
}
