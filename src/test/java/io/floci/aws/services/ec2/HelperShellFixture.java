package io.floci.aws.services.ec2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the helper shells Floci execs inside a guest (the IMDS proxy's, the sshd install probe) on
 * this host with {@code /bin/sh}, against fake system tools on PATH, with the environment an
 * application image hands a {@code docker exec}.
 */
final class HelperShellFixture {

    /** What an application image can carry into a {@code docker exec}, plus one setting the shells must keep. */
    static final Map<String, String> APPLICATION_ENVIRONMENT = Map.of(
            "LD_LIBRARY_PATH", "/fixture/application/lib",
            "PYTHONPATH", "/fixture/application/modules",
            "PYTHONHOME", "/fixture/application/python",
            "KEEP_SETTING", "fixture-value");

    private HelperShellFixture() {}

    /**
     * The start of a fake tool that fails unless the application's library and Python paths are
     * unset while its other settings survive, and then records the tool's name in TEST_RECORD.
     */
    static String checkedToolEnvironment() {
        return "[ -z \"${LD_LIBRARY_PATH+x}\" ] || exit 91\n"
                + "[ -z \"${PYTHONPATH+x}\" ] || exit 92\n"
                + "[ -z \"${PYTHONHOME+x}\" ] || exit 93\n"
                + "[ \"$KEEP_SETTING\" = fixture-value ] || exit 94\n"
                + "printf '%s\\n' \"${0##*/}\" >> \"$TEST_RECORD\"\n";
    }

    /** {@link #APPLICATION_ENVIRONMENT} plus the test's own settings. */
    static Map<String, String> applicationEnvironment(Map<String, String> settings) {
        Map<String, String> environment = new HashMap<>(APPLICATION_ENVIRONMENT);
        environment.putAll(settings);
        return environment;
    }

    static void writeTool(Path bin, String name, String script) throws IOException {
        Path tool = bin.resolve(name);
        Files.writeString(tool, "#!/bin/sh\nset -eu\n" + script);
        assertTrue(tool.toFile().setExecutable(true));
    }

    /** Runs a generated helper shell with {@code /bin/sh}, its output going to {@link #output}. */
    static int run(String[] command, Path directory, Map<String, String> environment) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.set(0, "/bin/sh");
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("shell-output").toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Generated helper shell exceeded its bound");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    static String output(Path directory) throws IOException {
        return Files.readString(directory.resolve("shell-output"));
    }
}
