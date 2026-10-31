package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update methods CloudFormation uses read an entity, change it and write it back. A delete the
 * AppConfig API runs on another thread in between must wait for the write and then remove the
 * entity, not finish first and have the update store it again. Each case parks the update inside
 * its write to the store and must see the delete waiting on the service.
 */
class AppConfigServiceUpdateRaceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String APPLICATIONS = "appconfig-applications.json";
    private static final String ENVIRONMENTS = "appconfig-environments.json";
    private static final String PROFILES = "appconfig-profiles.json";
    private static final String STRATEGIES = "appconfig-strategies.json";

    @Test
    void anApplicationDeletedDuringItsUpdateStaysDeleted() throws Exception {
        ParkingStore<Application> store = new ParkingStore<>();
        AppConfigService service = serviceWithStore(APPLICATIONS, store);
        Application app = service.createApplication(Map.of("Name", "orders"));

        deleteWaitsForTheWrite(store,
                () -> service.updateApplication(app.getId(), "orders-v2", null),
                () -> service.deleteApplication(app.getId()));

        assertTrue(service.findApplication(app.getId()).isEmpty(), "the entity outlived its delete");
    }

    @Test
    void aStrategyDeletedDuringItsUpdateStaysDeleted() throws Exception {
        ParkingStore<DeploymentStrategy> store = new ParkingStore<>();
        AppConfigService service = serviceWithStore(STRATEGIES, store);
        DeploymentStrategy strategy = service.createDeploymentStrategy(Map.of("Name", "gradual",
                "DeploymentDurationInMinutes", 0, "GrowthFactor", 100, "ReplicateTo", "NONE"));

        deleteWaitsForTheWrite(store,
                () -> service.updateDeploymentStrategy(strategy.getId(), "slower", 1, 0, 50f, "LINEAR"),
                () -> service.deleteDeploymentStrategy(strategy.getId()));

        assertTrue(service.listDeploymentStrategies().stream()
                .noneMatch(listed -> listed.getId().equals(strategy.getId())), "the strategy outlived its delete");
    }

    @Test
    void aProfileDeletedDuringItsUpdateStaysDeleted() throws Exception {
        ParkingStore<ConfigurationProfile> store = new ParkingStore<>();
        AppConfigService service = serviceWithStore(PROFILES, store);
        Application app = service.createApplication(Map.of("Name", "orders"));
        ConfigurationProfile profile = service.createConfigurationProfile(app.getId(),
                Map.of("Name", "settings", "LocationUri", "hosted"));

        deleteWaitsForTheWrite(store,
                () -> service.updateConfigurationProfile(app.getId(), profile.getId(), "renamed", null),
                () -> service.deleteConfigurationProfile(app.getId(), profile.getId()));

        assertTrue(service.findConfigurationProfile(profile.getId()).isEmpty(), "the entity outlived its delete");
    }

    @Test
    void anEnvironmentDeletedDuringItsUpdateStaysDeleted() throws Exception {
        ParkingStore<Environment> store = new ParkingStore<>();
        AppConfigService service = serviceWithStore(ENVIRONMENTS, store);
        Application app = service.createApplication(Map.of("Name", "orders"));
        Environment env = service.createEnvironment(app.getId(), Map.of("Name", "beta"));

        deleteWaitsForTheWrite(store,
                () -> service.updateEnvironment(app.getId(), env.getId(), "gamma", null, List.of()),
                () -> service.deleteEnvironment(app.getId(), env.getId()));

        assertTrue(service.findEnvironment(env.getId()).isEmpty(), "the entity outlived its delete");
    }

    /**
     * Parks {@code write} in its next write to {@code store}, starts {@code delete}, and requires
     * the delete to be unfinished and blocked on the service's monitor until the write is released;
     * then both finish.
     */
    private static void deleteWaitsForTheWrite(ParkingStore<?> store, Runnable write, Runnable delete)
            throws Exception {
        store.parkNextWrite();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writing = pool.submit(write);
            assertTrue(store.awaitParked(), "the write must reach the store");
            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<?> deleting = pool.submit(() -> {
                deleteThread.set(Thread.currentThread());
                delete.run();
            });
            awaitBlockedOrDone(deleteThread, deleting);
            assertFalse(deleting.isDone(), "the delete ran while a write of the same entity was mid-flight");
            assertEquals(Thread.State.BLOCKED, deleteThread.get().getState(),
                    "the delete waits on the service's monitor");

            store.release();
            writing.get(2, TimeUnit.SECONDS);
            deleting.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError(e.getCause());
        } finally {
            store.release();
            pool.shutdownNow();
        }
    }

    /** An AppConfig service whose {@code fileName} store is {@code store} and every other store in memory. */
    private static AppConfigService serviceWithStore(String fileName, StorageBackend<String, ?> store) {
        return new AppConfigService(new InMemoryStorageFactory(ACCOUNT).withStore(fileName, store), null);
    }
}
