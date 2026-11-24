package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.net.Cidr4;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestDockerClients;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cluster node started as Floci starts one on the VPC pod network, from the same entrypoint, environment and k3s
 * arguments, on a network standing in for Floci's and a second standing in for the VPC's: its pods take addresses from
 * both of its prefixes, and those answer another container on the VPC network and, on Linux, this JVM, the way Floci's
 * health checks and load balancers dial a {@code TargetType ip} target. It pulls k3s and busybox and runs fourteen pods,
 * so it runs with the nightly checks ({@code -Dnightly.checks.enabled=true}) rather than in the pull request shards.
 */
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EksVpcPodNetworkNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksVpcPodNetworkNightlyIntegrationTest.class);
    private static final String LABEL = "floci.test";
    private static final String LABEL_VALUE = "eks-vpc-pod-network";
    /** Longer than a run takes, so leftovers this old belong to no run still going. */
    private static final Duration LEFTOVER_AGE = Duration.ofHours(1);
    private static final String K3S_IMAGE = EksClusterManager.SUPPORTED_K8S_VERSIONS.get("1.31");
    private static final String PEER_IMAGE = "alpine:3.21";
    private static final int REPLICAS = 14;
    /** Ranges for the two networks, tried in turn, since another network on the daemon may hold one. */
    private static final List<Integer> SECOND_OCTETS = List.of(247, 246, 245, 244, 243, 242, 241, 240);

    /** Names this run's containers and networks, so runs sharing a daemon keep to their own. */
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private DockerClient docker;
    private final List<String> containers = new ArrayList<>();
    private final List<String> networks = new ArrayList<>();

    @BeforeEach
    void connect() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon is unavailable");
        docker = TestDockerClients.local();
        removeLeftovers();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (docker == null) {
            return;
        }
        try {
            containers.forEach(this::removeContainerQuietly);
            networks.forEach(this::removeNetworkQuietly);
        } finally {
            docker.close();
        }
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void podsTakeAddressesFromBothPrefixesAndAnswerOnTheVpcNetwork() throws Exception {
        pull(K3S_IMAGE);
        pull(PEER_IMAGE);
        createNetwork("floci");
        Cidr4 vpc = createNetwork("vpc");
        String nodeIp = Cidr4.format(vpc.network() + 266);
        List<String> prefixes = List.of(Cidr4.format(vpc.network() + 480) + "/28",
                Cidr4.format(vpc.network() + 464) + "/28");
        EksClusterManager.VpcPodNetwork podNetwork = new EksClusterManager.VpcPodNetwork(vpc.toString(), prefixes);

        String node = startNode(podNetwork, nodeIp, networks.get(0), networks.get(1));
        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(3))
                .until(() -> kubectl(node, "get", "nodes", "--no-headers").contains(" Ready "));
        kubectl(node, "create", "deployment", "web", "--image=busybox:1.36", "--replicas=" + REPLICAS, "--",
                "sh", "-c", "hostname > /tmp/index.html; exec httpd -f -p 8080 -h /tmp");
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(3)).until(() ->
                kubectl(node, "get", "deployment", "web", "-o", "jsonpath={.status.readyReplicas}").trim()
                        .equals(String.valueOf(REPLICAS)));

        List<String> podIps = Arrays.stream(kubectl(node, "get", "pods", "-l", "app=web", "-o",
                "jsonpath={range .items[*]}{.status.podIP}{\"\\n\"}{end}").trim().split("\n")).toList();
        assertEquals(REPLICAS, podIps.size(), podIps.toString());
        for (String prefix : prefixes) {
            assertTrue(podIps.stream().anyMatch(ip -> contains(prefix, ip)),
                    "no pod took an address from " + prefix + ": " + podIps);
        }
        assertTrue(podIps.stream().allMatch(ip -> prefixes.stream().anyMatch(prefix -> contains(prefix, ip))),
                "a pod took an address outside the prefixes: " + podIps);

        String peer = startPeer(networks.get(1), Cidr4.format(vpc.network() + 532));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        for (String ip : podIps) {
            ContainerExec.Result fromPeer = ContainerExec.run(docker, peer,
                    new String[]{"wget", "-q", "-O-", "-T", "5", "http://" + ip + ":8080/"}, 20);
            assertEquals(0, fromPeer.exitCode(), ip + " from the VPC network: " + fromPeer.stderr());
            assertTrue(fromPeer.stdout().startsWith("web-"), ip + ": " + fromPeer.stdout());
            if (linux) {
                // The host holds an address on the VPC network's bridge, as Floci running natively does.
                HttpResponse<String> fromHost = http.send(HttpRequest.newBuilder(URI.create("http://" + ip + ":8080/"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, fromHost.statusCode(), ip + " from the host");
            }
        }
    }

    private void pull(String image) throws InterruptedException {
        docker.pullImageCmd(image).start().awaitCompletion(5, TimeUnit.MINUTES);
    }

    /**
     * Creates a labelled network on the first free range of {@link #SECOND_OCTETS}; returns that range. Docker refuses a
     * range another network overlaps with a 403; any other failure fails the test.
     */
    private Cidr4 createNetwork(String role) {
        for (int octet : SECOND_OCTETS) {
            Cidr4 range = Cidr4.parse("10." + octet + ".0.0/16").orElseThrow();
            String name = name(role);
            try {
                docker.createNetworkCmd().withName(name).withLabels(Map.of(LABEL, LABEL_VALUE))
                        .withIpam(new Network.Ipam().withConfig(new Network.Ipam.Config()
                                .withSubnet(range.toString()))).exec();
                networks.add(name);
                return range;
            } catch (DockerException refused) {
                if (refused.getHttpStatus() != 403 || refused.getMessage() == null
                        || !refused.getMessage().contains("overlaps")) {
                    throw refused;
                }
                LOG.debugv("Range {0} is not free for the {1} network: {2}", range, role, refused.getMessage());
            }
        }
        Assumptions.abort("No free 10.x.0.0/16 range for the " + role + " network");
        throw new IllegalStateException("unreachable");
    }

    /** Creates the node on Floci's network, attaches it to the VPC's at {@code nodeIp}, then starts it, as Floci does. */
    private String startNode(EksClusterManager.VpcPodNetwork podNetwork, String nodeIp, String flociNetwork,
                             String vpcNetwork) {
        List<String> serverArgs = EksClusterManager.buildServerArgs(false, false, null, podNetwork.vpcCidr(), nodeIp);
        serverArgs.addAll(EksClusterManager.VPC_POD_NETWORK_SERVER_ARGS);
        List<String> env = new ArrayList<>(List.of("K3S_KUBECONFIG_MODE=644"));
        podNetwork.env(nodeIp).forEach((key, value) -> env.add(key + "=" + value));
        String id = docker.createContainerCmd(K3S_IMAGE)
                .withName(name("node"))
                .withLabels(Map.of(LABEL, LABEL_VALUE))
                .withEntrypoint(EksClusterManager.VPC_POD_NETWORK_ENTRYPOINT)
                .withCmd(EksClusterManager.buildRshareWrappedCmd(serverArgs))
                .withEnv(env)
                .withHostConfig(HostConfig.newHostConfig().withPrivileged(true).withNetworkMode(flociNetwork)
                        .withMemory(1536L * 1024 * 1024))
                .exec().getId();
        containers.add(id);
        docker.connectToNetworkCmd().withContainerId(id).withNetworkId(vpcNetwork)
                .withContainerNetwork(new ContainerNetwork().withIpamConfig(
                        new ContainerNetwork.Ipam().withIpv4Address(nodeIp))).exec();
        docker.startContainerCmd(id).exec();
        return id;
    }

    private String startPeer(String vpcNetwork, String address) {
        String id = docker.createContainerCmd(PEER_IMAGE)
                .withName(name("peer"))
                .withLabels(Map.of(LABEL, LABEL_VALUE))
                .withCmd("sleep", "600")
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(vpcNetwork).withMemory(64L * 1024 * 1024))
                .withIpv4Address(address)
                .exec().getId();
        containers.add(id);
        docker.startContainerCmd(id).exec();
        return id;
    }

    private String name(String role) {
        return "floci-test-" + LABEL_VALUE + "-" + role + "-" + run;
    }

    private String kubectl(String node, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "kubectl";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ContainerExec.Result result = ContainerExec.run(docker, node, cmd, 30);
        return result.exitCode() == 0 ? result.stdout() : "";
    }

    private static boolean contains(String prefix, String ip) {
        return Cidr4.parse(prefix).orElseThrow().containsAddress(Cidr4.parse(ip + "/32").orElseThrow().network());
    }

    /**
     * Containers and networks an interrupted run left, found by label: only those older than {@link #LEFTOVER_AGE}, as a
     * run on the same daemon may still be using newer ones.
     */
    private void removeLeftovers() {
        Instant before = Instant.now().minus(LEFTOVER_AGE);
        docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(LABEL, LABEL_VALUE)).exec().stream()
                .filter(container -> container.getCreated() != null
                        && Instant.ofEpochSecond(container.getCreated()).isBefore(before))
                .forEach(container -> removeContainerQuietly(container.getId()));
        docker.listNetworksCmd().withFilter("label", List.of(LABEL + "=" + LABEL_VALUE)).exec().stream()
                .filter(network -> network.getCreated() != null && network.getCreated().toInstant().isBefore(before))
                .forEach(network -> removeNetworkQuietly(network.getId()));
    }

    private void removeContainerQuietly(String id) {
        try {
            docker.removeContainerCmd(id).withForce(true).exec();
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove test container {0}: {1}", id, e.getMessage());
        }
    }

    private void removeNetworkQuietly(String id) {
        try {
            docker.removeNetworkCmd(id).exec();
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove test network {0}: {1}", id, e.getMessage());
        }
    }
}
