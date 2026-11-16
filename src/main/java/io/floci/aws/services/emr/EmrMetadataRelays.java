package io.floci.aws.services.emr;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ec2.model.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Instance metadata for a cluster's containers in the {@code docker} runner mode, at 169.254.169.254 as on the EC2
 * instances an EMR cluster runs on. A relay container on the cluster's own Docker network holds that address as a
 * link-local one, as ECS's credentials proxy holds its own, and forwards each request to Floci's EC2 metadata server,
 * naming its caller in {@code X-Forwarded-For} by the link-local address each of the cluster's containers is given
 * there, so the server answers it as the instance that container is: the cluster's primary node for its bootstrap
 * actions and its steps, a core or task node for that node's container. Only the cluster's own containers are on that
 * network, and the server reads the header from the relays it trusts alone.
 */
@ApplicationScoped
public class EmrMetadataRelays implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrMetadataRelays.class);
    /** The address of the instance metadata service, which a relay holds on its cluster's network. */
    static final String ADDRESS = "169.254.169.254";
    private static final String SERVICE = "emr";
    /** The component label of a cluster's relay container. */
    static final String COMPONENT = "emr-metadata";
    // How long a relay may take to listen once its container has started, a look every 100 ms.
    private static final int READY_LOOKS = 100;
    // The relay: an HTTP server on 169.254.169.254:80 that sends each request on to the metadata server ($1:$2) with
    // the caller's address in X-Forwarded-For, in place of any the caller sent, and gives its answer back, an answer to
    // HEAD with the length the server gave, as it has no body to count.
    static final String RELAY_SCRIPT = String.join("\n",
            "import http.client, http.server, socketserver, sys",
            "TARGET = (sys.argv[1], int(sys.argv[2]))",
            "DROPPED = ('host', 'x-forwarded-for', 'connection', 'content-length', 'transfer-encoding')",
            "class Relay(http.server.BaseHTTPRequestHandler):",
            "    protocol_version = 'HTTP/1.1'",
            "    def relay(self):",
            "        length = int(self.headers.get('Content-Length') or 0)",
            "        body = self.rfile.read(length) if length else None",
            "        headers = {k: v for k, v in self.headers.items() if k.lower() not in DROPPED}",
            "        headers['X-Forwarded-For'] = self.client_address[0]",
            "        connection = http.client.HTTPConnection(*TARGET, timeout=30)",
            "        try:",
            "            connection.request(self.command, self.path, body=body, headers=headers)",
            "            answer = connection.getresponse()",
            "            data = answer.read()",
            "        except OSError:",
            "            self.send_error(502)",
            "            return",
            "        finally:",
            "            connection.close()",
            "        self.send_response(answer.status)",
            "        for k, v in answer.getheaders():",
            "            if k.lower() not in DROPPED:",
            "                self.send_header(k, v)",
            "        length = answer.getheader('Content-Length') if self.command == 'HEAD' else None",
            "        self.send_header('Content-Length', length if length is not None else str(len(data)))",
            "        self.end_headers()",
            "        if self.command != 'HEAD':",
            "            self.wfile.write(data)",
            "    do_GET = do_PUT = do_HEAD = do_POST = relay",
            "    def log_message(self, *args):",
            "        pass",
            "class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):",
            "    daemon_threads = True",
            "    allow_reuse_address = True",
            "Server(('" + ADDRESS + "', 80), Relay).serve_forever()");
    // Ready once a connection to 169.254.169.254:80 is taken.
    private static final String READY_PROBE = "import socket; socket.create_connection(('" + ADDRESS + "', 80), 1)";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final Ec2MetadataServer metadataServer;
    private final EmulatorConfig config;
    // Each cluster's relay, by cluster id.
    private final Map<String, Relay> relays = new HashMap<>();
    // Each link-local address given, by address: whose it is.
    private final Map<String, Given> given = new HashMap<>();
    // The clusters whose relay Docker kept, trusted no more, which the next stop removes.
    private final Set<String> left = new LinkedHashSet<>();

    public EmrMetadataRelays(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                             Ec2MetadataServer metadataServer, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.metadataServer = metadataServer;
        this.config = config;
    }

    /**
     * Starts the relay of cluster {@code clusterId} on {@code network}, its own, which exists, in {@code image}, which
     * has Python 3, unless it runs, sending requests to Floci's metadata server at {@code targetHost} as the network
     * reaches Floci; the server trusts it from then on.
     */
    public synchronized void start(String clusterId, String accountId, String region, String network, String image,
                                   String targetHost) {
        Relay known = relays.get(clusterId);
        if (known != null && lifecycleManager.isContainerRunning(known.containerId())) {
            return;
        }
        if (known != null) {
            // One that has stopped is trusted no more before it goes, so whatever Docker gives its address is not.
            relays.remove(clusterId);
            metadataServer.distrustForwarder(known.address());
        }
        if (!removeRelay(clusterId)) {
            throw new IllegalStateException("Docker kept the instance metadata relay of EMR cluster " + clusterId);
        }
        String name = ContainerStorageHelper.dockerName(config, name(clusterId));
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(SERVICE,
                clusterId, accountId, region));
        labels.put(ContainerStorageHelper.COMPONENT_LABEL, COMPONENT);
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        String containerId = lifecycleManager.createAndStart(containerBuilder.newContainer(image)
                .withName(name)
                .withUser("root")
                .withEntrypoint(List.of("python3", "-c", RELAY_SCRIPT, targetHost,
                        String.valueOf(config.services().ec2().imdsPort())))
                .withDockerNetwork(Optional.of(network))
                .withLinkLocalIp(ADDRESS)
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .withLabels(labels)
                .build()).containerId();
        try {
            awaitListening(containerId);
            String address = lifecycleManager.resolveContainerNetworkIp(containerId, network);
            metadataServer.trustForwarder(address);
            relays.put(clusterId, new Relay(containerId, address));
            LOG.infov("Started the instance metadata relay of EMR cluster {0} at {1} on {2}", clusterId, ADDRESS,
                    network);
        } catch (RuntimeException e) {
            removeRelay(clusterId);
            throw e;
        }
    }

    /** Whether a relay this Floci started serves cluster {@code clusterId}'s network. */
    public synchronized boolean serves(String clusterId) {
        return relays.containsKey(clusterId);
    }

    /**
     * A link-local address on cluster {@code clusterId}'s network for a container that is {@code instance}, which the
     * metadata server answers for as that instance until {@link #release}.
     */
    public synchronized String give(String clusterId, Instance instance) {
        String address = free();
        given.put(address, new Given(clusterId, instance));
        metadataServer.reconcileClusterNodeAddresses(Set.copyOf(addressesOf(instance)), instance);
        return address;
    }

    /** The container given {@code address} has gone: the server answers for it no more. */
    public synchronized void release(String address) {
        Given gone = given.remove(address);
        if (gone == null) {
            return;
        }
        List<String> left = addressesOf(gone.instance());
        if (left.isEmpty()) {
            metadataServer.unregisterInstance(gone.instance());
        } else {
            metadataServer.reconcileClusterNodeAddresses(Set.copyOf(left), gone.instance());
        }
    }

    /**
     * Cluster {@code clusterId} has ended: its relay, trusted no more, goes, and every address given on its network.
     * Whether the relay has gone: one Docker keeps is removed by the next stop.
     */
    public synchronized boolean stop(String clusterId) {
        Relay relay = relays.remove(clusterId);
        if (relay != null) {
            metadataServer.distrustForwarder(relay.address());
        }
        List<String> addresses = given.entrySet().stream()
                .filter(entry -> entry.getValue().clusterId().equals(clusterId)).map(Map.Entry::getKey).toList();
        addresses.forEach(this::release);
        return removeRelay(clusterId);
    }

    /** Removes every relay this Floci started, so a reset or a shutdown orphans none. */
    @Override
    public synchronized void stopManagedContainers() {
        Set<String> clusters = new LinkedHashSet<>(relays.keySet());
        clusters.addAll(left);
        clusters.forEach(this::stop);
        for (String address : List.copyOf(given.keySet())) {
            release(address);
        }
    }

    // Removes cluster clusterId's relay container, by its name; one Docker keeps is left for the next stop. Whether it
    // has gone.
    private boolean removeRelay(String clusterId) {
        try {
            lifecycleManager.removeIfExistsStrict(ContainerStorageHelper.dockerName(config, name(clusterId)));
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the instance metadata relay of EMR cluster {0}: {1}", clusterId,
                    e.getMessage());
            left.add(clusterId);
            return false;
        }
        left.remove(clusterId);
        return true;
    }

    private void awaitListening(String containerId) {
        for (int look = 0; look < READY_LOOKS; look++) {
            ContainerExec.Result probed = ContainerExec.run(lifecycleManager.getDockerClient(), containerId,
                    new String[] {"python3", "-c", READY_PROBE}, 5);
            if (probed.exitCode() == 0) {
                return;
            }
            if (!lifecycleManager.isContainerRunning(containerId)) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("The instance metadata relay " + containerId + " did not listen on " + ADDRESS);
    }

    // The first link-local address not given, skipping the relay's own; the whole /16 is the networks' own.
    private String free() {
        for (int third = 0; third < 256; third++) {
            for (int fourth = 1; fourth <= 254; fourth++) {
                String candidate = "169.254." + third + "." + fourth;
                if (!ADDRESS.equals(candidate) && !given.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No link-local address is left for an EMR cluster's container");
    }

    // The addresses given to containers that are instance.
    private List<String> addressesOf(Instance instance) {
        return given.entrySet().stream().filter(entry -> entry.getValue().instance() == instance)
                .map(Map.Entry::getKey).toList();
    }

    static String name(String clusterId) {
        return "emr-" + clusterId + "-metadata";
    }

    private record Relay(String containerId, String address) {}

    private record Given(String clusterId, Instance instance) {}
}
