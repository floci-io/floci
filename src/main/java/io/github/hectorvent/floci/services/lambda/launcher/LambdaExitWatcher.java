package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.function.IntConsumer;

/**
 * Reports when a Lambda container's main process exits, through Docker's wait-for-exit stream.
 *
 * <p>The stream sends nothing until the container exits, so on a quiet container it ends with a read
 * timeout (the Docker client's response timeout) while the container still runs. A watch that simply
 * stopped there would miss every later crash, turning a {@code Runtime.ExitError} into a
 * {@code Function.TimedOut}. When the stream ends with an error, the container is inspected instead:
 * still running re-arms the watch, already exited reports the inspected exit code, and gone (removed
 * by teardown) reports nothing. Errors that keep arriving right after arming mean the daemon cannot
 * hold the stream at all, so the watch stops re-arming after a few of them rather than spinning.
 */
final class LambdaExitWatcher {

    private static final Logger LOG = Logger.getLogger(LambdaExitWatcher.class);

    /** A stream that fails sooner than this after it was armed counts as a quick failure. */
    static final Duration QUICK_FAILURE = Duration.ofSeconds(5);
    /** Consecutive quick failures tolerated before the watch gives up. */
    static final int MAX_QUICK_FAILURES = 3;

    private final DockerClient dockerClient;
    private final String containerId;
    private final IntConsumer onExit;
    private int quickFailures;

    private LambdaExitWatcher(DockerClient dockerClient, String containerId, IntConsumer onExit) {
        this.dockerClient = dockerClient;
        this.containerId = containerId;
        this.onExit = onExit;
    }

    /** Arms a watch that calls {@code onExit} with the exit status, at most once per exit. */
    static void watch(DockerClient dockerClient, String containerId, IntConsumer onExit) {
        new LambdaExitWatcher(dockerClient, containerId, onExit).arm();
    }

    private void arm() {
        long armedAt = System.nanoTime();
        try {
            dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback() {
                @Override
                public void onNext(WaitResponse response) {
                    super.onNext(response);
                    Integer statusCode = response.getStatusCode();
                    onExit.accept(statusCode != null ? statusCode : -1);
                }

                @Override
                public void onError(Throwable throwable) {
                    super.onError(throwable);
                    streamFailed(throwable, armedAt);
                }
            });
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not arm exit watcher for container {0}", containerId);
        }
    }

    private void streamFailed(Throwable cause, long armedAt) {
        InspectContainerResponse.ContainerState state;
        try {
            state = dockerClient.inspectContainerCmd(containerId).exec().getState();
        } catch (NotFoundException e) {
            LOG.debugv("Exit watcher for container {0} stopped: the container is gone", containerId);
            return;
        } catch (RuntimeException e) {
            LOG.warnv(e, "Exit watcher for container {0} lost its stream and could not inspect the container;"
                    + " a later exit will not be reported", containerId);
            return;
        }
        if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
            Long exitCode = state == null ? null : state.getExitCodeLong();
            onExit.accept(exitCode != null ? exitCode.intValue() : -1);
            return;
        }
        boolean quick = System.nanoTime() - armedAt < QUICK_FAILURE.toNanos();
        quickFailures = quick ? quickFailures + 1 : 0;
        if (quickFailures > MAX_QUICK_FAILURES) {
            LOG.warnv(cause, "Exit watcher for container {0} gave up after {1} immediate stream failures;"
                    + " a later exit will not be reported", containerId, quickFailures);
            return;
        }
        LOG.debugv("Exit watcher stream for running container {0} ended ({1}); re-arming",
                containerId, cause.toString());
        arm();
    }
}
