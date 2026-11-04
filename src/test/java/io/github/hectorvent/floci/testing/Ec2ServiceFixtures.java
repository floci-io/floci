package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2ContainerManager;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mock-mode {@link Ec2Service} over in-memory storage for account 000000000000, for unit tests
 * that drive the service directly: containers, port forwarding and images are mocks, so nothing
 * leaves the JVM.
 */
public final class Ec2ServiceFixtures {

    public static final String ACCOUNT = "000000000000";

    private Ec2ServiceFixtures() {
    }

    public static Ec2Service mockModeService() {
        return mockModeService(new InMemoryStorageFactory(ACCOUNT));
    }

    /** Over {@code storage}, for a test that builds a second service on the same stores. */
    public static Ec2Service mockModeService(InMemoryStorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                storage);
    }
}
