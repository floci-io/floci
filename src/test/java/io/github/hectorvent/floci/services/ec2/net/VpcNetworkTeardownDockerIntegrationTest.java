package io.github.hectorvent.floci.services.ec2.net;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class VpcNetworkTeardownDockerIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    DockerClient docker;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for VPC network teardown tests");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingDockerHostedVpcFreesItsCidrWithoutStoppingFloci(boolean restored) {
        String namespace = "vpc-delete-" + UUID.randomUUID();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4650);
        when(config.services().ec2().mock()).thenReturn(false);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().driver()).thenReturn("bridge");
        when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
        when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(24);
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.docker().resourceNamespace()).thenReturn(Optional.of(namespace));

        List<Cidr4> taken = docker.listNetworksCmd().exec().stream()
                .filter(network -> network.getIpam() != null && network.getIpam().getConfig() != null)
                .flatMap(network -> network.getIpam().getConfig().stream())
                .flatMap(ipam -> Cidr4.parse(ipam.getSubnet()).stream())
                .toList();
        Cidr4 cidr = IntStream.range(128, 240)
                .mapToObj(block -> Cidr4.parse("10.200." + block + ".0/24").orElseThrow())
                .filter(candidate -> taken.stream().noneMatch(candidate::overlaps))
                .findFirst().orElseThrow(() -> new AssertionError("No free test CIDR in 10.200.128.0/17"));
        String expectedAddress = cidr.addressAt(10).orElseThrow();
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);
        VpcNetworkManager manager = new VpcNetworkManager(config, docker, resolver);
        List<String> containers = new ArrayList<>();
        List<String> networks = new ArrayList<>();
        try {
            String self = startContainer(namespace + "-floci");
            containers.add(self);
            when(resolver.resolveContainerId()).thenReturn(Optional.of(self.substring(0, 12)));
            for (int round = 0; round < 2; round++) {
                String vpcId = "vpc-" + round;
                String subnetId = "subnet-" + round;
                manager.declareVpc(REGION, vpcId, cidr.toString());
                manager.declareSubnet(REGION, vpcId, subnetId, cidr.toString());
                String address = manager.allocatePrivateIp(REGION, subnetId).orElseThrow();
                assertEquals(expectedAddress, address);
                assertFalse(manager.isSubstituted(REGION, vpcId));

                String guest = startContainer(namespace + "-guest-" + round);
                containers.add(guest);
                String networkName = manager.networkName(REGION, vpcId);
                networks.add(networkName);
                assertTrue(manager.attach(REGION, vpcId, subnetId, guest, address).isPresent());
                Network network = docker.inspectNetworkCmd().withNetworkId(networkName).exec();
                assertTrue(network.getContainers().containsKey(self));
                assertEquals(address + "/24", network.getContainers().get(guest).getIpv4Address());

                if (restored) {
                    manager.shutdown();
                    manager = new VpcNetworkManager(config, docker, resolver);
                    manager.declareVpc(REGION, vpcId, cidr.toString());
                    manager.declareSubnet(REGION, vpcId, subnetId, cidr.toString());
                    assertTrue(manager.attachContainer(REGION, vpcId, guest));
                }

                docker.removeContainerCmd(guest).withForce(true).exec();
                containers.remove(guest);
                manager.forgetSubnet(REGION, subnetId);
                manager.deleteVpcNetwork(REGION, vpcId);

                await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(12))
                        .untilAsserted(() -> assertThrows(NotFoundException.class,
                                () -> docker.inspectNetworkCmd().withNetworkId(networkName).exec()));
                assertTrue(docker.inspectContainerCmd(self).exec().getState().getRunning());
                assertFalse(docker.inspectContainerCmd(self).exec().getNetworkSettings()
                        .getNetworks().containsKey(networkName));
            }
        } finally {
            manager.shutdown();
            for (String containerId : containers) {
                docker.removeContainerCmd(containerId).withForce(true).exec();
            }
            for (String networkName : networks) {
                try {
                    Network network = docker.inspectNetworkCmd().withNetworkId(networkName).exec();
                    assertEquals(ContainerStorageHelper.ownerIdentity(config),
                            network.getLabels().get(ContainerStorageHelper.OWNER_LABEL));
                    docker.removeNetworkCmd(network.getId()).exec();
                } catch (NotFoundException expected) {
                    // A successful teardown already removed this test-owned network.
                }
            }
        }
    }

    private String startContainer(String name) {
        return lifecycleManager.createAndStart(containerBuilder.newContainer(TestImages.BUSYBOX)
                .withName(name).withCmd(List.of("sleep", "120"))
                .build()).containerId();
    }
}
