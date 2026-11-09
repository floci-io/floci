package io.floci.aws.core.common;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lazily caches a non-null provider result. Failed or null resolutions are retried.
 * Concurrent first callers may resolve more than once, as with the filters' lazy getters.
 * For request-scoped beans, cache the client proxy, not a value read through that proxy.
 */
public final class MemoizedSupplier<T> implements Supplier<T> {

    private final Supplier<? extends T> supplier;
    private volatile T value;

    public MemoizedSupplier(Supplier<? extends T> supplier) {
        this.supplier = Objects.requireNonNull(supplier);
    }

    @Override
    public T get() {
        T resolved = value;
        if (resolved == null) {
            resolved = supplier.get();
            value = resolved;
        }
        return resolved;
    }
}
