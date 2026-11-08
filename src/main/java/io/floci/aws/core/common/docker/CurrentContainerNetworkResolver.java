package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Resolves the Docker network used by the Floci container itself.
 *
 * <p>When Floci runs in Docker and launches sibling containers through the
 * mounted Docker socket, those siblings must join the same Docker network to
 * reach Floci's in-container Runtime API and service endpoints.
 */
@ApplicationScoped
public class CurrentContainerNetworkResolver {

    private static final Logger LOG = Logger.getLogger(CurrentContainerNetworkResolver.class);

    private static final String HOSTNAME_FILE = "/etc/hostname";

    private final DockerClient dockerClient;
    private final ContainerDetector containerDetector;
    private final Map<Integer, OptionalInt> cachedPublishedPorts = new ConcurrentHashMap<>();

    private volatile Optional<CurrentContainerNetwork> cachedNetwork;

    @Inject
    public CurrentContainerNetworkResolver(DockerClient dockerClient, ContainerDetector containerDetector) {
        this.dockerClient = dockerClient;
        this.containerDetector = containerDetector;
    }

    public Optional<String> resolveNetworkName() {
        return resolve().map(CurrentContainerNetwork::name);
    }

    public Optional<String> resolveContainerId() {
        if (!containerDetector.isRunningInContainer()) {
            return Optional.empty();
        }
        String containerId = currentContainerId();
        return containerId.isBlank() ? Optional.empty() : Optional.of(containerId);
    }

    public Optional<String> resolveContainerIp() {
        return resolve().map(CurrentContainerNetwork::ipAddress);
    }

    public OptionalInt resolvePublishedPort(int containerPort) {
        OptionalInt cachedPublishedPort = cachedPublishedPorts.get(containerPort);
        if (cachedPublishedPort != null) {
            return cachedPublishedPort;
        }

        OptionalInt resolvedPublishedPort = detectPublishedPort(containerPort);
        OptionalInt publishedPort = cachedPublishedPorts.putIfAbsent(containerPort, resolvedPublishedPort);
        return publishedPort == null ? resolvedPublishedPort : publishedPort;
    }

    Optional<CurrentContainerNetwork> resolve() {
        Optional<CurrentContainerNetwork> cached = cachedNetwork;
        if (cached != null) {
            return cached;
        }
        cachedNetwork = detect();
        return cachedNetwork;
    }

    /**
     * The host port Floci's own container publishes for {@code containerPort}, empty when it is
     * not published, Floci is not in a container, or the container cannot be inspected. Every
     * result is cached, a failure included, as {@link #resolve()} does: a running container's
     * port bindings never change, and an inspect that fails (no Docker access, no container id)
     * fails the same way on the next call.
     */
    private OptionalInt detectPublishedPort(int containerPort) {
        if (!containerDetector.isRunningInContainer()) {
            return OptionalInt.empty();
        }

        String containerId = currentContainerId();
        if (containerId.isBlank()) {
            LOG.debug("Could not determine current Docker container id");
            return OptionalInt.empty();
        }

        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            Ports ports = inspect.getNetworkSettings().getPorts();
            if (ports == null || ports.getBindings() == null) {
                return OptionalInt.empty();
            }

            Ports.Binding[] bindings = ports.getBindings().get(ExposedPort.tcp(containerPort));
            if (bindings == null || bindings.length == 0) {
                return OptionalInt.empty();
            }

            for (Ports.Binding binding : bindings) {
                if (binding == null) {
                    continue;
                }
                String hostPort = binding.getHostPortSpec();
                if (hostPort != null && !hostPort.isBlank()) {
                    return OptionalInt.of(Integer.parseInt(hostPort));
                }
            }
            return OptionalInt.empty();
        } catch (Exception e) {
            LOG.debugv("Could not resolve published port {0} for current Docker container {1}: {2}",
                    String.valueOf(containerPort), containerId, e.getMessage());
            return OptionalInt.empty();
        }
    }

    private Optional<CurrentContainerNetwork> detect() {
        if (!containerDetector.isRunningInContainer()) {
            return Optional.empty();
        }

        String containerId = currentContainerId();
        if (containerId.isBlank()) {
            LOG.debug("Could not determine current Docker container id");
            return Optional.empty();
        }

        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
            if (networks == null || networks.isEmpty()) {
                return Optional.empty();
            }

            Optional<CurrentContainerNetwork> selected = selectNetwork(networks);
            selected.ifPresent(network -> LOG.infov(
                    "Detected current Docker network for spawned containers: {0} ({1})",
                    network.name(), network.ipAddress()));
            return selected;
        } catch (Exception e) {
            LOG.debugv("Could not inspect current Docker container {0}: {1}", containerId, e.getMessage());
            return Optional.empty();
        }
    }

    // Docker lists a container's networks by name. One Floci made and joined, an EC2 VPC's or an EMR cluster's, is not
    // where its containers belong: a Floci that crashed is still on it when it starts again, and what it launches would
    // join that network, reaching what only its owner should. It is taken only when Floci is on no other.
    private Optional<CurrentContainerNetwork> selectNetwork(Map<String, ContainerNetwork> networks) {
        return selectNetwork(networks, networkName -> !isMadeByFloci(networkName))
                .or(() -> selectNetwork(networks, networkName -> true));
    }

    // The first usable network eligible says, a user-defined one before the others.
    private Optional<CurrentContainerNetwork> selectNetwork(Map<String, ContainerNetwork> networks,
                                                            Predicate<String> eligible) {
        return networks.entrySet().stream()
                .filter(entry -> isUsable(entry.getValue()))
                .filter(entry -> isUserDefinedNetwork(entry.getKey()))
                .filter(entry -> eligible.test(entry.getKey()))
                .findFirst()
                .or(() -> networks.entrySet().stream()
                        .filter(entry -> isUsable(entry.getValue()))
                        .filter(entry -> eligible.test(entry.getKey()))
                        .findFirst())
                .map(entry -> new CurrentContainerNetwork(entry.getKey(), entry.getValue().getIpAddress()));
    }

    private boolean isUsable(ContainerNetwork network) {
        return network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank();
    }

    private boolean isUserDefinedNetwork(String networkName) {
        return !"bridge".equals(networkName) && !"host".equals(networkName) && !"none".equals(networkName);
    }

    // Whether Floci made the network, as its owner label says, or the label an EC2 VPC's had before it. One Docker
    // cannot inspect is taken as Floci's, so it is not mistaken for the user's own.
    private boolean isMadeByFloci(String networkName) {
        try {
            Map<String, String> labels = dockerClient.inspectNetworkCmd().withNetworkId(networkName).exec().getLabels();
            return labels != null && (labels.containsKey(ContainerStorageHelper.OWNER_LABEL)
                    || ContainerStorageHelper.NETWORK_LABEL_ALIASES.legacyValue(labels,
                            ContainerStorageHelper.OWNER_LABEL) != null);
        } catch (Exception e) {
            LOG.warnv(e, "Could not inspect Docker network {0}, so it is not taken for Floci's own: {1}", networkName,
                    e.getMessage());
            return true;
        }
    }

    String currentContainerId() {
        try {
            return Files.readString(Path.of(HOSTNAME_FILE)).trim();
        } catch (Exception e) {
            LOG.debugv("Could not read {0}: {1}", HOSTNAME_FILE, e.getMessage());
            return "";
        }
    }

    record CurrentContainerNetwork(String name, String ipAddress) {
    }
}
