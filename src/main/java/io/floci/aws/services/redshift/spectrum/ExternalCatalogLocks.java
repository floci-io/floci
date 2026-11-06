package io.floci.aws.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Coordinates Spectrum catalog mutations with readers, including aliases of the same Glue database. */
@ApplicationScoped
public class ExternalCatalogLocks {
    private static final Duration DEFAULT_TRANSACTION_TIMEOUT = Duration.ofSeconds(30);

    private final Duration transactionTimeout;
    private final Map<CatalogKey, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public ExternalCatalogLocks() {
        this(DEFAULT_TRANSACTION_TIMEOUT);
    }

    public ExternalCatalogLocks(Duration transactionTimeout) {
        this.transactionTimeout = transactionTimeout;
    }

    public Guard read(ExternalSchemaBinding binding, boolean transactionOpen) {
        return acquire(binding, false, transactionOpen);
    }

    public Guard write(ExternalSchemaBinding binding) {
        return acquire(binding, true, false);
    }

    private Guard acquire(ExternalSchemaBinding binding, boolean write, boolean transactionOpen) {
        CatalogKey key = new CatalogKey(binding.accountId(), binding.glueDatabase().toLowerCase(Locale.ROOT));
        ReentrantReadWriteLock catalog = locks.computeIfAbsent(key, ignored -> new ReentrantReadWriteLock(true));
        Lock lock = write ? catalog.writeLock() : catalog.readLock();
        try {
            if (transactionOpen) {
                if (!lock.tryLock(transactionTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new SpectrumReadException("58030", "Timed out waiting for external catalog access");
                }
            } else {
                lock.lockInterruptibly();
            }
            return new Guard(lock);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpectrumReadException("58030", "Interrupted while waiting for external catalog access", exception);
        }
    }

    private record CatalogKey(String accountId, String databaseName) { }

    public record Guard(Lock lock) implements AutoCloseable {
        @Override
        public void close() {
            lock.unlock();
        }
    }
}
