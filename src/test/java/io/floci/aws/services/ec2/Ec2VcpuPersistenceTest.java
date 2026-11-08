package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2VcpuPersistenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void reloadedInstancesRetainSpotProvenance(String mode, @TempDir Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(dir.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.empty());
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(5);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("ec2")).thenReturn(mode);
        when(access.storageFlushInterval("ec2")).thenReturn(60000L);
        StorageFactory first = new StorageFactory(config, access);
        try {
            Ec2Service service = service(config, first);
            Instance spot = guest("i-persisted-spot", InstanceState.running());
            spot.setSpot(true);
            Instance ordinary = guest("i-persisted-ordinary", InstanceState.running());
            service.putInstanceForTest(spot);
            service.putInstanceForTest(ordinary);
        } finally {
            first.shutdownAll();
        }
        StorageFactory reloaded = new StorageFactory(config, access);
        try {
            Ec2Service service = service(config, reloaded);
            Map<String, Instance> instances = service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .collect(Collectors.toMap(Instance::getInstanceId, i -> i));
            assertTrue(instances.get("i-persisted-spot").isSpot());
            assertFalse(instances.get("i-persisted-ordinary").isSpot());
        } finally {
            reloaded.shutdownAll();
        }
    }

    private static Instance guest(String id, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setInstanceType("t3.large");
        instance.setOwnerId("000000000000");
        instance.setRegion("us-east-1");
        instance.setState(state);
        return instance;
    }

    private static Ec2Service service(EmulatorConfig config, StorageFactory storage) {
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(), storage);
    }
}
