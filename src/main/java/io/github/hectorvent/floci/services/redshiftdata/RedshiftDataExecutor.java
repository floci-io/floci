package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ApplicationScoped
class RedshiftDataExecutor implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RedshiftDataExecutor.class);

    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor deliveries;
    private final Map<String, RedshiftDataExecution> executions = new HashMap<>();
    private final int shutdownSeconds;
    private boolean closed;
    private volatile long generation;

    @Inject
    RedshiftDataExecutor(EmulatorConfig config) {
        this(config.services().redshiftData().maxConcurrentStatements(),
                config.services().redshiftData().queueCapacity(),
                config.services().redshiftData().shutdownTimeoutSeconds());
    }

    RedshiftDataExecutor(int concurrency, int capacity, int shutdownSeconds) {
        if (concurrency <= 0 || capacity <= 0 || shutdownSeconds < 0) {
            throw new IllegalArgumentException("Invalid Redshift Data executor limits");
        }
        this.shutdownSeconds = shutdownSeconds;
        workers = pool(concurrency, capacity, "redshift-data-sql");
        deliveries = pool(2, capacity, "redshift-data-events");
    }

    private static ThreadPoolExecutor pool(int concurrency, int capacity, String name) {
        return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized RedshiftDataExecution submit(String account, String region, String id,
                                               Consumer<RedshiftDataExecution> work) {
        if (closed) {
            throw new AwsException("ActiveStatementsExceededException", "Data API is shutting down", 400);
        }
        String key = account + ":" + id;
        RedshiftDataExecution execution = new RedshiftDataExecution(generation);
        Runnable task = () -> {
            try {
                RequestScopes.runAs(account, region, () -> work.accept(execution));
            } catch (RuntimeException failure) {
                LOG.warnv(failure, "Redshift Data worker failed for statement {0} in account {1}", id, account);
            } finally {
                execution.completed();
                synchronized (this) {
                    executions.remove(key, execution);
                }
            }
        };
        execution.task(task);
        executions.put(key, execution);
        try {
            workers.execute(task);
        } catch (RejectedExecutionException failure) {
            executions.remove(key, execution);
            throw new AwsException("ActiveStatementsExceededException", "Too many active statements", 400);
        }
        return execution;
    }

    boolean cancel(String account, String id) {
        RedshiftDataExecution execution;
        synchronized (this) {
            execution = executions.get(account + ":" + id);
        }
        if (execution == null || !execution.cancel()) {
            return false;
        }
        if (workers.remove(execution.task())) {
            execution.task().run();
        }
        return true;
    }

    synchronized void publish(RedshiftDataExecution execution, Runnable write) {
        if (execution.isValid() && execution.generation() == generation) {
            write.run();
        }
    }

    synchronized void deliver(RedshiftDataExecution execution, String account, String region,
                              String id, Runnable delivery) {
        if (!execution.isValid() || execution.generation() != generation) {
            return;
        }
        try {
            deliveries.execute(() -> RequestScopes.runAs(account, region, () -> {
                if (execution.isValid() && execution.generation() == generation) {
                    delivery.run();
                }
            }));
        } catch (RejectedExecutionException failure) {
            LOG.warnv("Event delivery rejected for statement {0}, account {1}, region {2}", id, account, region);
        }
    }

    void clear(Runnable clearStorage) {
        List<RedshiftDataExecution> active;
        synchronized (this) {
            generation++;
            active = new ArrayList<>(executions.values());
            for (RedshiftDataExecution execution : active) {
                execution.invalidate();
            }
            executions.clear();
            clearStorage.run();
        }
        for (RedshiftDataExecution execution : active) {
            if (execution.requestCancellation()) {
                Thread.startVirtualThread(execution::signalCancellation);
            }
            if (workers.remove(execution.task())) {
                execution.completed();
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (this) {
            closed = true;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(shutdownSeconds).toNanos();
        workers.shutdown();
        waitUntil(workers, deadline);
        List<RedshiftDataExecution> active;
        synchronized (this) {
            active = new ArrayList<>(executions.values());
        }
        for (RedshiftDataExecution execution : active) {
            if (execution.requestCancellation()) {
                Thread.startVirtualThread(execution::signalCancellation);
            }
        }
        for (Runnable queued : workers.shutdownNow()) {
            queued.run();
        }
        deliveries.shutdown();
        waitUntil(deliveries, deadline);
        deliveries.shutdownNow();
    }

    private static void waitUntil(ThreadPoolExecutor pool, long deadline) {
        try {
            pool.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException expected) {
            // Continue shutdown cleanup while preserving the caller's interruption.
            Thread.currentThread().interrupt();
        }
    }
}
