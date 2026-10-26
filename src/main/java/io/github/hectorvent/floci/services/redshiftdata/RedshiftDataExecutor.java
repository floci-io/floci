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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

@ApplicationScoped
class RedshiftDataExecutor implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RedshiftDataExecutor.class);

    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor deliveries;
    private final Map<String, RedshiftDataExecution> executions = new HashMap<>();
    private final int shutdownSeconds;
    // Status writes hold the read lock; reset holds the write lock, so no write lands after a reset.
    private final ReentrantReadWriteLock resetLock = new ReentrantReadWriteLock();
    private volatile boolean closed;
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

    RedshiftDataExecution submit(String account, String region, String id,
                                 Consumer<RedshiftDataExecution> work) {
        return submit(account, region, id, () -> { }, work, (execution, failure) -> { });
    }

    /**
     * Reserves queue capacity before {@code admit} writes any state. The worker waits for admission
     * to succeed. {@code admit} runs under the reset read lock, so reset cannot split admission.
     * {@code onFailure} runs when
     * the worker dies with an unexpected exception, so the statement can still reach a terminal state.
     */
    RedshiftDataExecution submit(String account, String region, String id, Runnable admit,
                                 Consumer<RedshiftDataExecution> work,
                                 BiConsumer<RedshiftDataExecution, RuntimeException> onFailure) {
        resetLock.readLock().lock();
        try {
            if (closed) {
                throw new AwsException("ActiveStatementsExceededException", "Data API is shutting down", 400);
            }
            String key = account + ":" + id;
            RedshiftDataExecution execution = new RedshiftDataExecution(generation);
            CountDownLatch ready = new CountDownLatch(1);
            AtomicBoolean admitted = new AtomicBoolean();
            AtomicBoolean claimed = new AtomicBoolean();
            Runnable task = () -> {
                if (closed && ready.getCount() != 0) {
                    return;
                }
                try {
                    ready.await();
                } catch (InterruptedException expected) {
                    // The admitting thread completes a pending reservation after shutdown interrupts its worker.
                    Thread.currentThread().interrupt();
                    if (ready.getCount() != 0) {
                        return;
                    }
                }
                if (!admitted.get() || !claimed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    RequestScopes.runAs(account, region, () -> work.accept(execution));
                } catch (RuntimeException failure) {
                    LOG.errorv(failure, "Redshift Data worker failed for statement {0} in account {1}", id, account);
                    recover(execution, failure, onFailure, id, account, region);
                } finally {
                    execution.completed();
                    synchronized (this) {
                        executions.remove(key, execution);
                    }
                }
            };
            execution.task(task);
            synchronized (this) {
                executions.put(key, execution);
            }
            try {
                workers.execute(task);
            } catch (RejectedExecutionException failure) {
                synchronized (this) {
                    executions.remove(key, execution);
                }
                throw new AwsException("ActiveStatementsExceededException", "Too many active statements", 400);
            }
            try {
                admit.run();
                admitted.set(true);
            } catch (RuntimeException | Error failure) {
                workers.remove(task);
                synchronized (this) {
                    executions.remove(key, execution);
                }
                execution.completed();
                throw failure;
            } finally {
                boolean finishAfterShutdown;
                synchronized (this) {
                    finishAfterShutdown = closed && admitted.get();
                    if (finishAfterShutdown) {
                        execution.requestCancellation();
                    }
                    ready.countDown();
                }
                if (finishAfterShutdown) {
                    Thread.startVirtualThread(task);
                }
            }
            return execution;
        } finally {
            resetLock.readLock().unlock();
        }
    }

    /** Keeps receipt lookup, admission and rejected-admission cleanup in one reset generation. */
    <T> T admitting(Supplier<T> admission) {
        resetLock.readLock().lock();
        try {
            return admission.get();
        } finally {
            resetLock.readLock().unlock();
        }
    }

    private static void recover(RedshiftDataExecution execution, RuntimeException failure,
                                BiConsumer<RedshiftDataExecution, RuntimeException> onFailure,
                                String id, String account, String region) {
        try {
            RequestScopes.runAs(account, region, () -> onFailure.accept(execution, failure));
        } catch (RuntimeException secondary) {
            LOG.errorv(secondary, "Unable to mark statement {0} in account {1} failed after a worker error", id, account);
        }
    }

    synchronized RedshiftDataExecution execution(String account, String id) {
        return executions.get(account + ":" + id);
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

    void publish(RedshiftDataExecution execution, Runnable write) {
        resetLock.readLock().lock();
        try {
            if (execution.isValid() && execution.generation() == generation) {
                write.run();
            }
        } finally {
            resetLock.readLock().unlock();
        }
    }

    void deliver(RedshiftDataExecution execution, String account, String region,
                 String id, Runnable delivery) {
        resetLock.readLock().lock();
        try {
            if (!execution.isValid() || execution.generation() != generation) {
                return;
            }
            // Delivery runs outside the lock so a slow EventBridge call cannot block a reset.
            deliveries.execute(() -> RequestScopes.runAs(account, region, () -> {
                if (execution.isValid() && execution.generation() == generation) {
                    try {
                        delivery.run();
                    } catch (RuntimeException failure) {
                        // The SQL outcome is already stored; log the lost event at error level and keep the pool alive.
                        LOG.errorv(failure, "Event delivery failed for statement {0}, account {1}, region {2}",
                                id, account, region);
                    }
                }
            }));
        } catch (RejectedExecutionException failure) {
            LOG.warnv("Event delivery rejected for statement {0}, account {1}, region {2}", id, account, region);
        } finally {
            resetLock.readLock().unlock();
        }
    }

    void clear(Runnable clearStorage) {
        List<RedshiftDataExecution> active = new ArrayList<>();
        resetLock.writeLock().lock();
        try {
            generation++;
            synchronized (this) {
                active.addAll(executions.values());
                executions.clear();
            }
            for (RedshiftDataExecution execution : active) {
                execution.invalidate();
            }
            clearStorage.run();
        } finally {
            resetLock.writeLock().unlock();
            for (RedshiftDataExecution execution : active) {
                if (execution.requestCancellation()) {
                    Thread.startVirtualThread(execution::signalCancellation);
                }
                if (workers.remove(execution.task())) {
                    execution.completed();
                }
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
