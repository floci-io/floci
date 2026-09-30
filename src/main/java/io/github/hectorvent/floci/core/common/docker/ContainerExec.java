package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command in a running container and waits for its exit code, shared by every container
 * manager that drives its container through {@code docker exec}: each of them had its own copy of
 * this before it moved here.
 *
 * <p>A command that outlives its timeout is left running in the container, with its attach
 * stream closed, and reported with {@link Result#timedOut()} set and an exit code of {@code -1}.
 * An error on the attach stream propagates as the {@link RuntimeException} docker-java raises for
 * it.
 */
public final class ContainerExec {

    private static final Logger LOG = Logger.getLogger(ContainerExec.class);

    private ContainerExec() {
    }

    /**
     * The outcome of one command. On a timeout, {@code stderr} ends with a note naming the timeout,
     * so a caller that logs the output says why the command stopped.
     */
    public record Result(long exitCode, String stdout, String stderr, boolean timedOut) {

        /** Both streams, trimmed, or {@code "(no output)"} when the command printed nothing. */
        public String summary() {
            String output = joinLines(stdout, stderr).trim();
            return output.isEmpty() ? "(no output)" : output;
        }

        /** This result, or a {@link RuntimeException} naming {@code containerId} when it timed out. */
        public Result throwIfTimedOut(String containerId) {
            if (timedOut) {
                throw new RuntimeException("exec timed out in container " + containerId);
            }
            return this;
        }
    }

    /** Runs {@code cmd}, keeping its stdout and stderr apart. */
    public static Result run(DockerClient dockerClient, String containerId, String[] cmd, int timeoutSeconds) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Result result = run(dockerClient, containerId, cmd, timeoutSeconds, stdout, stderr);
        return new Result(result.exitCode(), stdout.toString(StandardCharsets.UTF_8),
                joinLines(stderr.toString(StandardCharsets.UTF_8), result.stderr()), result.timedOut());
    }

    /** Runs {@code cmd}, interleaving stderr into stdout in the order the container wrote them. */
    public static Result runMerged(DockerClient dockerClient, String containerId, String[] cmd, int timeoutSeconds) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Result result = run(dockerClient, containerId, cmd, timeoutSeconds, output, output);
        return new Result(result.exitCode(), output.toString(StandardCharsets.UTF_8), result.stderr(),
                result.timedOut());
    }

    /**
     * Runs {@code cmd}, writing its stdout and stderr to the given streams, which may be the same
     * one. The returned {@code stdout} is empty and its {@code stderr} holds only the timeout note.
     */
    public static Result run(DockerClient dockerClient, String containerId, String[] cmd, int timeoutSeconds,
                             OutputStream stdout, OutputStream stderr) {
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();

        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                try {
                    if (frame.getStreamType() == StreamType.STDERR) {
                        stderr.write(payload);
                    } else {
                        stdout.write(payload);
                    }
                } catch (IOException e) {
                    LOG.warnv(e, "Failed to capture output of container exec {0}", execId);
                }
            }
        };
        dockerClient.execStartCmd(execId).exec(callback);

        try {
            // docker-java closes the attach stream on completion, timeout, or interruption.
            if (!callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS)) {
                return new Result(-1, "", "Timed out after " + timeoutSeconds + "s", true);
            }
            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            return new Result(exitCode != null ? exitCode : -1, "", "", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running a command in container " + containerId, e);
        }
    }

    private static String joinLines(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first + second;
        }
        return first.endsWith("\n") ? first + second : first + "\n" + second;
    }
}
