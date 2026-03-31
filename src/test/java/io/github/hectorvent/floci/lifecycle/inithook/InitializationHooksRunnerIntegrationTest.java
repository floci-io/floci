package io.github.hectorvent.floci.lifecycle.inithook;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@QuarkusTest
class InitializationHooksRunnerIntegrationTest {

    @Inject
    private InitializationHooksRunner initializationHooksRunner;

    @TempDir
    private Path hookScriptsDirectory;

    @Test
    @DisplayName("Should execute startup hook scripts in lexicographical order")
    void shouldExecuteRealScriptsInLexicographicalOrder() throws IOException, InterruptedException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        File hookDirectory = hookScriptsDirectory.toFile();
        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Path absoluteOutputFile = outputFile.toAbsolutePath();
        String bootstrapScript = """
                #!/bin/bash
                echo bootstrap >> "%s"
                """.formatted(absoluteOutputFile);
        String seedResourcesScript = """
                #!/bin/bash
                echo seed-resources >> "%s"
                """.formatted(absoluteOutputFile);

        Files.writeString(hookScriptsDirectory.resolve("20-seed-resources.sh"), seedResourcesScript);
        Files.writeString(hookScriptsDirectory.resolve("10-bootstrap.sh"), bootstrapScript);

        initializationHooksRunner.run("startup", hookDirectory);

        List<String> expectedLines = List.of("bootstrap", "seed-resources");
        List<String> lines = Files.readAllLines(outputFile);
        Assertions.assertEquals(expectedLines, lines);
    }

    @Test
    @DisplayName("Should ignore non-shell files in the hook directory")
    void shouldIgnoreNonShellScriptFiles() throws IOException, InterruptedException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        File hookDirectory = hookScriptsDirectory.toFile();
        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Path absoluteOutputFile = outputFile.toAbsolutePath();
        String bootstrapScript = """
                #!/bin/bash
                echo bootstrap >> "%s"
                """.formatted(absoluteOutputFile);
        String seedResourcesScript = """
                #!/bin/bash
                echo seed-resources >> "%s"
                """.formatted(absoluteOutputFile);

        Files.writeString(hookScriptsDirectory.resolve("10-bootstrap.sh"), bootstrapScript);
        Files.writeString(hookScriptsDirectory.resolve("15-notes.txt"), "ignored");
        Files.writeString(hookScriptsDirectory.resolve("20-seed-resources.sh"), seedResourcesScript);

        initializationHooksRunner.run("startup", hookDirectory);

        List<String> expectedLines = List.of("bootstrap", "seed-resources");
        List<String> lines = Files.readAllLines(outputFile);
        Assertions.assertEquals(expectedLines, lines);
    }

    @Test
    @DisplayName("Should use lexicographical ordering for numbered hook file names")
    void shouldExecuteScriptsUsingLexicographicalFileNameOrder() throws IOException, InterruptedException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        File hookDirectory = hookScriptsDirectory.toFile();
        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Path absoluteOutputFile = outputFile.toAbsolutePath();
        String configureDomainScript = """
                #!/bin/bash
                echo configure-domain >> "%s"
                """.formatted(absoluteOutputFile);
        String bootstrapScript = """
                #!/bin/bash
                echo bootstrap >> "%s"
                """.formatted(absoluteOutputFile);
        String createBucketsScript = """
                #!/bin/bash
                echo create-buckets >> "%s"
                """.formatted(absoluteOutputFile);

        Files.writeString(hookScriptsDirectory.resolve("10-configure-domain.sh"), configureDomainScript);
        Files.writeString(hookScriptsDirectory.resolve("01-bootstrap.sh"), bootstrapScript);
        Files.writeString(hookScriptsDirectory.resolve("02-create-buckets.sh"), createBucketsScript);

        initializationHooksRunner.run("startup", hookDirectory);

        List<String> expectedLines = List.of("bootstrap", "create-buckets", "configure-domain");
        List<String> lines = Files.readAllLines(outputFile);
        Assertions.assertEquals(expectedLines, lines);
    }

    @Test
    @DisplayName("Should do nothing when the hook directory contains no shell scripts")
    void shouldDoNothingWhenDirectoryContainsNoShellScripts() throws IOException, InterruptedException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));
        File hookDirectory = hookScriptsDirectory.toFile();
        Files.writeString(hookScriptsDirectory.resolve("README.txt"), "hook documentation");

        initializationHooksRunner.run("startup", hookDirectory);

        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Assertions.assertFalse(Files.exists(outputFile));
    }

    @Test
    @DisplayName("Should do nothing when the hook directory does not exist")
    void shouldDoNothingWhenHookDirectoryDoesNotExist() throws IOException, InterruptedException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        Path missingHookDirectory = hookScriptsDirectory.resolve("missing");
        Path outputFile = hookScriptsDirectory.resolve("output.txt");

        initializationHooksRunner.run("startup", missingHookDirectory.toFile());

        Assertions.assertFalse(Files.exists(outputFile));
    }

    @Test
    @DisplayName("Should do nothing when the hook path is not a directory")
    void shouldDoNothingWhenHookPathIsNotADirectory() throws IOException, InterruptedException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        Path hookPathFile = hookScriptsDirectory.resolve("hook-file.txt");
        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Files.writeString(hookPathFile, "ignored");

        initializationHooksRunner.run("startup", hookPathFile.toFile());

        Assertions.assertFalse(Files.exists(outputFile));
    }

    @Test
    @DisplayName("Should stop executing startup hooks after the first failing script")
    void shouldStopAtFirstFailingScript() throws IOException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        File hookDirectory = hookScriptsDirectory.toFile();
        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Path absoluteOutputFile = outputFile.toAbsolutePath();
        String bootstrapScript = """
                #!/bin/bash
                echo bootstrap >> "%s"
                """.formatted(absoluteOutputFile);
        String failingScript = """
                #!/bin/bash
                exit 42
                """;
        String seedDataScript = """
                #!/bin/bash
                echo seed-data >> "%s"
                """.formatted(absoluteOutputFile);

        Files.writeString(hookScriptsDirectory.resolve("10-bootstrap.sh"), bootstrapScript);
        Files.writeString(hookScriptsDirectory.resolve("20-create-queue.sh"), failingScript);
        Files.writeString(hookScriptsDirectory.resolve("30-seed-data.sh"), seedDataScript);

        Assertions.assertThrows(IllegalStateException.class, () -> initializationHooksRunner.run("startup", hookDirectory));
        Assertions.assertEquals(List.of("bootstrap"), Files.readAllLines(outputFile));
    }

    @Test
    @DisplayName("Should stop executing startup hooks after the first timed out script")
    void shouldStopAtFirstTimedOutScript() throws IOException {
        Path bashExecutable = Path.of("/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bashExecutable));

        File hookDirectory = hookScriptsDirectory.toFile();
        Path outputFile = hookScriptsDirectory.resolve("output.txt");
        Path absoluteOutputFile = outputFile.toAbsolutePath();
        String bootstrapScript = """
                #!/bin/bash
                echo bootstrap >> "%s"
                """.formatted(absoluteOutputFile);
        String timeoutScript = """
                #!/bin/bash
                sleep 6
                """;
        String publishEventsScript = """
                #!/bin/bash
                echo publish-events >> "%s"
                """.formatted(absoluteOutputFile);

        Files.writeString(hookScriptsDirectory.resolve("10-bootstrap.sh"), bootstrapScript);
        Files.writeString(hookScriptsDirectory.resolve("20-seed-fixtures.sh"), timeoutScript);
        Files.writeString(hookScriptsDirectory.resolve("30-publish-events.sh"), publishEventsScript);

        Assertions.assertThrows(IllegalStateException.class, () -> initializationHooksRunner.run("startup", hookDirectory));

        List<String> expectedLines = List.of("bootstrap");
        List<String> lines = Files.readAllLines(outputFile);
        Assertions.assertEquals(expectedLines, lines);
    }
}
