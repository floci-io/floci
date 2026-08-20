package io.github.hectorvent.floci.core.common.docker;

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
    private final Map<Integer, Integer> cachedPublishedPorts = new ConcurrentHashMap<>();

    private volatile Optional<CurrentContainerNetwork> cachedNetwork;

    @Inject
    public CurrentContainerNetworkResolver(DockerClient dockerClient, ContainerDetector containerDetector) {
        this.dockerClient = dockerClient;
        this.containerDetector = containerDetector;
    }

    public Optional<String> resolveNetworkName() {
        return resolve().map(CurrentContainerNetwork::name);
    }

    public Optional<String> resolveContainerIp() {
        return resolve().map(CurrentContainerNetwork::ipAddress);
    }

    public OptionalInt resolvePublishedPort(int containerPort) {
        Integer cachedPublishedPort = cachedPublishedPorts.get(containerPort);
        if (cachedPublishedPort != null) {
            return OptionalInt.of(cachedPublishedPort);
        }

        OptionalInt resolvedPublishedPort = detectPublishedPort(containerPort);
        resolvedPublishedPort.ifPresent(port -> cachedPublishedPorts.putIfAbsent(containerPort, port));
        Integer publishedPort = cachedPublishedPorts.get(containerPort);
        return publishedPort == null ? OptionalInt.empty() : OptionalInt.of(publishedPort);
    }

    Optional<CurrentContainerNetwork> resolve() {
        Optional<CurrentContainerNetwork> cached = cachedNetwork;
        if (cached != null) {
            return cached;
        }
        cachedNetwork = detect();
        return cachedNetwork;
    }

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

    private Optional<CurrentContainerNetwork> selectNetwork(Map<String, ContainerNetwork> networks) {
        return networks.entrySet().stream()
                .filter(entry -> isUsable(entry.getValue()))
                .filter(entry -> isUserDefinedNetwork(entry.getKey()))
                .findFirst()
                .or(() -> networks.entrySet().stream()
                        .filter(entry -> isUsable(entry.getValue()))
                        .findFirst())
                .map(entry -> new CurrentContainerNetwork(entry.getKey(), entry.getValue().getIpAddress()));
    }

    private boolean isUsable(ContainerNetwork network) {
        return network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank();
    }

    private boolean isUserDefinedNetwork(String networkName) {
        return !"bridge".equals(networkName) && !"host".equals(networkName) && !"none".equals(networkName);
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
