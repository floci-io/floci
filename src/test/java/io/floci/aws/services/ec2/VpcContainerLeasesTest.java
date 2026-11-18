package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
        awaitTrue(() -> !leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"),
                "the address should be given back once a background look finds the container gone");
        verify(ec2, timeout(5_000)).releaseVpcPrivateIp(ACCOUNT, REGION, "subnet-1", "10.0.1.20");
    }

    // Catches: DeleteSubnet and DeleteVpc waiting on a daemon that does not answer, because the
    // dependency check inspected each unconfirmed container on the request thread.
    @Test
    @Timeout(30)
    void aDaemonThatDoesNotAnswerLeavesTheDependencyCheckAnsweringFromMemory() throws Exception {
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
            assertTrue(tookMillis < 1_000, "the checks should not wait on Docker, took " + tookMillis + " ms");
            awaitTrue(() -> inspects.get() == 2, "one background look should be running");
            Thread.sleep(200);
            assertEquals(2, inspects.get(), "asking again while a look runs starts no second one");
        } finally {
            daemonAnswers.countDown();
        }

        awaitTrue(() -> !leases.holdsSubnet(ACCOUNT, REGION, "subnet-1"),
                "the address should be given back once Docker says the container is gone");
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(20);
        }
    }
}
