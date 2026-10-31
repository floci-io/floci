package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.ensureDefaultResources(REGION));
            assertTrue(subnets.awaitParked(), "the first call must reach its first subnet write");
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<Integer> second = pool.submit(() -> {
                secondThread.set(Thread.currentThread());
                return service.describeSubnets(REGION, List.of(), Map.of()).size();
            });
            awaitBlockedOrDone(secondThread, second);
            assertFalse(second.isDone(), "the second call read the default network while it was still being seeded");

            subnets.release();
            first.get(2, TimeUnit.SECONDS);
            assertEquals(3, second.get(2, TimeUnit.SECONDS), "the second call sees the three default subnets");
        } finally {
            pool.shutdownNow();
        }
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
