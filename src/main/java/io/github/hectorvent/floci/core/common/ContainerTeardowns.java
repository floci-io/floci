package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs every {@link ContainerTeardown} implementation, tolerating a failure in one so it
 * doesn't block the others or whatever cleanup the caller runs next. {@code EmulatorLifecycle}
 * (on {@code ShutdownEvent}) and {@code EmulatorInfoController} (on {@code /state/reset} and
 * {@code /state/nuke}) each independently ran this identical loop before this class existed.
 */
public final class ContainerTeardowns {

    private ContainerTeardowns() {
    }

    public static void stopAll(Instance<ContainerTeardown> teardowns, Logger log) {
        List<ContainerTeardown> ordered = new ArrayList<>();
        for (ContainerTeardown teardown : teardowns) {
            ordered.add(teardown);
        }
        ordered.sort(Comparator.comparingInt(ContainerTeardown::teardownOrder));
        for (ContainerTeardown teardown : ordered) {
            try {
                teardown.stopManagedContainers();
            } catch (Exception e) {
                log.warnv("Container teardown failed for {0}: {1}",
                        teardown.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public static <T> void stopInParallel(List<T> resources, Consumer<T> stop, Logger log) {
        stopInParallel(resources, stop, log, System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
    }

    public static <T> void stopInParallel(List<T> resources, Consumer<T> stop, Logger log,
                                         long deadlineNanos) {
        if (resources.isEmpty()) {
            return;
        }
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        if (remaining == 0) {
            log.warnv("Container drain deadline expired with {0} resource(s) pending", resources.size());
            return;
        }
        // Every owned container must receive its stop within the same deadline. A fixed worker
        // queue lets slow Docker calls consume that budget before later containers can even stop.
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("container-teardown-", 0).factory());
        try {
            List<Callable<Void>> tasks = new ArrayList<>(resources.size());
            for (T resource : resources) {
                tasks.add(() -> {
                    stop.accept(resource);
                    return null;
                });
            }
            for (Future<Void> task : executor.invokeAll(tasks,
                    Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                if (task.isCancelled()) {
                    log.warn("Container drain did not finish before the shared deadline");
                } else {
                    try {
                        task.get();
                    } catch (ExecutionException failure) {
                        log.warnv(failure.getCause(), "Container drain failed");
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Container drain interrupted");
        } finally {
            executor.shutdownNow();
        }
    }
}
