package io.github.hectorvent.floci.services.lambda.launcher;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BootstrapProcess {

    private BootstrapProcess() {
    }

    public static Result run(ProcessBuilder builder) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "bootstrap process must terminate");
            return new Result(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "bootstrap process must be reaped");
            }
        }
    }

    public record Result(int exit, String output) {
    }
}
