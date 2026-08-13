package io.github.hectorvent.floci.services.stepfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Runs Map iterations with a bounded in-flight window while preserving input order. */
final class MapIterationScheduler {

    private MapIterationScheduler() {
    }

    static <T> List<T> execute(int itemCount, int maxConcurrency,
                               IntFunction<Callable<T>> taskFactory) throws Exception {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must not be negative");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        if (itemCount == 0) {
            return List.of();
        }
        if (itemCount == 1 || maxConcurrency == 1) {
            List<T> results = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                results.add(taskFactory.apply(i).call());
            }
            return results;
        }

        int inFlightLimit = Math.min(itemCount, maxConcurrency);
        List<T> results = new ArrayList<>(Collections.nCopies(itemCount, null));
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("sfn-map-", 0).factory());
        CompletionService<IndexedResult<T>> completions = new ExecutorCompletionService<>(executor);
        Set<Future<IndexedResult<T>>> inFlight = new HashSet<>();
        int nextIndex = 0;
        int completed = 0;

        try {
            while (nextIndex < inFlightLimit) {
                inFlight.add(submit(completions, taskFactory, nextIndex++));
            }
            while (completed < itemCount) {
                Future<IndexedResult<T>> future = completions.take();
                inFlight.remove(future);
                IndexedResult<T> result = future.get();
                results.set(result.index(), result.value());
                completed++;
                if (nextIndex < itemCount) {
                    inFlight.add(submit(completions, taskFactory, nextIndex++));
                }
            }
            return results;
        } catch (ExecutionException e) {
            cancel(inFlight);
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            cancel(inFlight);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            cancel(inFlight);
            executor.shutdownNow();
        }
    }

    private static <T> Future<IndexedResult<T>> submit(
            CompletionService<IndexedResult<T>> completions,
            IntFunction<Callable<T>> taskFactory, int index) {
        return completions.submit(() -> new IndexedResult<>(index, taskFactory.apply(index).call()));
    }

    private static void cancel(Set<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private record IndexedResult<T>(int index, T value) {
    }
}
