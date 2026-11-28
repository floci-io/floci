package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
        when(ec2.allocateVpcPrivateIp(ACCOUNT, REGION, "subnet-1")).thenReturn(Optional.of("10.0.1.20"));
        leases = new VpcContainerLeases(ec2, "Test resource", () -> docker);
    }

    @Test
    void anAttachThatFailsGivesTheLeasedAddressBack() {
        when(ec2.attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "10.0.1.20"))
                .thenThrow(new IllegalStateException("docker refused"));

        assertThrows(IllegalStateException.class, () -> leases.join(ACCOUNT, REGION, null, "subnet-1", "c1", "r1"));

        verify(ec2).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
        assertFalse(leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
    }

    @Test
    void aRestartTakesBackAnAddressInTheRangeASubstitutedSubnetLeasesFrom() {
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        subnet.setCidrBlock("172.17.0.0/24");
        when(ec2.describeSubnets(REGION, List.of("subnet-1"), Map.of())).thenReturn(List.of(subnet));
        // The declared range is the Docker bridge's, so the VPC's network uses another.
        when(ec2.effectiveSubnetCidr(ACCOUNT, REGION, "subnet-1")).thenReturn(Optional.of("10.255.1.0/24"));
        when(ec2.reserveVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.255.1.20")).thenReturn(true);

        Optional<VpcContainerLeases.Lease> lease = leases.reclaim(ACCOUNT, REGION, "subnet-1", "container-1",
                List.of("172.17.0.4", "10.255.1.20"), "resource-1");

        assertEquals(Optional.of("10.255.1.20"), lease.map(VpcContainerLeases.Lease::address));
        verify(ec2, never()).reserveVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "172.17.0.4");
    }

    @Test
    void aPreferredAddressTheSubnetStillHasIsTakenBack() {
        when(ec2.reserveVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.31")).thenReturn(true);
        when(ec2.attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "10.0.1.31"))
                .thenReturn(Optional.of("vpc-net"));

        assertEquals("10.0.1.31", leases.join(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "r1", "10.0.1.31")
                .orElseThrow().address());

        verify(ec2, never()).allocateVpcPrivateIp(anyString(), anyString(), anyString());
    }

    @Test
    void aPreferredAddressSomethingElseHoldsGivesWayToTheNextFreeOne() {
        when(ec2.reserveVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.31")).thenReturn(false);
        when(ec2.attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "10.0.1.20"))
                .thenReturn(Optional.of("vpc-net"));

        assertEquals("10.0.1.20", leases.join(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "r1", "10.0.1.31")
                .orElseThrow().address());
    }

    @Test
    void aSubnetOutsideTheVpcTheResourceNamesIsNotJoined() {
        assertTrue(leases.join(ACCOUNT, REGION, "vpc-other", "subnet-1", "c1", "r1").isEmpty());

        verify(ec2, never()).allocateVpcPrivateIp(anyString(), anyString(), anyString());
    }

    @Test
    void anAddressDockerCouldNotConfirmFreeIsGivenBackOnceTheContainerIsGone() throws Exception {
        when(ec2.attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "10.0.1.20"))
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
        await("the address should be given back once a background look finds the container gone")
                .atMost(Duration.ofSeconds(5)).until(() -> !leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
        verify(ec2, timeout(5_000)).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
    }

    // Catches: DeleteSubnet and DeleteVpc waiting on a daemon that does not answer, because the
    // dependency check inspected each unconfirmed container on the request thread.
    @Test
    @Timeout(30)
    void aDaemonThatDoesNotAnswerLeavesTheDependencyCheckAnsweringFromMemory() throws Exception {
        leases = new VpcContainerLeases(ec2, "Test resource", () -> docker, Duration.ofMillis(50));
        when(ec2.attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "10.0.1.20"))
                .thenReturn(Optional.of("10.0.1.20"));
        leases.join(ACCOUNT, REGION, "vpc-1", "subnet-1", "c1", "r1");
        CountDownLatch daemonAnswers = new CountDownLatch(1);
        AtomicInteger inspects = new AtomicInteger();
        // The removal's own look fails at once; every later one hangs until the daemon answers.
        when(docker.inspectContainerCmd("c1").exec()).thenAnswer(invocation -> {
            if (inspects.getAndIncrement() == 0) {
                throw new IllegalStateException("daemon down");
            }
            daemonAnswers.await();
            throw new NotFoundException("gone");
        });
        leases.releaseIfGone("c1");
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                assertTrue(leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"), "still held until Docker answers");
                assertTrue(leases.holdsVpc(ACCOUNT, REGION, "vpc-1"));
            }
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(tookMillis < 3_000, "each check should wait at most the recheck wait, took " + tookMillis + " ms");
            // The look is still blocked in Docker, so each of the ten checks could only join it: one more
            // inspect after the removal's own means none of them started a second look.
            await("one background look should be running").atMost(Duration.ofSeconds(5))
                    .until(() -> inspects.get() >= 2);
            assertEquals(2, inspects.get(), "asking again while a look runs starts no second one");
        } finally {
            daemonAnswers.countDown();
        }

        await("the address should be given back once Docker says the container is gone")
                .atMost(Duration.ofSeconds(5)).until(() -> !leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"));
    }

    // Catches: the first DeleteSubnet after an unconfirmed removal getting DependencyViolation even
    // though a healthy daemon would say at once that the container is gone (a console asks once).
    @Test
    void aHealthyDaemonsAnswerCountsForTheCheckThatAsked() {
        join("c1", "10.0.1.20");
        AtomicReference<RuntimeException> inspect = new AtomicReference<>(new IllegalStateException("daemon down"));
        when(docker.inspectContainerCmd("c1").exec()).thenAnswer(invocation -> {
            throw inspect.get();
        });
        leases.releaseIfGone("c1");
        inspect.set(new NotFoundException("gone"));

        assertFalse(leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"), "the first check should see the address free");
    }

    // Catches: one container whose address could not be given back stopping the look before the others.
    @Test
    void oneFailedReleaseDoesNotStopTheOthers() {
        when(ec2.allocateVpcPrivateIp(ACCOUNT, REGION, "subnet-1"))
                .thenReturn(Optional.of("10.0.1.20"), Optional.of("10.0.1.21"));
        join("c1", "10.0.1.20");
        join("c2", "10.0.1.21");
        AtomicReference<RuntimeException> inspect = new AtomicReference<>(new IllegalStateException("daemon down"));
        when(docker.inspectContainerCmd(anyString()).exec()).thenAnswer(invocation -> {
            throw inspect.get();
        });
        leases.releaseIfGone("c1");
        leases.releaseIfGone("c2");
        inspect.set(new NotFoundException("gone"));
        doThrow(new IllegalStateException("EC2 refused")).when(ec2)
                .releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");

        leases.holdsSubnet(ACCOUNT, REGION, "subnet-1");

        verify(ec2, timeout(5_000)).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.21");
        assertTrue(leases.lease("c2").isEmpty(), "the second container's address should be given back");
    }

    // Catches: a removal that went unconfirmed while a look was running waiting for the next question.
    @Test
    @Timeout(30)
    void aLookTakesUpAContainerThatBecameUnconfirmedWhileItRan() throws Exception {
        when(ec2.allocateVpcPrivateIp(ACCOUNT, REGION, "subnet-1"))
                .thenReturn(Optional.of("10.0.1.20"), Optional.of("10.0.1.21"));
        join("c1", "10.0.1.20");
        join("c2", "10.0.1.21");
        CountDownLatch firstLookStarted = new CountDownLatch(1);
        CountDownLatch daemonAnswers = new CountDownLatch(1);
        AtomicInteger c1Looks = new AtomicInteger();
        when(docker.inspectContainerCmd("c1").exec()).thenAnswer(invocation -> {
            if (c1Looks.getAndIncrement() == 0) {
                throw new IllegalStateException("daemon down");
            }
            firstLookStarted.countDown();
            daemonAnswers.await();
            throw new NotFoundException("gone");
        });
        AtomicInteger c2Looks = new AtomicInteger();
        when(docker.inspectContainerCmd("c2").exec()).thenAnswer(invocation -> {
            if (c2Looks.getAndIncrement() == 0) {
                throw new IllegalStateException("daemon down");
            }
            throw new NotFoundException("gone");
        });
        leases.releaseIfGone("c1");
        leases.holdsSubnet(ACCOUNT, REGION, "subnet-1");
        assertTrue(firstLookStarted.await(5, TimeUnit.SECONDS));

        leases.releaseIfGone("c2");
        daemonAnswers.countDown();

        await("the running look should also take up c2").atMost(Duration.ofSeconds(5))
                .until(() -> leases.lease("c2").isEmpty());
    }

    private void join(String containerId, String address) {
        when(ec2.attachContainerToVpc(ACCOUNT, REGION, "vpc-1", "subnet-1", containerId, address))
                .thenReturn(Optional.of(address));
        assertEquals(address, leases.join(ACCOUNT, REGION, "vpc-1", "subnet-1", containerId, "r-" + containerId)
                .orElseThrow().address());
    }
}
