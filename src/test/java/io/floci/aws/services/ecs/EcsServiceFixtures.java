package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
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
 * registration are mocks, unless a test changes them through {@link #builder()}. Storage is
 * initialised; the reconciler is not scheduled, so tests call {@code reconcileServices()} themselves.
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
                    loadBalancerRegistrar, new InMemoryStorageFactory(ACCOUNT), eventPublisher,
                    new EcsExecSessionRegistry(), null, null, clock);
            service.initializeStorage();
            return service;
        }
    }
}
