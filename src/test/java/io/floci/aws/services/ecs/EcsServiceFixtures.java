package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.exec.EcsExecSessionRegistry;
import io.floci.aws.testing.InMemoryStorageFactory;

import java.time.Clock;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link EcsService} over in-memory storage for account 000000000000 in us-east-1, for unit tests
 * that drive the service directly. It is in mock mode, and its containers and load balancer
 * registration are mocks, unless a test changes them through {@link #builder()}. Storage is a fresh
 * {@link InMemoryStorageFactory} unless the test passes its own, and is initialised; the reconciler
 * is not scheduled, so tests call {@code reconcileServices()} themselves.
 */
final class EcsServiceFixtures {

    static final String ACCOUNT = "000000000000";
    static final String REGION = "us-east-1";

    private EcsServiceFixtures() {
    }

    static EcsService mockModeService() {
        return builder().build();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private EcsContainerManager containerManager = mock(EcsContainerManager.class);
        private EcsLoadBalancerRegistrar loadBalancerRegistrar = mock(EcsLoadBalancerRegistrar.class);
        private EcsEventPublisher eventPublisher;
        private StorageFactory storageFactory;
        private CloudWatchMetricsService cloudWatchMetricsService;
        private EcsServiceDiscoveryRegistrar discoveryRegistrar;
        private Clock clock = Clock.systemUTC();
        private boolean dockerMode;

        private Builder() {
        }

        Builder containerManager(EcsContainerManager containerManager) {
            this.containerManager = containerManager;
            return this;
        }

        Builder loadBalancerRegistrar(EcsLoadBalancerRegistrar loadBalancerRegistrar) {
            this.loadBalancerRegistrar = loadBalancerRegistrar;
            return this;
        }

        Builder eventPublisher(EcsEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        /** Storage to build on; a second service over the same storage is a restart. */
        Builder storageFactory(StorageFactory storageFactory) {
            this.storageFactory = storageFactory;
            return this;
        }

        Builder cloudWatchMetricsService(CloudWatchMetricsService cloudWatchMetricsService) {
            this.cloudWatchMetricsService = cloudWatchMetricsService;
            return this;
        }

        /** The Cloud Map side; none unless the test gives one. */
        Builder discoveryRegistrar(EcsServiceDiscoveryRegistrar discoveryRegistrar) {
            this.discoveryRegistrar = discoveryRegistrar;
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Docker mode, for a test whose container manager stands in for Docker. */
        Builder dockerMode() {
            this.dockerMode = true;
            return this;
        }

        EcsService build() {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().ecs().mock()).thenReturn(!dockerMode);
            when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
            EcsService service = new EcsService(new RegionResolver(REGION, ACCOUNT), containerManager, config,
                    loadBalancerRegistrar,
                    storageFactory != null ? storageFactory : new InMemoryStorageFactory(ACCOUNT), eventPublisher,
                    new EcsExecSessionRegistry(), discoveryRegistrar, cloudWatchMetricsService, clock);
            service.initializeStorage();
            return service;
        }
    }
}
