package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoResourceServerOwnershipTest {

    private static final String IDENTIFIER = "https://api.example.com";

    @TempDir
    Path directory;

    private CognitoService service(StorageBackend<String, ResourceServer> resourceServers) {
        return new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(), resourceServers,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", new RegionResolver("us-east-1", "000000000000"), null, null);
    }

    private static String createPool(CognitoService service) {
        return service.createUserPool(Map.of("PoolName", "Ownership test"), "us-east-1").getId();
    }

    @Test
    void incarnationSurvivesUpdatesAndStorageReloadWithoutBeingInventedForLegacyData() throws Exception {
        Path file = directory.resolve("resource-servers.json");
        TypeReference<Map<String, ResourceServer>> type = new TypeReference<>() {};
        PersistentStorage<String, ResourceServer> storage = new PersistentStorage<>(file, type);
        CognitoService service = service(storage);
        String pool = createPool(service);
        ResourceServer original = service.createResourceServer(pool, IDENTIFIER, "Original API", List.of());
        String incarnation = original.getIncarnationId();
        assertEquals(incarnation, UUID.fromString(incarnation).toString());
        service.updateResourceServer(pool, IDENTIFIER, "Updated API", List.of());
        assertEquals(incarnation, service.describeResourceServer(pool, IDENTIFIER).getIncarnationId());
        service.updateResourceServer(pool, IDENTIFIER, "Owned update", List.of(), incarnation);

        PersistentStorage<String, ResourceServer> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        ResourceServer restored = reloaded.get(pool + "::" + IDENTIFIER).orElseThrow();
        assertEquals(incarnation, restored.getIncarnationId());
        assertEquals("Owned update", restored.getName());
        ObjectMapper mapper = new ObjectMapper();
        ResourceServer legacy = mapper.readValue("{\"identifier\":\"legacy\"}", ResourceServer.class);
        assertNull(legacy.getIncarnationId());
        assertNull(mapper.readValue(mapper.writeValueAsBytes(legacy), ResourceServer.class).getIncarnationId());

        service.deleteResourceServer(pool, IDENTIFIER);
        ResourceServer replacement = service.createResourceServer(pool, IDENTIFIER, "Foreign API", List.of());
        replacement.setCreationDate(original.getCreationDate());
        assertNotEquals(incarnation, replacement.getIncarnationId());
        assertFalse(service.deleteResourceServer(pool, IDENTIFIER, incarnation));
        assertEquals("Foreign API", service.describeResourceServer(pool, IDENTIFIER).getName());
        AwsException failure = assertThrows(AwsException.class, () ->
                service.updateResourceServer(pool, IDENTIFIER, "Stale update", List.of(), incarnation));
        assertEquals("ResourceConflictException", failure.getErrorCode());
        assertEquals("Foreign API", service.describeResourceServer(pool, IDENTIFIER).getName());
        assertTrue(service.deleteResourceServer(pool, IDENTIFIER, replacement.getIncarnationId()));
        assertTrue(service.deleteResourceServer(pool, IDENTIFIER, incarnation));
    }

    @Test
    void unknownOwnershipFailsClosedForBothCurrentAndLegacyEntities() {
        InMemoryStorage<String, ResourceServer> storage = new InMemoryStorage<>();
        CognitoService service = service(storage);
        String pool = createPool(service);
        ResourceServer server = service.createResourceServer(pool, IDENTIFIER, "Original API", List.of());
        String incarnation = server.getIncarnationId();
        assertThrows(IllegalStateException.class, () -> service.deleteResourceServer(pool, IDENTIFIER, null));
        assertThrows(IllegalStateException.class, () ->
                service.updateResourceServer(pool, IDENTIFIER, "Unknown owner", List.of(), ""));
        server.setIncarnationId(null);
        assertThrows(IllegalStateException.class, () -> service.deleteResourceServer(pool, IDENTIFIER, incarnation));
        assertThrows(IllegalStateException.class, () ->
                service.updateResourceServer(pool, IDENTIFIER, "Unknown entity", List.of(), incarnation));
        assertEquals("Original API", service.describeResourceServer(pool, IDENTIFIER).getName());
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void checkedDeletionSerializesWithEveryPublicResourceServerWriter(Writer writer) throws Exception {
        BlockingResourceServerStorage storage = new BlockingResourceServerStorage();
        CognitoService service = service(storage);
        String pool = createPool(service);
        ResourceServer original = service.createResourceServer(pool, IDENTIFIER, "Original API", List.of());
        storage.arm();
        CountDownLatch writerStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> deletion = executor.submit(() ->
                    service.deleteResourceServer(pool, IDENTIFIER, original.getIncarnationId()));
            assertTrue(storage.readReached.await(5, TimeUnit.SECONDS));
            Future<String> mutation = executor.submit(() -> {
                writerStarted.countDown();
                try {
                    return switch (writer) {
                        case CREATE -> {
                            service.createResourceServer(pool, IDENTIFIER, "Foreign API", List.of());
                            yield "complete";
                        }
                        case UPDATE -> {
                            service.updateResourceServer(pool, IDENTIFIER, "Public update", List.of());
                            yield "complete";
                        }
                        case DELETE -> {
                            service.deleteResourceServer(pool, IDENTIFIER);
                            yield "complete";
                        }
                        case POOL_DELETE -> {
                            service.deleteUserPool(pool);
                            yield "complete";
                        }
                    };
                } catch (AwsException failure) {
                    return failure.getErrorCode();
                }
            });
            try {
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS));
            } finally {
                storage.releaseRead.countDown();
            }
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            String result = mutation.get(5, TimeUnit.SECONDS);
            if (writer == Writer.CREATE) {
                assertEquals("complete", result);
                assertNotEquals(original.getIncarnationId(), service.describeResourceServer(pool, IDENTIFIER).getIncarnationId());
                assertEquals("Foreign API", service.describeResourceServer(pool, IDENTIFIER).getName());
            } else if (writer == Writer.POOL_DELETE) {
                assertEquals("complete", result);
                assertThrows(AwsException.class, () -> service.describeUserPool(pool));
                assertTrue(storage.keys().isEmpty());
            } else {
                assertEquals("ResourceNotFoundException", result);
                assertTrue(storage.keys().isEmpty());
            }
        } finally {
            storage.releaseRead.countDown();
        }
    }

    @Test
    void checkedUpdateCannotRaceWithPublicDeletionAndRecreation() throws Exception {
        BlockingResourceServerStorage storage = new BlockingResourceServerStorage();
        CognitoService service = service(storage);
        String pool = createPool(service);
        ResourceServer original = service.createResourceServer(pool, IDENTIFIER, "Original API", List.of());
        String incarnation = original.getIncarnationId();
        storage.arm();
        CountDownLatch writerStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResourceServer> update = executor.submit(() ->
                    service.updateResourceServer(pool, IDENTIFIER, "Owned update", List.of(), incarnation));
            assertTrue(storage.readReached.await(5, TimeUnit.SECONDS));
            Future<ResourceServer> recreation = executor.submit(() -> {
                writerStarted.countDown();
                service.deleteResourceServer(pool, IDENTIFIER);
                return service.createResourceServer(pool, IDENTIFIER, "Foreign API", List.of());
            });
            try {
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> recreation.get(200, TimeUnit.MILLISECONDS));
            } finally {
                storage.releaseRead.countDown();
            }
            assertEquals("Owned update", update.get(5, TimeUnit.SECONDS).getName());
            assertNotEquals(incarnation, recreation.get(5, TimeUnit.SECONDS).getIncarnationId());
            assertEquals("Foreign API", service.describeResourceServer(pool, IDENTIFIER).getName());
        } finally {
            storage.releaseRead.countDown();
        }
    }

    private enum Writer {
        CREATE, UPDATE, DELETE, POOL_DELETE
    }

    private static final class BlockingResourceServerStorage extends InMemoryStorage<String, ResourceServer> {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch readReached = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        private void arm() {
            armed.set(true);
        }

        @Override
        public Optional<ResourceServer> get(String key) {
            Optional<ResourceServer> result = super.get(key);
            if (armed.compareAndSet(true, false)) {
                readReached.countDown();
                try {
                    if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out releasing the ownership read");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ownership read was interrupted", failure);
                }
            }
            return result;
        }
    }
}
