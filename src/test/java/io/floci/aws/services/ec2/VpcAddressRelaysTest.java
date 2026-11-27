package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.Container;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An endpoint served at its own address in a subnet, by a relay container EC2 leases the address for. */
class VpcAddressRelaysTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private Ec2Service ec2;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerBuilder.Builder builder;
    private VpcRelayFixture fixture;
    private VpcAddressRelays relays;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        subnet.setCidrBlock("10.0.1.0/24");
        when(ec2.describeSubnets(REGION, List.of("subnet-1"), Map.of())).thenReturn(List.of(subnet));
        fixture = new VpcRelayFixture(ec2);
        lifecycleManager = fixture.lifecycleManager;
        builder = fixture.builder;
        relays = fixture.relays("test-endpoint", "floci-net");
    }

    @Test
    void aRelayListensOnTheEndpointPortAtItsAddressAndForwardsToTheTarget() {
        assertEquals("10.0.1.20", relays.serve(relay("ep1", 5432, 7001), null));

        verify(builder).withEntrypoint(List.of("socat"));
        verify(builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:7001,nodelay"));
        verify(builder).withDockerNetwork(Optional.of("floci-net"));
        verify(builder).withHostDockerInternalOnLinux();
        verify(ec2).attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "relay-1", "10.0.1.20");
        assertEquals(Optional.of("10.0.1.20"), relays.address("ep1"));
        assertTrue(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
        assertTrue(relays.holdsVpc(ACCOUNT, REGION, "vpc-1"));
    }

    @Test
    void aSameNamedContainerLeftBehindIsRemovedBeforeTheRelayStarts() {
        relays.serve(relay("ep1", 5432, 7001), null);

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).removeIfExistsStrict(relays.containerName("ep1"));
        order.verify(lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        verify(builder).withName(relays.containerName("ep1"));
    }

    @Test
    void aRelayServingTheEndpointTheSameWayIsKept() {
        relays.serve(relay("ep1", 5432, 7001), null);

        assertEquals("10.0.1.20", relays.serve(relay("ep1", 5432, 7001), null));

        verify(lifecycleManager, times(1)).createAndStart(any(ContainerSpec.class), any(Consumer.class));
    }

    @Test
    void aRelayThatIsGoneIsStartedAgainAtItsAddress() {
        relays.serve(relay("ep1", 5432, 7001), null);
        fixture.present.remove("relay-1");

        assertEquals("10.0.1.20", relays.serve(relay("ep1", 5432, 7001), null));

        verify(lifecycleManager, times(2)).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        verify(ec2).attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "relay-2", "10.0.1.20");
    }

    @Test
    void aRelayToAnotherTargetReplacesTheOldOneAtItsAddress() {
        relays.serve(relay("ep1", 5432, 7001), null);

        assertEquals("10.0.1.20", relays.serve(relay("ep1", 5432, 7002), null));

        verify(lifecycleManager).removeIfExistsStrict("relay-1");
        verify(ec2).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
        verify(ec2).attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "relay-2", "10.0.1.20");
        verify(builder).withCmd(List.of("TCP-LISTEN:5432,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:host.docker.internal:7002,nodelay"));
    }

    @Test
    void anEndpointIsServedAgainAtThePreferredAddressItHeldBefore() {
        assertEquals("10.0.1.31", relays.serve(relay("ep1", 5432, 7001), "10.0.1.31"));

        verify(ec2, never()).allocateVpcPrivateIp(any(), any(), any());
    }

    @Test
    void aSubnetWithNoAddressToGiveServesNothing() {
        doReturn(Optional.empty()).when(ec2).allocateVpcPrivateIp(ACCOUNT, REGION, "subnet-1");

        assertThrows(IllegalStateException.class, () -> relays.serve(relay("ep1", 5432, 7001), null));

        assertEquals(Optional.empty(), relays.address("ep1"));
        assertFalse(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
    }

    @Test
    void aRelayThatDoesNotStartGivesItsAddressBack() {
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("relay-x");
            throw new IllegalStateException("start refused");
        }).when(lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));

        assertThrows(IllegalStateException.class, () -> relays.serve(relay("ep1", 5432, 7001), null));

        verify(ec2).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
        assertFalse(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
        assertEquals(Optional.empty(), relays.address("ep1"));
        assertEquals(List.of(), fixture.placements.all());
    }

    @Test
    void aRelayThatMayHaveStartedAndCouldNotBeRemovedStaysTrackedUntilAStopRemovesIt() {
        doAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("relay-x");
            fixture.present.add("relay-x");
            throw new IllegalStateException("start reply lost");
        }).when(lifecycleManager).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        doThrow(new IllegalStateException("daemon down")).when(lifecycleManager).removeIfExistsStrict("relay-x");

        assertThrows(IllegalStateException.class, () -> relays.serve(relay("ep1", 5432, 7001), null));

        assertEquals(Optional.of("10.0.1.20"), relays.address("ep1"));
        assertTrue(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));

        doAnswer(invocation -> fixture.present.remove(invocation.<String>getArgument(0)))
                .when(lifecycleManager).removeIfExistsStrict("relay-x");
        relays.stop("ep1");

        assertEquals(Optional.empty(), relays.address("ep1"));
        verify(ec2).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
    }

    @Test
    void aServedRelayIsWrittenDownUntilAStopRemovesIt() {
        relays.serve(relay("ep1", 5432, 7001), null);

        assertEquals(1, fixture.placements.all().size());
        assertTrue(relays.targetsPort(7001));

        relays.stop("ep1");

        assertEquals(List.of(), fixture.placements.all());
        assertFalse(relays.targetsPort(7001));
    }

    @Test
    void aRelayDockerDidNotRemoveStaysWrittenDownAndKeepsItsTargetPortTaken() {
        relays.serve(relay("ep1", 5432, 7001), null);
        doThrow(new IllegalStateException("daemon down")).when(lifecycleManager).removeIfExistsStrict("relay-1");

        assertThrows(IllegalStateException.class, () -> relays.stop("ep1"));

        assertTrue(relays.targetsPort(7001));
    }

    @Test
    void aRestartRemovesTheRelaysAnEarlierFlociWroteDown() {
        relays.serve(relay("ep1", 5432, 7001), null);
        VpcAddressRelays restarted = fixture.relays("test-endpoint", "floci-net");
        clearInvocations(lifecycleManager);

        restarted.recoverAll();

        verify(lifecycleManager).removeIfExistsStrict(restarted.containerName("ep1"));
        assertEquals(List.of(), fixture.placements.all());
        assertFalse(restarted.targetsPort(7001));
    }

    @Test
    void aRelayARestartCannotRemoveIsHeldWithItsAddressUntilAStopRemovesIt() {
        relays.serve(relay("ep1", 5432, 7001), null);
        fixture.leased.clear();
        VpcAddressRelays restarted = fixture.relays("test-endpoint", "floci-net");
        String name = restarted.containerName("ep1");
        doThrow(new IllegalStateException("daemon down")).when(lifecycleManager).removeIfExistsStrict(name);

        restarted.recoverAll();

        assertEquals(Optional.of("10.0.1.20"), restarted.address("ep1"));
        assertTrue(fixture.leased.contains("10.0.1.20"));
        assertTrue(restarted.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
        assertTrue(restarted.targetsPort(7001));

        doAnswer(invocation -> null).when(lifecycleManager).removeIfExistsStrict(name);
        restarted.stop("ep1");

        assertEquals(Optional.empty(), restarted.address("ep1"));
        assertFalse(fixture.leased.contains("10.0.1.20"));
        assertFalse(restarted.targetsPort(7001));
    }

    @Test
    void aRestartRemovesARelayOfThisFlociThatWasNeverWrittenDown() {
        Container survivor = new Container();
        Map<String, String> labels = Map.of("io.floci.service", "test-endpoint",
                "io.floci.resource-id", "ep-lost", "io.floci.owner", "4566");
        setField(survivor, "id", "relay-lost");
        setField(survivor, "labels", labels);
        when(fixture.docker.listContainersCmd().withShowAll(true).withLabelFilter(any(Map.class)).exec())
                .thenReturn(List.of(survivor));

        fixture.relays("test-endpoint", "floci-net").recoverAll();

        verify(lifecycleManager).removeIfExists("relay-lost");
    }

    @Test
    void stoppingAnEndpointNeverServedAndNeverWrittenDownAsksDockerNothing() {
        relays.stop("never");

        verify(lifecycleManager, never()).removeIfExistsStrict(any(String.class));
    }

    @Test
    void stoppingAnEndpointRemovesItsRelayAndGivesTheAddressBack() {
        relays.serve(relay("ep1", 5432, 7001), null);

        relays.stop("ep1");

        verify(lifecycleManager).removeIfExistsStrict("relay-1");
        verify(ec2).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
        assertEquals(Optional.empty(), relays.address("ep1"));
        assertFalse(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
    }

    @Test
    void aRelayDockerDidNotRemoveStaysTrackedWithItsAddressUntilALaterStopRemovesIt() {
        relays.serve(relay("ep1", 5432, 7001), null);
        doThrow(new IllegalStateException("daemon down")).when(lifecycleManager).removeIfExistsStrict("relay-1");

        assertThrows(IllegalStateException.class, () -> relays.stop("ep1"));

        assertEquals(Optional.of("10.0.1.20"), relays.address("ep1"));
        assertTrue(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
        verify(ec2, never()).releaseVpcPrivateIp(any(), any(), any(), any());

        doAnswer(invocation -> fixture.present.remove(invocation.<String>getArgument(0)))
                .when(lifecycleManager).removeIfExistsStrict("relay-1");
        relays.stop("ep1");

        assertEquals(Optional.empty(), relays.address("ep1"));
        verify(ec2).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
    }

    @Test
    void anEndpointWhoseOldRelayDockerDidNotRemoveIsNotServedAgain() {
        relays.serve(relay("ep1", 5432, 7001), null);
        doThrow(new IllegalStateException("daemon down")).when(lifecycleManager).removeIfExistsStrict("relay-1");

        assertThrows(IllegalStateException.class, () -> relays.serve(relay("ep1", 5432, 7002), null));

        verify(lifecycleManager, times(1)).createAndStart(any(ContainerSpec.class), any(Consumer.class));
        assertEquals(Optional.of("10.0.1.20"), relays.address("ep1"));
    }

    @Test
    void stoppingEveryRelayCarriesOnPastOneDockerDidNotRemove() {
        relays.serve(relay("ep1", 5432, 7001), null);
        relays.serve(relay("ep2", 5432, 7002), null);
        doThrow(new IllegalStateException("daemon down")).when(lifecycleManager).removeIfExistsStrict("relay-1");

        relays.stopAll();

        verify(lifecycleManager).removeIfExistsStrict("relay-2");
        assertEquals(Optional.empty(), relays.address("ep2"));
        assertEquals(Optional.of("10.0.1.20"), relays.address("ep1"));
    }

    @Test
    void stoppingEveryRelayRemovesEachOne() {
        relays.serve(relay("ep1", 5432, 7001), null);
        relays.serve(relay("ep2", 5432, 7002), null);

        relays.stopAll();

        verify(lifecycleManager).removeIfExistsStrict("relay-1");
        verify(lifecycleManager).removeIfExistsStrict("relay-2");
        assertFalse(relays.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
    }

    @Test
    void stoppingAnEndpointNoRelayServesRemovesNothing() {
        relays.stop("unknown");
        relays.stop(null);

        verify(lifecycleManager, never()).removeIfExistsStrict(any(String.class));
        verify(ec2, never()).releaseVpcPrivateIp(any(), any(), any(), any());
    }

    private static VpcAddressRelays.Relay relay(String id, int port, int targetPort) {
        return new VpcAddressRelays.Relay(id, ACCOUNT, REGION, "vpc-1", "subnet-1", port,
                "host.docker.internal", targetPort, "endpoint " + id);
    }

    private static void setField(Container container, String name, Object value) {
        try {
            Field field = Container.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(container, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
