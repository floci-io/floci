package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update methods CloudFormation uses read an entity, change it and write it back. A delete the
 * AppConfig API runs on another thread in between must wait for the write and then remove the
 * entity, not finish first and have the update store it again. Each case runs that delete from
 * inside the update's write to the store.
 */
class AppConfigServiceUpdateRaceTest {

    private static final String ACCOUNT = "000000000000";

    /** In-memory stores that run a one-shot hook before the next write to a chosen file. */
    private static final class HookedStorageFactory extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        private final Map<String, Runnable> beforePut = new ConcurrentHashMap<>();

        HookedStorageFactory() {
            super(null, null);
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
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, name ->
                    new AccountAwareStorageBackend<V>(new InMemoryStorage<String, V>() {
                        @Override
                        public void put(String key, V value) {
                            Runnable hook = beforePut.remove(name);
                            if (hook != null) {
                                hook.run();
                            }
                            super.put(key, value);
                        }
                    }, null, ACCOUNT));
        }
    }

    private final HookedStorageFactory storage = new HookedStorageFactory();
    private final AppConfigService service = new AppConfigService(storage, null);

    @Test
    void anApplicationDeletedDuringItsUpdateStaysDeleted() {
        Application app = service.createApplication(Map.of("Name", "orders"));
        boolean deleteWaited = deleteDuringNextWrite("appconfig-applications.json",
                () -> service.deleteApplication(app.getId()),
                () -> service.updateApplication(app.getId(), "orders-v2", null));

        assertTrue(deleteWaited, "the delete waits for the update's write");
        assertGone(() -> service.findApplication(app.getId()).isEmpty());
    }

    @Test
    void anApplicationDeletedDuringATagWriteStaysDeleted() {
        Application app = service.createApplication(Map.of("Name", "orders"));
        boolean deleteWaited = deleteDuringNextWrite("appconfig-applications.json",
                () -> service.deleteApplication(app.getId()),
                () -> service.tagApplication(app.getId(), Map.of("team", "payments")));

        assertTrue(deleteWaited, "the delete waits for the tag write");
        assertGone(() -> service.findApplication(app.getId()).isEmpty());
    }

    @Test
    void aStrategyDeletedDuringItsUpdateStaysDeleted() {
        DeploymentStrategy strategy = service.createDeploymentStrategy(Map.of("Name", "gradual",
                "DeploymentDurationInMinutes", 0, "GrowthFactor", 100, "ReplicateTo", "NONE"));
        boolean deleteWaited = deleteDuringNextWrite("appconfig-strategies.json",
                () -> service.deleteDeploymentStrategy(strategy.getId()),
                () -> service.updateDeploymentStrategy(strategy.getId(), "slower", 1, 0, 50f, "LINEAR"));

        assertTrue(deleteWaited, "the delete waits for the update's write");
        assertGone(() -> service.listDeploymentStrategies().stream()
                .noneMatch(listed -> listed.getId().equals(strategy.getId())));
    }

    @Test
    void aProfileDeletedDuringItsUpdateStaysDeleted() {
        Application app = service.createApplication(Map.of("Name", "orders"));
        ConfigurationProfile profile = service.createConfigurationProfile(app.getId(),
                Map.of("Name", "settings", "LocationUri", "hosted"));
        boolean deleteWaited = deleteDuringNextWrite("appconfig-profiles.json",
                () -> service.deleteConfigurationProfile(app.getId(), profile.getId()),
                () -> service.updateConfigurationProfile(app.getId(), profile.getId(), "renamed", null));

        assertTrue(deleteWaited, "the delete waits for the update's write");
        assertGone(() -> service.findConfigurationProfile(profile.getId()).isEmpty());
    }

    @Test
    void anEnvironmentDeletedDuringItsUpdateStaysDeleted() {
        Application app = service.createApplication(Map.of("Name", "orders"));
        Environment env = service.createEnvironment(app.getId(), Map.of("Name", "beta"));
        boolean deleteWaited = deleteDuringNextWrite("appconfig-environments.json",
                () -> service.deleteEnvironment(app.getId(), env.getId()),
                () -> service.updateEnvironment(app.getId(), env.getId(), "gamma", null, List.of()));

        assertTrue(deleteWaited, "the delete waits for the update's write");
        assertGone(() -> service.findEnvironment(env.getId()).isEmpty());
    }

    /**
     * Runs {@code write}, and from inside its write to {@code fileName} starts {@code delete} on
     * another thread. Returns whether that delete was still waiting half a second later, as it must
     * while the write holds the service.
     */
    private boolean deleteDuringNextWrite(String fileName, Runnable delete, Runnable write) {
        boolean[] waited = new boolean[1];
        storage.beforePut.put(fileName, () -> {
            CountDownLatch done = new CountDownLatch(1);
            Thread thread = new Thread(() -> {
                delete.run();
                done.countDown();
            });
            thread.start();
            try {
                waited[0] = !done.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        write.run();
        return waited[0];
    }

    /** Waits up to two seconds for the delete that waited to finish. */
    private static void assertGone(BooleanSupplier gone) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!gone.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(gone.getAsBoolean(), "the delete removes the entity once the write is done");
    }
}
