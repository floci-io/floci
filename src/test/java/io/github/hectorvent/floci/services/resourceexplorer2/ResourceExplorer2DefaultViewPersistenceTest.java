package io.github.hectorvent.floci.services.resourceexplorer2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.resourceexplorer2.model.Index;
import io.github.hectorvent.floci.services.resourceexplorer2.model.IndexState;
import io.github.hectorvent.floci.services.resourceexplorer2.model.IndexType;
import io.github.hectorvent.floci.services.resourceexplorer2.model.View;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * The default-view association is per-region state that must survive a process restart, the same
 * way indexes and views do. These tests share the storage backends across two service instances —
 * the second instance models a restart with persisted storage but fresh in-memory state.
 */
class ResourceExplorer2DefaultViewPersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final StorageBackend<String, Index> indexStore = new InMemoryStorage<>();
    private final StorageBackend<String, View> viewStore = new InMemoryStorage<>();
    private final StorageBackend<String, String> defaultViewStore = new InMemoryStorage<>();

    @SuppressWarnings("unchecked")
    private final Instance<ResourceProvider> providers = mock(Instance.class);
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResourceExplorer2Service newInstance() {
        return new ResourceExplorer2Service(
                providers, regionResolver, objectMapper, indexStore, viewStore, defaultViewStore);
    }

    @Test
    void userChosenDefaultViewSurvivesRestart() {
        ResourceExplorer2Service before = newInstance();
        before.onStartup(new StartupEvent());
        String autoProvisioned = before.getDefaultView(REGION);

        // Point the regional default at a custom view, distinct from the auto-provisioned one.
        View custom = before.createView(REGION, "custom-view", null, null, null, null);
        before.associateDefaultView(REGION, custom.viewArn());
        assertNotEquals(autoProvisioned, custom.viewArn());
        assertEquals(custom.viewArn(), before.getDefaultView(REGION));

        // Restart: fresh instance, same persisted storage.
        ResourceExplorer2Service after = newInstance();
        after.onStartup(new StartupEvent());

        // The association must not silently reset to the auto-provisioned default view.
        assertEquals(custom.viewArn(), after.getDefaultView(REGION));
    }

    @Test
    void disassociatedDefaultViewStaysDisassociatedAfterRestart() {
        ResourceExplorer2Service before = newInstance();
        before.onStartup(new StartupEvent());
        before.disassociateDefaultView(REGION);
        assertNull(before.getDefaultView(REGION));

        ResourceExplorer2Service after = newInstance();
        after.onStartup(new StartupEvent());

        // No view should be re-associated by a startup guess once the user removed the default.
        assertNull(after.getDefaultView(REGION));
    }

    @Test
    void reprovisionsWhenStorageHasNoActiveDefaultRegionIndex() {
        // Boot against storage that carries only a leftover DELETED index — no ACTIVE index in the
        // default region, which is what a persistent/hybrid store looks like after a prior run
        // created and deleted indexes. The old "provision only when the store is empty" guard
        // skipped provisioning here, leaving GetIndex 404 and no default view and failing the whole
        // integration suite. Startup must recover by provisioning a fresh index and default view.
        String staleArn = "arn:aws:resource-explorer-2:" + REGION + ":" + ACCOUNT + ":index/stale";
        indexStore.put(staleArn, new Index(staleArn, REGION, IndexType.AGGREGATOR,
                IndexState.DELETED, new HashMap<>(), Instant.now()));

        ResourceExplorer2Service service = newInstance();
        service.onStartup(new StartupEvent());

        // getIndex throws when the region has no ACTIVE index, so a non-null return proves recovery.
        assertNotNull(service.getIndex(REGION));
        assertNotNull(service.getDefaultView(REGION));
    }

    @Test
    void recoversAfterInProcessStorageResetWipesIndexAndView() {
        // Models a LocalStack state/reset: StorageFactory.clearAll() wipes every backend in-process
        // with NO Quarkus restart, so onStartup never re-runs. RE2 must lazily re-provision on the
        // next operation — otherwise GetIndex/GetDefaultView/Search fail for the whole service until
        // the process restarts (this is what broke the full CI suite when a reset test ran first).
        ResourceExplorer2Service service = newInstance();
        service.onStartup(new StartupEvent());

        indexStore.clear();
        viewStore.clear();
        defaultViewStore.clear();

        assertNotNull(service.getIndex(REGION));
        assertNotNull(service.getDefaultView(REGION));
    }
}
