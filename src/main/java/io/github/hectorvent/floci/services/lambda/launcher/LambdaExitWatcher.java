package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Reports when a Lambda container's main process exits, through Docker's wait-for-exit stream.
 *
 * <p>The stream sends nothing until the container exits, so on a quiet container it ends with a read
 * timeout (the Docker client's response timeout) while the container still runs. A watch that simply
 * stopped there would miss every later crash, turning a {@code Runtime.ExitError} into a
 * {@code Function.TimedOut}. When the stream ends with an error, the container is inspected instead:
 * still running re-arms the watch, already exited reports the inspected exit code, and gone (removed
 * by teardown) reports nothing.
 *
 * <p>A stream that fails soon after it was armed, or an inspect that fails, usually means the daemon
 * itself is unreachable (a dropped connection, a restart). Those re-arm after a delay that doubles each
 * time, so the watch outlasts a short outage instead of spending its attempts within milliseconds; after
 * {@link #MAX_QUICK_FAILURES} in a row it stops. A stream that lived longer than {@link #QUICK_FAILURE}
 * resets the count and re-arms at once.
 */
final class LambdaExitWatcher {

    private static final Logger LOG = Logger.getLogger(LambdaExitWatcher.class);

    /** A stream that fails sooner than this after it was armed counts as a quick failure. */
    static final Duration QUICK_FAILURE = Duration.ofSeconds(5);
    /** Consecutive quick failures tolerated before the watch gives up. */
    static final int MAX_QUICK_FAILURES = 5;
    /** Delay before re-arming after the first quick failure; it doubles with each further one. */
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);

    private final DockerClient dockerClient;
    private final String containerId;
    private final IntConsumer onExit;
    private final BiConsumer<Duration, Runnable> scheduler;
    private final LongSupplier nanoClock;
    private int quickFailures;

    LambdaExitWatcher(DockerClient dockerClient, String containerId, IntConsumer onExit,
                      BiConsumer<Duration, Runnable> scheduler, LongSupplier nanoClock) {
        this.dockerClient = dockerClient;
        this.containerId = containerId;
        this.onExit = onExit;
        this.scheduler = scheduler;
        this.nanoClock = nanoClock;
    }

    /** Arms a watch that calls {@code onExit} with the exit status, at most once per exit. */
    static void watch(DockerClient dockerClient, String containerId, IntConsumer onExit) {
        new LambdaExitWatcher(dockerClient, containerId, onExit, LambdaExitWatcher::schedule, System::nanoTime)
                .arm();
    }

    private static void schedule(Duration delay, Runnable task) {
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(task);
    }

    void arm() {
        long armedAt = nanoClock.getAsLong();
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
            reArm(e, armedAt);
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
            LOG.debugv(e, "Exit watcher for container {0} could not inspect the container", containerId);
            reArm(e, armedAt);
            return;
        }
        if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
            Long exitCode = state == null ? null : state.getExitCodeLong();
            onExit.accept(exitCode != null ? exitCode.intValue() : -1);
            return;
        }
        reArm(cause, armedAt);
    }

    private void reArm(Throwable cause, long armedAt) {
        boolean quick = nanoClock.getAsLong() - armedAt < QUICK_FAILURE.toNanos();
        quickFailures = quick ? quickFailures + 1 : 0;
        if (quickFailures > MAX_QUICK_FAILURES) {
            LOG.warnv(cause, "Exit watcher for container {0} gave up after {1} quick failures; a later exit is unreported",
                    containerId, quickFailures);
            return;
        }
        if (quickFailures == 0) {
            LOG.debugv("Exit watcher stream for running container {0} ended ({1}); re-arming",
                    containerId, cause.toString());
            arm();
            return;
        }
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << (quickFailures - 1));
        LOG.debugv("Exit watcher for container {0} failed quickly ({1}); re-arming in {2} ms",
                containerId, cause.toString(), delay.toMillis());
        scheduler.accept(delay, this::arm);
    }
}
