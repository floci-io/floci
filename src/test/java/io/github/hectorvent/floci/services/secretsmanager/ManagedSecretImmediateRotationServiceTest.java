package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSecretImmediateRotationServiceTest {

    private static final String REGION = "us-east-1";

    @Test
    void publicationFailureRestoresBackendVersionLabelsAndAllowsRetry() {
        FailingStore store = new FailingStore();
        SecretsManagerService service = service(store);
        try {
            Secret secret = create(service);
            String previous = secret.getCurrentVersionId();
            AtomicReference<String> backend = new AtomicReference<>("old");
            store.failNext = true;
            assertThrows(IllegalStateException.class, () -> service.rotateManagedSecretAfter(secret.getArn(), "rds",
                    REGION, () -> { backend.set("new"); return "new"; }, () -> backend.set("old")));
            assertEquals("old", backend.get());
            assertEquals(previous, secret.getCurrentVersionId());
            assertEquals(List.of("AWSCURRENT"), secret.getVersions().get(previous).getVersionStages());
            assertEquals(1, secret.getVersions().size());
            assertNull(secret.getLastRotatedDate());

            service.rotateManagedSecretAfter(secret.getArn(), "rds", REGION,
                    () -> { backend.set("new"); return "new"; }, () -> backend.set("old"));
            assertEquals("new", backend.get());
            assertNotEquals(previous, secret.getCurrentVersionId());
            assertEquals(previous, service.getSecretValue(secret.getArn(), null, "AWSPREVIOUS", REGION).getVersionId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void backendFailureLeavesTheCurrentVersionUntouched() {
        SecretsManagerService service = service(new FailingStore());
        try {
            Secret secret = create(service);
            String previous = secret.getCurrentVersionId();
            assertThrows(IllegalStateException.class, () -> service.rotateManagedSecretAfter(secret.getArn(), "rds",
                    REGION, () -> { throw new IllegalStateException("backend refused"); },
                    () -> fail("backend did not accept a change")));
            assertEquals(previous, secret.getCurrentVersionId());
            assertEquals(1, secret.getVersions().size());
            assertDoesNotThrow(() -> service.putSecretValue(secret.getArn(), "retry", null, null, REGION, null));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void concurrentWritesAreRefusedUntilTheOwnerPublishesItsCredential() throws Exception {
        SecretsManagerService service = service(new FailingStore());
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Secret secret = create(service);
            Future<?> rotation = executor.submit(() -> service.rotateManagedSecretAfter(secret.getArn(), "rds",
                    REGION, () -> {
                        changing.countDown();
                        try {
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        return "new";
                    }, () -> fail("unexpected rollback")));
            try {
                assertTrue(changing.await(5, TimeUnit.SECONDS));
                assertThrows(AwsException.class,
                        () -> service.putSecretValue(secret.getArn(), "racing", null, null, REGION, null));
                assertThrows(AwsException.class, () -> service.deleteSecret(secret.getArn(), null, true, REGION));
                assertThrows(AwsException.class, () -> service.rotateManagedSecretAfter(secret.getArn(), "rds", REGION,
                        () -> "racing", () -> fail("racing operation must not run")));
            } finally {
                finish.countDown();
            }
            rotation.get(5, TimeUnit.SECONDS);
            assertEquals("new", service.getSecretValue(secret.getArn(), null, null, REGION).getSecretString());
        } finally {
            finish.countDown();
            service.shutdown();
        }
    }

    private static SecretsManagerService service(FailingStore store) {
        return new SecretsManagerService(store, 30, new RegionResolver(REGION, "000000000000"),
                null, new ObjectMapper(), null, List.of());
    }

    private static Secret create(SecretsManagerService service) {
        return service.createSecret("managed", "old", null, null, null, null, "rds", REGION);
    }

    private static final class FailingStore extends InMemoryStorage<String, Secret> {
        private boolean failNext;

        @Override
        public synchronized void put(String key, Secret value) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("publication failed");
            }
            super.put(key, value);
        }
    }
}
