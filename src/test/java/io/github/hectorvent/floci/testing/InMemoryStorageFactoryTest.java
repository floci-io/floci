package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InMemoryStorageFactoryTest {

    private static final String ACCOUNT = "123456789012";
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

    @Test
    void suppliedBackingStoreUsesTheFactoryAccountAndBothCreateOverloads() {
        InMemoryStorage<String, String> backing = new InMemoryStorage<>();
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT).withStore("items.json", backing);
        AccountAwareStorageBackend<String> store = factory.create("service", "items.json", TYPE);

        store.put("item", "value");

        assertEquals("value", new AccountAwareStorageBackend<>(backing, null, ACCOUNT).get("item").orElseThrow());
        assertSame(store, factory.create("service", "items.json", TYPE, WriteProfile.DEFAULT));
    }

    @Test
    void accountAwareOverridesRetainIdentityAndDoNotWrapTwice() {
        AccountAwareStorageBackend<String> first = AccountAwareStorageBackend.inMemory(ACCOUNT);
        AccountAwareStorageBackend<String> second = AccountAwareStorageBackend.inMemory(ACCOUNT);
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT)
                .withStore("first.json", first).withStore("second.json", second);

        factory.create("service", "first.json", TYPE).put("item", "first");
        factory.create("service", "second.json", TYPE, WriteProfile.DEFAULT).put("item", "second");

        assertSame(first, factory.create("service", "first.json", TYPE));
        assertSame(second, factory.create("service", "second.json", TYPE));
        assertEquals("first", first.get("item").orElseThrow());
        assertEquals("second", second.get("item").orElseThrow());
    }

    @Test
    void unrelatedStoresRemainSharedByFileName() {
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT)
                .withStore("overridden.json", new InMemoryStorage<String, String>());
        AccountAwareStorageBackend<String> store = factory.create("one", "other.json", TYPE);
        store.put("item", "value");

        AccountAwareStorageBackend<String> reused = factory.create("two", "other.json", TYPE, WriteProfile.DEFAULT);

        assertSame(store, reused);
        assertEquals("value", reused.get("item").orElseThrow());
    }
}
