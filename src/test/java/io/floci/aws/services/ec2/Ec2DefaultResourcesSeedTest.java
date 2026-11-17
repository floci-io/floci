package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #6247: a call that arrives while the first call for an account and
 * region is still seeding the default network waits for it instead of reading a partial network.
 */
class Ec2DefaultResourcesSeedTest {

    private static final String REGION = "us-east-1";

    @Test
    void aConcurrentFirstCallSeesTheWholeDefaultNetwork() throws Exception {
        ParkingStore<Subnet> subnets = new ParkingStore<>();
        Ec2Service service = newService(subnets);
        // Holds the first call in the middle of seeding, before any default subnet is stored.
        subnets.parkNextWrite();
        assertOperationWaitsForParkedStore(subnets,
                () -> service.ensureDefaultResources(REGION),
                () -> assertEquals(3, service.describeSubnets(REGION, List.of(), Map.of()).size(),
                        "the second call sees the three default subnets"));
    }

    private Ec2Service newService(StorageBackend<String, Subnet> subnets) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        return new Ec2Service(config, null, mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorage<>(), subnets, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
    }
}
