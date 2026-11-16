package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Joining a container to its subnet's VPC network, and giving the address back only once Docker says it is gone. */
class VpcContainerLeasesTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private Ec2Service ec2;
    private DockerClient docker;
    private VpcContainerLeases leases;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2).runWithSubnetReferencesHeld(eq(REGION), any());
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        when(ec2.describeSubnets(REGION, List.of("subnet-1"), Map.of())).thenReturn(List.of(subnet));
        when(ec2.allocateVpcPrivateIp(REGION, "subnet-1")).thenReturn(Optional.of("10.0.1.20"));
        leases = new VpcContainerLeases(ec2, "Test resource", () -> docker);
    }

    @Test
    void anAttachThatFailsGivesTheLeasedAddressBack() {
        when(ec2.attachContainerToVpc(REGION, "vpc-1", "subnet-1", "c1", "10.0.1.20"))
                .thenThrow(new IllegalStateException("docker refused"));

        assertThrows(IllegalStateException.class, () -> leases.join(ACCOUNT, REGION, null, "subnet-1", "c1", "r1"));

        verify(ec2).releaseVpcPrivateIp(REGION, "subnet-1", "10.0.1.20");
        assertFalse(leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
    }

    @Test
    void aSubnetOutsideTheVpcTheResourceNamesIsNotJoined() {
        assertTrue(leases.join(ACCOUNT, REGION, "vpc-other", "subnet-1", "c1", "r1").isEmpty());

        verify(ec2, never()).allocateVpcPrivateIp(anyString(), anyString());
    }

    @Test
    void anAddressDockerCouldNotConfirmFreeIsGivenBackOnceTheContainerIsGone() {
        when(ec2.attachContainerToVpc(REGION, "vpc-1", "subnet-1", "c1", "10.0.1.20"))
                .thenReturn(Optional.of("10.0.1.20"));
        assertEquals("10.0.1.20", leases.join(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "r1")
                .orElseThrow().address());
        AtomicReference<RuntimeException> inspect = new AtomicReference<>(new IllegalStateException("daemon down"));
        when(docker.inspectContainerCmd("c1").exec()).thenAnswer(invocation -> {
            if (inspect.get() != null) {
                throw inspect.get();
            }
            return mock(InspectContainerResponse.class);
        });

        leases.releaseIfGone("c1");
        assertTrue(leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"), "a Docker error keeps the address");

        inspect.set(null);
        assertTrue(leases.holdsVpc(ACCOUNT, REGION, "vpc-1"), "a container still present keeps the address");

        inspect.set(new NotFoundException("gone"));
        assertFalse(leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
        verify(ec2).releaseVpcPrivateIp(REGION, "subnet-1", "10.0.1.20");
    }
}
