package io.github.hectorvent.floci.testing;

import com.github.dockerjava.api.DockerClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Probe that lets Docker-backed integration tests skip themselves when no daemon answers. */
public final class DockerAvailability {

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private DockerAvailability() {
    }

    /** True when {@code docker info} succeeds within the probe timeout. */
    public static boolean isAvailable() throws InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException expected) {
            // The Docker CLI is optional on development machines.
            return false;
        }
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    /** True when the daemon Floci itself talks to, through {@code dockerClient}, answers a ping. */
    public static boolean answers(DockerClient dockerClient) {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // An unreachable daemon only means the caller skips its test rather than failing it.
            return false;
        }
    }
}
