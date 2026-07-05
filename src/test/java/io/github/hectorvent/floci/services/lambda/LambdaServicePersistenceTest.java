package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies version counters and event-invoke configs survive a restart. Two service
 * instances share the same {@link StorageFactory} backend; the second simulates a
 * process restart reloading from disk. Without the counter persisting, PublishVersion
 * after a restart re-issues already-used version numbers.
 */
class LambdaServicePersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    @Test
    void publishVersionContinuesNumberingAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();
        LambdaFunctionStore store = new LambdaFunctionStore(storage);

        LambdaService first = serviceWithStorage(store, storage);
        first.createFunction(REGION, baseRequest("versioned-fn"));
        assertEquals("1", first.publishVersion(REGION, "versioned-fn", null).getVersion());
        assertEquals("2", first.publishVersion(REGION, "versioned-fn", null).getVersion());

        LambdaService reloaded = serviceWithStorage(store, storage);
        LambdaFunction third = reloaded.publishVersion(REGION, "versioned-fn", null);
        assertEquals("3", third.getVersion());
        assertTrue(third.getFunctionArn().endsWith(":3"));
    }

    @Test
    void eventInvokeConfigSurvivesRestartIncludingUpdates() {
        SharedStorageFactory storage = new SharedStorageFactory();
        LambdaFunctionStore store = new LambdaFunctionStore(storage);

        LambdaService first = serviceWithStorage(store, storage);
        first.createFunction(REGION, baseRequest("cfg-fn"));
        first.putEventInvokeConfig(REGION, "cfg-fn", null,
                new HashMap<>(Map.of("MaximumRetryAttempts", 2, "MaximumEventAgeInSeconds", 120)));
        // updateEventInvokeConfig mutates in place — the re-put must reach the backend
        first.updateEventInvokeConfig(REGION, "cfg-fn", null,
                new HashMap<>(Map.of("MaximumRetryAttempts", 0)));

        LambdaService reloaded = serviceWithStorage(store, storage);
        FunctionEventInvokeConfig cfg = reloaded.getEventInvokeConfig(REGION, "cfg-fn", null);
        assertEquals(0, cfg.getMaximumRetryAttempts());
        assertEquals(120, cfg.getMaximumEventAgeInSeconds());
        assertTrue(cfg.getLastModifiedSeconds() > 0,
                "LastModified must round-trip through persisted JSON");
    }

    @Test
    void deletedEventInvokeConfigDoesNotReappearAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();
        LambdaFunctionStore store = new LambdaFunctionStore(storage);

        LambdaService first = serviceWithStorage(store, storage);
        first.createFunction(REGION, baseRequest("gone-fn"));
        first.putEventInvokeConfig(REGION, "gone-fn", null,
                new HashMap<>(Map.of("MaximumRetryAttempts", 1)));
        first.deleteEventInvokeConfig(REGION, "gone-fn", null);

        LambdaService reloaded = serviceWithStorage(store, storage);
        assertThrows(Exception.class, () -> reloaded.getEventInvokeConfig(REGION, "gone-fn", null));
    }

    private static LambdaService serviceWithStorage(LambdaFunctionStore store, StorageFactory storage) {
        LambdaService service = new LambdaService(store, new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(), null, new RegionResolver(REGION, "000000000000"), storage);
        service.initializeStorage();
        return service;
    }

    private static Map<String, Object> baseRequest(String name) {
        return new HashMap<>(Map.of(
                "FunctionName", name,
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "index.handler"
        ));
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            // Wrap like the production factory does so the tests exercise the
            // account-prefixed key space, not a bare backend.
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName,
                    ignored -> new AccountAwareStorageBackend<V>(new InMemoryStorage<>(), null, ACCOUNT_ID));
        }
    }
}
