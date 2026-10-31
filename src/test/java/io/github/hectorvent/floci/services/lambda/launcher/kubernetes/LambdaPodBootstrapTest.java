package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaPodBootstrapTest {

    @TempDir
    Path root;
    private Path task;
    private Path layer;
    private Path runtime;
    private Path bin;
    private final LambdaPodSpecFactory factory = new LambdaPodSpecFactory(null);

    @BeforeEach
    void setUp() throws Exception {
        task = Files.createDirectory(root.resolve("task"));
        layer = Files.createDirectory(root.resolve("opt"));
        runtime = Files.createDirectory(root.resolve("runtime"));
        bin = Files.createDirectory(root.resolve("bin"));
        Files.createDirectory(root.resolve("tmp"));
        // Replace only transport: generated download commands copy local zip fixtures.
        // Real unzip, chmod, shell redirection and the installed delegate execute below.
        Path wget = bin.resolve("wget");
        Files.writeString(wget, "#!/bin/sh\n[ \"$1\" = -q ] && [ \"$2\" = -O ] || exit 99\nexec cp \"$4\" \"$3\"\n");
        assertTrue(wget.toFile().setExecutable(true));
    }

    @Test
    void taskBootstrapKeepsCompanionPathArgumentsAndPrecedence() throws Exception {
        Path code = zip("code", Map.of("bootstrap", companionScript(), "value", "task"));
        Path overlay = zip("layer", Map.of("bootstrap", "#!/bin/sh\nexit 93\n"));
        assertEquals(0, init(code, List.of(overlay), true).exit());
        Result result = run(List.of("/bin/sh", runtime.resolve("bootstrap").toString(), "two words", "tail"));
        assertEquals(0, result.exit(), result.output());
        assertEquals("task:two words:tail", result.output());
        assertTrue(Files.isExecutable(task.resolve("bootstrap")));
        assertTrue(Files.isExecutable(layer.resolve("bootstrap")));
        assertTrue(Files.isExecutable(runtime.resolve("bootstrap")));
        assertEquals(companionScript(), Files.readString(task.resolve("bootstrap")));
    }

    @Test
    void layerOnlyBootstrapKeepsCompanionPathAndRestoresMode() throws Exception {
        Path code = zip("code", Map.of("handler", "unused"));
        Path overlay = zip("layer", Map.of("bootstrap", companionScript(), "value", "layer"));
        assertEquals(0, init(code, List.of(overlay), true).exit());
        Result result = run(List.of("/bin/sh", runtime.resolve("bootstrap").toString(), "two words", "tail"));
        assertEquals(0, result.exit(), result.output());
        assertEquals("layer:two words:tail", result.output());
        assertTrue(Files.isExecutable(layer.resolve("bootstrap")));
    }

    @Test
    void missingBootstrapUsesSharedErrorAndExitStatus() throws Exception {
        assertEquals(0, init(zip("code", Map.of("handler", "unused")), List.of(), true).exit());
        Result result = run(List.of(runtime.resolve("bootstrap").toString()));
        assertEquals(127, result.exit());
        assertEquals("no bootstrap found at " + task + "/bootstrap or " + layer + "/bootstrap\n", result.output());
    }

    @Test
    void failingTaskDoesNotFallBackToLayer() throws Exception {
        Path code = zip("code", Map.of("bootstrap", "#!/bin/sh\nprintf task-failed\nexit 42\n"));
        Path overlay = zip("layer", Map.of("bootstrap", "#!/bin/sh\nprintf unexpected-layer\n"));
        assertEquals(0, init(code, List.of(overlay), true).exit());
        Result result = run(List.of(runtime.resolve("bootstrap").toString()));
        assertEquals(42, result.exit());
        assertEquals("task-failed", result.output());
    }

    @Test
    void failedDownloadDoesNotInstallDelegate() throws Exception {
        assertTrue(init(root.resolve("absent.zip"), List.of(), true).exit() != 0);
        assertFalse(Files.exists(runtime.resolve("bootstrap")));
    }

    @Test
    void failedExtractionDoesNotInstallDelegate() throws Exception {
        Path invalid = Files.writeString(root.resolve("invalid.zip"), "not a zip");
        assertTrue(init(invalid, List.of(), true).exit() != 0);
        assertFalse(Files.exists(runtime.resolve("bootstrap")));
    }

    @Test
    void managedRuntimeDoesNotInstallDelegateOrChangeBootstrapMode() throws Exception {
        Path code = zip("code", Map.of("bootstrap", companionScript()));
        assertEquals(0, init(code, List.of(), false).exit());
        assertFalse(Files.exists(runtime.resolve("bootstrap")));
        assertFalse(Files.isExecutable(task.resolve("bootstrap")));
    }

    private String companionScript() {
        return "#!/bin/sh\ncat \"${0%/*}/value\"\nprintf ':%s:%s' \"$1\" \"$2\"\n";
    }

    private Path zip(String name, Map<String, String> files) throws Exception {
        Path archive = root.resolve(name + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private Result init(Path code, List<Path> layers, boolean provided) throws Exception {
        String script = factory.initScript(code.toString(), layers.stream().map(Path::toString).toList(), provided);
        // Rebase container directories without requiring root, a cluster or a chroot.
        script = script.replace("/var/task", task.toString()).replace("/var/runtime", runtime.toString())
                .replace("/opt", layer.toString())
                .replace("/tmp/code.zip", root.resolve("tmp/code.zip").toString())
                .replace("/tmp/layer", root.resolve("tmp/layer").toString());
        Path init = Files.writeString(root.resolve("init.sh"), script);
        return run(List.of("/bin/sh", init.toString()));
    }

    private Result run(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.environment().put("PATH", bin + ":" + builder.environment().get("PATH"));
        return BootstrapProcess.run(builder);
    }
}
