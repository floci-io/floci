package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.appconfig.model.Application;
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
 * TagResource and UntagResource on an application read it, change its tags and write it back. They
 * change a copy, so an application read before the write keeps its prior tags, and they hold the
 * service lock, so a DeleteApplication arriving mid-write waits for it rather than finish first and
 * have the write store the application again.
 */
class AppConfigServiceTaggingTest {

    private static final String ACCOUNT = "000000000000";
    private static final String APPLICATIONS = "appconfig-applications.json";

    @Test
    void anApplicationReadBeforeATagOrUntagKeepsItsPriorTags() {
        AppConfigService service = new AppConfigService(new InMemoryStorageFactory(ACCOUNT), null);
        Application created = service.createApplication(Map.of("Name", "orders"));
        service.tagApplication(created.getId(), Map.of("team", "payments"));
        Application beforeTag = service.getApplication(created.getId());

        service.tagApplication(created.getId(), Map.of("tier", "gold"));

        assertEquals(Map.of("team", "payments"), beforeTag.getTags());
        Application beforeUntag = service.getApplication(created.getId());
        assertEquals(Map.of("team", "payments", "tier", "gold"), beforeUntag.getTags());

        service.untagApplication(created.getId(), List.of("team"));

        assertEquals(Map.of("team", "payments", "tier", "gold"), beforeUntag.getTags());
        assertEquals(Map.of("tier", "gold"), service.getApplication(created.getId()).getTags());
    }

    /**
     * The tag write is parked inside its write to the store, inside the section the service lock
     * guards, and the delete must be seen waiting on it.
     */
    @Test
    void anApplicationDeletedDuringATagWriteStaysDeleted() throws Exception {
        ParkingStore<Application> store = new ParkingStore<>();
        AppConfigService service = serviceWithApplicationStore(store);
        Application app = service.createApplication(Map.of("Name", "orders"));

        store.parkNextWrite();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> tag = pool.submit(() -> service.tagApplication(app.getId(), Map.of("team", "payments")));
            assertTrue(store.awaitParked(), "the tag write must reach the store");
            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<?> delete = pool.submit(() -> {
                deleteThread.set(Thread.currentThread());
                service.deleteApplication(app.getId());
            });
            awaitBlockedOrDone(deleteThread, delete);
            assertFalse(delete.isDone(), "the delete ran while a tag write on the same application was mid-flight");
            assertEquals(Thread.State.BLOCKED, deleteThread.get().getState(),
                    "the delete waits on the service's monitor");

            store.release();
            tag.get(2, TimeUnit.SECONDS);
            delete.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError(e.getCause());
        } finally {
            store.release();
            pool.shutdownNow();
        }

        assertTrue(service.listApplications().isEmpty(), "the application outlived its delete");
    }

    /** An AppConfig service whose application store is {@code store} and every other store in memory. */
    private static AppConfigService serviceWithApplicationStore(StorageBackend<String, Application> store) {
        return new AppConfigService(new InMemoryStorageFactory(ACCOUNT).withStore(APPLICATIONS, store), null);
    }
}
