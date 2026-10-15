package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Test {@link StorageFactory} that hands out in-memory stores so a service can be constructed
 * without touching disk.
 *
 * <p>Stores are keyed by file name and shared, mirroring the runtime factory where two services
 * created on the same factory see each other's writes. Every store is scoped to the account id
 * passed to the constructor, so a service that reads through {@link AccountAwareStorageBackend}
 * behaves as it does under the emulator.
 *
 * <p>This is the shared replacement for the private in-memory {@code StorageFactory} copies that
 * individual service tests used to define; migrating those to it is a separate follow-up.
 */
public final class InMemoryStorageFactory extends StorageFactory {

    private final String accountId;
    private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

    public InMemoryStorageFactory(String accountId) {
        super(null, null);
        this.accountId = accountId;
    }

    @Override
    public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
        return create(serviceName, fileName, typeReference, WriteProfile.DEFAULT);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference,
                                                    WriteProfile profile) {
        return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                ignored -> AccountAwareStorageBackend.inMemory(accountId));
    }
}
