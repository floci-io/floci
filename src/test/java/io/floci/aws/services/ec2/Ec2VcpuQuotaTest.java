package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2VcpuQuotaTest {
    @Test
    void defaultOffPreservesLargeBatchesAndUnsizedTypes() {
        Ec2Service service = service(5, false);
        assertEquals(6, service.runInstances("us-east-1", "ami-test", "t2.micro", 6, 6, null,
                List.of(), null, null, List.of(), null, null).getInstances().size());
        assertNotNull(launch(service, "m5.2xlarge"));
        assertNotNull(launch(service, "m999.unmeasured"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "running", "stopping", "stopped", "shutting-down", "terminated"})
    void enabledAdmissionCountsOnlyRunningInstances(String state) {
        Ec2Service service = service(2, true);
        Instance existing = instance("i-existing", state);
        service.putInstanceForTest(existing);
        if ("running".equals(state)) {
            AwsException failure = assertThrows(AwsException.class, () -> launch(service, "t3.micro"));
            assertEquals("VcpuLimitExceeded", failure.getErrorCode());
            assertEquals(400, failure.getHttpStatus());
            existing.setState(InstanceState.stopping());
            assertNotNull(launch(service, "t3.micro"));
        } else {
            assertNotNull(launch(service, "t3.micro"));
        }
    }

    @Test
    void partialBatchesFitCurrentRunningUsageAndRefusedMinimumPublishesNothing() {
        Ec2Service service = service(5, true);
        Instance running = instance("i-running", "running");
        running.setInstanceType("t3.large");
        service.putInstanceForTest(running);
        assertEquals(1, service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 4, null,
                List.of(), null, null, List.of(), null, null).getInstances().size());
        AwsException refused = assertThrows(AwsException.class, () -> service.runInstances("us-east-1", "ami-test",
                "t3.micro", 2, 4, null, List.of(), null, null, List.of(), null, null));
        assertEquals("VcpuLimitExceeded", refused.getErrorCode());
        assertEquals(2, service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                .mapToInt(reservation -> reservation.getInstances().size()).sum());
    }

    @Test
    void pendingLaunchesDoNotReserveFutureRunningCapacity() {
        Ec2Service service = service(2, true);
        Instance first = launch(service, "t3.micro");
        assertEquals("pending", first.getState().getName());
        Instance second = launch(service, "t3.micro");
        assertEquals("pending", second.getState().getName());
    }

    @Test
    void startsUseCurrentRunningUsageDeduplicateIdsAndRetryAfterStop() {
        Ec2Service service = service(2, true);
        Instance running = instance("i-running", "running");
        Instance stopped = instance("i-stopped", "stopped");
        service.putInstanceForTest(running);
        service.putInstanceForTest(stopped);
        AwsException refused = assertThrows(AwsException.class, () -> service.startInstances("us-east-1",
                List.of(stopped.getInstanceId(), stopped.getInstanceId())));
        assertEquals("VcpuLimitExceeded", refused.getErrorCode());
        assertEquals("stopped", stopped.getState().getName());
        service.stopInstances("us-east-1", List.of(running.getInstanceId()));
        service.startInstances("us-east-1", List.of(stopped.getInstanceId(), stopped.getInstanceId()));
        assertEquals("running", stopped.getState().getName());
    }

    @Test
    void startingAStoppingTargetChecksItsNewRunningCostAndCanRetry() {
        Ec2Service service = service(2, true);
        Instance running = instance("i-running", "running");
        Instance stopping = instance("i-stopping", "stopping");
        service.putInstanceForTest(running);
        service.putInstanceForTest(stopping);
        AwsException refused = assertThrows(AwsException.class,
                () -> service.startInstances("us-east-1", List.of(stopping.getInstanceId())));
        assertEquals("VcpuLimitExceeded", refused.getErrorCode());
        assertEquals("stopping", stopping.getState().getName());
        service.stopInstances("us-east-1", List.of(running.getInstanceId()));
        service.startInstances("us-east-1", List.of(stopping.getInstanceId()));
        assertEquals("running", stopping.getState().getName());
    }

    @Test
    void terminatedTargetIsRejectedBeforeQuotaAndNoEarlierTargetStarts() {
        Ec2Service service = service(2, true);
        Instance running = instance("i-running", "running");
        Instance stopped = instance("i-stopped", "stopped");
        Instance terminated = instance("i-terminated", "terminated");
        service.putInstanceForTest(running);
        service.putInstanceForTest(stopped);
        service.putInstanceForTest(terminated);
        AwsException refused = assertThrows(AwsException.class, () -> service.startInstances("us-east-1",
                List.of(stopped.getInstanceId(), terminated.getInstanceId())));
        assertEquals("IncorrectInstanceState", refused.getErrorCode());
        assertEquals("stopped", stopped.getState().getName());
        assertEquals("terminated", terminated.getState().getName());
        service.stopInstances("us-east-1", List.of(running.getInstanceId()));
        service.startInstances("us-east-1", List.of(stopped.getInstanceId()));
        assertEquals("running", stopped.getState().getName());
    }

    @Test
    void defaultOffStartsLargeAndUnsizedStoppedTargets() {
        Ec2Service service = service(0, false);
        Instance large = instance("i-large", "stopped");
        large.setInstanceType("m5.2xlarge");
        Instance unsized = instance("i-unsized", "stopped");
        unsized.setInstanceType("m999.unmeasured");
        service.putInstanceForTest(large);
        service.putInstanceForTest(unsized);
        service.startInstances("us-east-1", List.of(large.getInstanceId(), unsized.getInstanceId()));
        assertEquals("running", large.getState().getName());
        assertEquals("running", unsized.getState().getName());
    }

    @Test
    void enabledUnknownTypesAreRefusedBeforePublicationAndKnownTypesCanRetry() {
        Ec2Service service = service(4, true);
        AwsException failure = assertThrows(AwsException.class, () -> launch(service, "m999.unmeasured"));
        assertEquals("InvalidParameterValue", failure.getErrorCode());
        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        assertNotNull(launch(service, "t3.large"));
    }

    @Test
    void spotHostsAndOtherRegionsDoNotConsumeThisRegionalBucket() {
        Ec2Service service = service(2, true);
        Instance spot = instance("i-spot", "running");
        spot.setSpot(true);
        Instance host = instance("i-host", "running");
        Placement placement = new Placement("us-east-1a");
        placement.setTenancy("host");
        host.setPlacement(placement);
        Instance other = instance("i-other-region", "running");
        other.setRegion("eu-west-1");
        service.putInstanceForTest(spot);
        service.putInstanceForTest(host);
        service.putInstanceForTest(other);
        assertNotNull(launch(service, "t3.micro"));
    }

    private static Instance instance(String id, String state) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setRegion("us-east-1");
        instance.setOwnerId("000000000000");
        instance.setInstanceType("t3.micro");
        instance.setState(new InstanceState(0, state));
        return instance;
    }

    private static Instance launch(Ec2Service service, String type) {
        return service.runInstances("us-east-1", "ami-test", type, 1, 1, null,
                List.of(), null, null, List.of(), null, null).getInstances().getFirst();
    }

    private static Ec2Service service(int limit, boolean enabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.services().ec2().enforceVcpuQuotas()).thenReturn(enabled);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.empty());
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(limit);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new MemoryFactory());
    }

    private static final class MemoryFactory extends StorageFactory {
        private MemoryFactory() { super(null, null); }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                        TypeReference<Map<String, V>> type) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
