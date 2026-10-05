package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;

import java.util.HashMap;
import java.util.Map;

/** In-memory stores shared by every service built on the same factory, so a second service sees a "restart". */
final class InMemoryStorageFactory extends StorageFactory {

    private final String accountId;
    private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

    InMemoryStorageFactory(String accountId) {
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
