package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.OrganizationRootFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM centralized root access management: {@code ListOrganizationsFeatures} +
 * enable/disable of {@code RootCredentialsManagement} / {@code RootSessions}.
 *
 * <p>Consumer contract (LZA v1.16 {@code root-user-management} module,
 * {@code aws-iam/root-user-management/index.ts}): the module reads only the enabled-feature
 * set from {@code ListOrganizationsFeatures}; a successful call means the service is
 * reachable, and each present feature flips the corresponding config flag on. Our config
 * enables both, starting from an empty set, so the module calls both enable ops.
 */
class IamOrganizationsRootFeaturesTest {

    private static final String ROOT_CREDS = "RootCredentialsManagement";
    private static final String ROOT_SESSIONS = "RootSessions";

    @Test
    void startsWithNoEnabledFeatures() {
        IamService svc = newInMemoryService();
        assertTrue(svc.listOrganizationsFeatures().isEmpty(),
                "a fresh org has no centralized root features enabled");
    }

    @Test
    void enableAddsFeaturesAndIsIdempotent() {
        IamService svc = newInMemoryService();

        List<String> afterCreds = svc.enableOrganizationsRootCredentialsManagement();
        assertTrue(afterCreds.contains(ROOT_CREDS));
        assertFalse(afterCreds.contains(ROOT_SESSIONS));

        List<String> afterSessions = svc.enableOrganizationsRootSessions();
        assertTrue(afterSessions.contains(ROOT_CREDS));
        assertTrue(afterSessions.contains(ROOT_SESSIONS));

        // Enabling again must not duplicate.
        svc.enableOrganizationsRootCredentialsManagement();
        svc.enableOrganizationsRootSessions();
        assertEquals(2, svc.listOrganizationsFeatures().size());
    }

    @Test
    void disableRemovesFeatures() {
        IamService svc = newInMemoryService();
        svc.enableOrganizationsRootCredentialsManagement();
        svc.enableOrganizationsRootSessions();

        List<String> afterDisableSessions = svc.disableOrganizationsRootSessions();
        assertTrue(afterDisableSessions.contains(ROOT_CREDS));
        assertFalse(afterDisableSessions.contains(ROOT_SESSIONS));

        List<String> afterDisableCreds = svc.disableOrganizationsRootCredentialsManagement();
        assertTrue(afterDisableCreds.isEmpty());
    }

    @Test
    void enabledFeaturesSurviveRestart(@TempDir Path dir) {
        IamService first = newPersistentService(dir);
        first.enableOrganizationsRootCredentialsManagement();
        first.enableOrganizationsRootSessions();

        IamService restarted = newPersistentService(dir);
        List<String> loaded = restarted.listOrganizationsFeatures();
        assertTrue(loaded.contains(ROOT_CREDS));
        assertTrue(loaded.contains(ROOT_SESSIONS));
        assertEquals(2, loaded.size());
    }

    private IamService newInMemoryService() {
        return new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                false
        );
    }

    private IamService newPersistentService(Path dir) {
        return new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                loadRootFeatures(dir),
                new RegionResolver("us-east-1", "000000000000"),
                false
        );
    }

    private StorageBackend<String, OrganizationRootFeatures> loadRootFeatures(Path dir) {
        PersistentStorage<String, OrganizationRootFeatures> backend = new PersistentStorage<>(
                dir.resolve("iam-org-root-features.json"),
                new TypeReference<Map<String, OrganizationRootFeatures>>() {}
        );
        backend.load();
        return backend;
    }
}
