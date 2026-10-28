package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvidedRuntimeBootstrapTest {

    @TempDir
    Path root;
    Path task;
    Path layer;
    Path entrypoint;

    @BeforeEach
    void setUp() throws Exception {
        task = Files.createDirectories(root.resolve("task"));
        layer = Files.createDirectories(root.resolve("layer"));
        entrypoint = root.resolve("runtime-bootstrap");
        // Rebase only the two container directories to execute the exact launcher
        // logic on the test filesystem without Docker, root, or a chroot.
        Files.writeString(entrypoint, ProvidedRuntimeBootstrap.SCRIPT
                .replace("/var/task/", task + "/").replace("/opt/", layer + "/"));
    }

    @Test
    void packageScriptKeepsItsCompanionPathAndTakesPrecedence() throws Exception {
        script(task, """
                #!/bin/sh
                exec "${0%/*}/.payload/worker" "$@"
                """);
        Path payload = Files.createDirectories(task.resolve(".payload"));
        executable(payload.resolve("worker"), "#!/bin/sh\nprintf 'package:%s' \"$1\"\n");
        script(layer, "#!/bin/sh\nexit 93\n");
        Result result = run("two words");
        assertEquals(0, result.exit());
        assertEquals("package:two words", result.output());
    }

    @Test
    void layerScriptKeepsItsCompanionPathWhenPackageHasNoBootstrap() throws Exception {
        script(layer, "#!/bin/sh\ncat \"${0%/*}/value\"\n");
        Files.writeString(layer.resolve("value"), "layer");
        Result result = run();
        assertEquals(0, result.exit());
        assertEquals("layer", result.output());
    }

    @Test
    void executableBinaryRetainsArguments() throws Exception {
        // Not /bin/echo: on Ubuntu 26.04 it is a multicall binary that refuses to run as "bootstrap".
        Files.createSymbolicLink(task.resolve("bootstrap"), Path.of("/bin/sh"));
        Result result = run("-c", "printf '%s|' \"$@\"", "sh", "binary", "two words");
        assertEquals(0, result.exit());
        assertEquals("binary|two words|", result.output());
    }

    @Test
    void missingBootstrapRefuses() throws Exception {
        Result result = run();
        assertEquals(127, result.exit());
        assertEquals("no bootstrap found at " + task + "/bootstrap or "
                + layer + "/bootstrap\n", result.output());
    }

    @Test
    void nonExecutablePackageDoesNotFallBackToLayer() throws Exception {
        Files.writeString(task.resolve("bootstrap"), "#!/bin/sh\nexit 0\n");
        script(layer, "#!/bin/sh\nprintf unexpected-fallback\n");
        Result result = run();
        assertTrue(result.exit() != 0);
        assertFalse(result.output().contains("unexpected-fallback"));
    }

    private void script(Path directory, String content) throws Exception {
        executable(directory.resolve("bootstrap"), content);
    }

    private void executable(Path path, String content) throws Exception {
        Files.writeString(path, content);
        assertTrue(path.toFile().setExecutable(true));
    }

    private Result run(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("/bin/sh", entrypoint.toString()));
        command.addAll(List.of(arguments));
        return BootstrapProcess.run(new ProcessBuilder(command));
    }
}
