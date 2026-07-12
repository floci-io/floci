package io.github.hectorvent.floci.services.elasticbeanstalk;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkApplication;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkApplicationVersion;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkEnvironment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Elastic Beanstalk applications, versions, and environments survive a restart.
 * Two service instances share the same {@link StorageFactory} backend; the second simulates
 * a process restart reloading from disk.
 */
class ElasticBeanstalkServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void applicationsVersionsAndEnvironmentsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        ElasticBeanstalkService first = serviceWithStorage(storage);
        first.createApplication(REGION, "shop", "storefront", Map.of("team", "web"));
        first.createApplicationVersion(REGION, "shop", "v1", null, false, "bucket", "key", Map.of());
        BeanstalkEnvironment created = first.createEnvironment(REGION, "shop", "shop-prod",
                null, null, null, null, null, "v1", List.of(), Map.of());

        ElasticBeanstalkService reloaded = serviceWithStorage(storage);

        List<BeanstalkApplication> apps = List.copyOf(reloaded.describeApplications(REGION, null));
        assertEquals(1, apps.size());
        assertEquals("shop", apps.getFirst().getApplicationName());
        assertEquals(List.of("v1"), apps.getFirst().getVersions());
        assertEquals(Map.of("team", "web"), apps.getFirst().getTags());

        List<BeanstalkApplicationVersion> versions =
                List.copyOf(reloaded.describeApplicationVersions(REGION, "shop", null));
        assertEquals(1, versions.size());
        assertEquals("v1", versions.getFirst().getVersionLabel());
        assertEquals("bucket", versions.getFirst().getSourceBundleBucket());

        List<BeanstalkEnvironment> envs = List.copyOf(
                reloaded.describeEnvironments(REGION, "shop", null, null, null, false));
        assertEquals(1, envs.size());
        assertEquals("shop-prod", envs.getFirst().getEnvironmentName());
        assertEquals(created.getEnvironmentId(), envs.getFirst().getEnvironmentId());
        assertEquals("Ready", envs.getFirst().getStatus());
    }

    @Test
    void updatesAndTerminationSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        ElasticBeanstalkService first = serviceWithStorage(storage);
        first.createApplication(REGION, "api", null, Map.of());
        first.updateApplication(REGION, "api", "updated description");
        first.createEnvironment(REGION, "api", "api-prod",
                null, null, null, null, null, null, List.of(), Map.of());
        first.terminateEnvironment(REGION, "api-prod", null);

        ElasticBeanstalkService reloaded = serviceWithStorage(storage);

        List<BeanstalkApplication> apps = List.copyOf(reloaded.describeApplications(REGION, List.of("api")));
        assertEquals("updated description", apps.getFirst().getDescription());

        // Terminated environments are excluded by default but visible with includeDeleted.
        assertTrue(reloaded.describeEnvironments(REGION, "api", null, null, null, false).isEmpty());
        List<BeanstalkEnvironment> all = List.copyOf(
                reloaded.describeEnvironments(REGION, "api", null, null, null, true));
        assertEquals(1, all.size());
        assertEquals("Terminated", all.getFirst().getStatus());
    }

    @Test
    void deletionsDoNotReappearAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        ElasticBeanstalkService first = serviceWithStorage(storage);
        first.createApplication(REGION, "batch", null, Map.of());
        first.createApplicationVersion(REGION, "batch", "v1", null, false, null, null, Map.of());
        first.deleteApplicationVersion(REGION, "batch", "v1");
        first.createApplication(REGION, "doomed", null, Map.of());
        first.deleteApplication(REGION, "doomed", false);

        ElasticBeanstalkService reloaded = serviceWithStorage(storage);

        assertTrue(reloaded.describeApplicationVersions(REGION, "batch", null).isEmpty());
        List<BeanstalkApplication> apps = List.copyOf(reloaded.describeApplications(REGION, null));
        assertEquals(1, apps.size());
        assertEquals("batch", apps.getFirst().getApplicationName());
        assertTrue(apps.getFirst().getVersions().isEmpty());
    }

    private static ElasticBeanstalkService serviceWithStorage(StorageFactory storage) {
        ElasticBeanstalkService service = new ElasticBeanstalkService(
                new RegionResolver("us-east-1", "000000000000"), storage);
        service.initializeStorage();
        return service;
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
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
