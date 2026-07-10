package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Volume;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fluent builder for constructing {@link ContainerSpec} instances.
 * Provides sensible defaults and integrates with Floci configuration.
 *
 * <p>Example usage:
 * <pre>{@code
 * ContainerSpec spec = containerBuilder.newContainer("nginx:latest")
 *     .withName("floci-my-service")
 *     .withEnv("MY_VAR", "value")
 *     .withDynamicPort(8080)
 *     .withDockerNetwork(config.services().myService().dockerNetwork())
 *     .withLogRotation()
 *     .build();
 * }</pre>
 */
@ApplicationScoped
public class ContainerBuilder {

    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;

    @Inject
    public ContainerBuilder(EmulatorConfig config, DockerHostResolver dockerHostResolver,
                            EmbeddedDnsServer embeddedDnsServer,
                            CurrentContainerNetworkResolver currentContainerNetworkResolver) {
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
    }

    public ContainerBuilder(EmulatorConfig config, DockerHostResolver dockerHostResolver,
                            EmbeddedDnsServer embeddedDnsServer) {
        this(config, dockerHostResolver, embeddedDnsServer, null);
    }

    /**
     * Creates a new builder for a container with the specified image.
     *
     * @param image Docker image name (e.g., "nginx:latest")
     * @return a new Builder instance
     */
    public Builder newContainer(String image) {
        return new Builder(resolveImage(image), config, dockerHostResolver, embeddedDnsServer, currentContainerNetworkResolver);
    }

    private String resolveImage(String image) {
        return resolveImage(image, configuredImageRegistryBase(config));
    }

    static String resolveImage(String image, Optional<String> imageRegistryBase) {
        if (image == null || image.isBlank()) {
            return image;
        }
        return normalizeImageRegistryBase(imageRegistryBase)
                .filter(base -> !image.startsWith(base + "/"))
                .map(base -> base + "/" + image)
                .orElse(image);
    }

    private static Optional<String> configuredImageRegistryBase(EmulatorConfig config) {
        if (config == null || config.docker() == null) {
            return Optional.empty();
        }
        return config.docker().imageRegistryBase();
    }

    private static Optional<String> normalizeImageRegistryBase(Optional<String> imageRegistryBase) {
        return imageRegistryBase
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    while (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    return value;
                });
    }

    /**
     * Fluent builder for constructing ContainerSpec instances.
     */
    public static class Builder {
        private final String image;
        private final EmulatorConfig config;
        private final DockerHostResolver dockerHostResolver;
        private final EmbeddedDnsServer embeddedDnsServer;
        private final CurrentContainerNetworkResolver currentContainerNetworkResolver;

        private String name;
        private final List<String> env = new ArrayList<>();
        private List<String> cmd;
        private List<String> entrypoint;
        private String workingDir;
        private Long memoryBytes;
        private final Map<Integer, Integer> portBindings = new HashMap<>();
        private final List<Integer> exposedPorts = new ArrayList<>();
        private String networkMode;
        private final List<Mount> mounts = new ArrayList<>();
        private final List<Bind> binds = new ArrayList<>();
        private final List<String> extraHosts = new ArrayList<>();
        private LogConfig logConfig;
        private boolean privileged;
        private String cgroupnsMode;
        private String user;
        private final List<String> groupAdd = new ArrayList<>();
        private final List<String> dnsServers = new ArrayList<>();

        Builder(String image, EmulatorConfig config, DockerHostResolver dockerHostResolver,
                EmbeddedDnsServer embeddedDnsServer,
                CurrentContainerNetworkResolver currentContainerNetworkResolver) {
            this.image = image;
            this.config = config;
            this.dockerHostResolver = dockerHostResolver;
            this.embeddedDnsServer = embeddedDnsServer;
            this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        }

        /**
         * Sets the container name.
         */
        public Builder withName(String name) {
            this.name = name;
            return this;
        }

        /**
         * Adds a single environment variable.
         */
        public Builder withEnv(String key, String value) {
            this.env.add(key + "=" + value);
            return this;
        }

        /**
         * Adds multiple environment variables from a list of "KEY=value" strings.
         */
        public Builder withEnv(List<String> env) {
            this.env.addAll(env);
            return this;
        }

        /**
         * Sets the container command (overrides image CMD).
         */
        public Builder withCmd(List<String> cmd) {
            this.cmd = cmd;
            return this;
        }

        /**
         * Sets the container command from a single string (for simple commands).
         */
        public Builder withCmd(String cmd) {
            this.cmd = List.of(cmd);
            return this;
        }

        /**
         * Sets the container entrypoint (overrides image ENTRYPOINT).
         */
        public Builder withEntrypoint(List<String> entrypoint) {
            this.entrypoint = entrypoint;
            return this;
        }

        /**
         * Sets the working directory inside the container (overrides image WORKDIR).
         */
        public Builder withWorkingDir(String workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        /**
         * Sets the memory limit in megabytes.
         */
        public Builder withMemoryMb(int memoryMb) {
            this.memoryBytes = (long) memoryMb * 1024 * 1024;
            return this;
        }

        /**
         * Sets the memory limit in bytes.
         */
        public Builder withMemoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
            return this;
        }

        /**
         * Adds a port binding from container port to a specific host port.
         */
        public Builder withPortBinding(int containerPort, int hostPort) {
            this.portBindings.put(containerPort, hostPort);
            this.exposedPorts.add(containerPort);
            return this;
        }

        /**
         * Adds a port binding with dynamic host port allocation.
         * Use this when you don't care which host port is used.
         */
        public Builder withDynamicPort(int containerPort) {
            return withPortBinding(containerPort, 0);
        }

        /**
         * Exposes a port without creating a host binding.
         * Useful when containers communicate via Docker network.
         */
        public Builder withExposedPort(int port) {
            this.exposedPorts.add(port);
            return this;
        }

        /**
         * Sets the Docker network mode directly.
         */
        public Builder withNetworkMode(String networkMode) {
            this.networkMode = networkMode;
            return this;
        }

        /**
         * Sets the Docker network from a service-specific Optional, falling back to
         * the global services.dockerNetwork() if not present.
         * This is the standard pattern for Floci container services.
         */
        public Builder withDockerNetwork(Optional<String> serviceNetwork) {
            Optional<String> configuredNetwork = serviceNetwork
                    .or(() -> config.services().dockerNetwork())
                    .filter(n -> !n.isBlank())
                    .or(() -> currentContainerNetworkResolver != null
                            ? currentContainerNetworkResolver.resolveNetworkName()
                            : Optional.empty());
            configuredNetwork.ifPresent(n -> this.networkMode = n);
            return this;
        }

        /**
         * Adds a bind mount from host path to container path.
         */
        public Builder withBind(String hostPath, String containerPath) {
            this.binds.add(new Bind(hostPath, new Volume(containerPath)));
            return this;
        }

        public Builder withReadOnlyBind(String hostPath, String containerPath) {
            this.binds.add(new Bind(hostPath, new Volume(containerPath), AccessMode.ro));
            return this;
        }

        /**
         * Adds a read-write named volume mount.
         */
        public Builder withNamedVolume(String volumeName, String containerPath) {
            return withNamedVolume(volumeName, containerPath, false);
        }

        /**
         * Adds a named volume mount, read-only when {@code readOnly} is true.
         */
        public Builder withNamedVolume(String volumeName, String containerPath, boolean readOnly) {
            this.mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(volumeName)
                    .withTarget(containerPath)
                    .withReadOnly(readOnly));
            return this;
        }

        /**
         * Adds a mount (any type: volume, bind, tmpfs).
         */
        public Builder withMount(Mount mount) {
            this.mounts.add(mount);
            return this;
        }

        /**
         * Adds the host.docker.internal extra host entry on Linux.
         * This allows containers to reach the host via a consistent hostname
         * across all platforms (already exists on Docker Desktop).
         */
        public Builder withHostDockerInternalOnLinux() {
            if (dockerHostResolver.isLinuxHost()) {
                this.extraHosts.add("host.docker.internal:host-gateway");
            }
            return this;
        }

        /**
         * Adds a custom extra host entry.
         */
        public Builder withExtraHost(String hostname, String ip) {
            this.extraHosts.add(hostname + ":" + ip);
            return this;
        }

        /**
         * Enables log rotation with default settings from configuration.
         * Uses json-file driver with max-size and max-file from config.
         */
        public Builder withLogRotation() {
            String maxSize = config.docker().logMaxSize();
            String maxFile = config.docker().logMaxFile();
            return withLogRotation(maxSize, maxFile);
        }

        /**
         * Enables log rotation with custom settings.
         *
         * @param maxSize maximum log file size (e.g., "10m", "100k", "1g")
         * @param maxFile maximum number of log files to retain
         */
        public Builder withLogRotation(String maxSize, String maxFile) {
            this.logConfig = new LogConfig(
                    LogConfig.LoggingType.JSON_FILE,
                    Map.of("max-size", maxSize, "max-file", maxFile));
            return this;
        }

        /**
         * Sets a custom log configuration.
         */
        public Builder withLogConfig(LogConfig logConfig) {
            this.logConfig = logConfig;
            return this;
        }

        /**
         * Runs the container in privileged mode (required for k3s and similar containers
         * that need full system access).
         */
        public Builder withPrivileged(boolean privileged) {
            this.privileged = privileged;
            return this;
        }

        public Builder withCgroupnsMode(String cgroupnsMode) {
            this.cgroupnsMode = cgroupnsMode;
            return this;
        }

        /**
         * Sets the user the container process runs as, formatted {@code "uid[:gid]"}
         * (Docker's top-level container user). Null or blank leaves the image {@code USER}
         * in effect.
         */
        public Builder withUser(String user) {
            this.user = user;
            return this;
        }

        /**
         * Adds a supplementary group id to the container process (Docker {@code --group-add}),
         * so it can access group-owned resources without changing its primary uid/gid. Blank
         * ids are ignored.
         */
        public Builder withGroupAdd(String groupId) {
            if (groupId != null && !groupId.isBlank()) {
                this.groupAdd.add(groupId);
            }
            return this;
        }

        /**
         * Injects Floci's embedded DNS server into the container so virtual-hosted
         * S3 hostnames (my-bucket.localhost.floci.io) resolve to Floci's Docker
         * network IP. No-op when the embedded DNS server is not running.
         *
         * <p>When {@code floci.dns.container-fallback-enabled} is set, the configured public
         * fallback resolvers are appended after Floci's IP so the container's own resolver can
         * fall through for public hostnames if Floci's embedded forwarder cannot answer.
         */
        public Builder withEmbeddedDns() {
            embeddedDnsServer.getServerIp().ifPresent(flociIp -> {
                dnsServers.add(flociIp);
                if (config.dns().containerFallbackEnabled()) {
                    for (String fallback : config.dns().containerFallbackServers()) {
                        if (fallback != null && !fallback.isBlank() && !dnsServers.contains(fallback.trim())) {
                            dnsServers.add(fallback.trim());
                        }
                    }
                }
            });
            return this;
        }

        /**
         * Builds the immutable ContainerSpec.
         */
        public ContainerSpec build() {
            return new ContainerSpec(
                    image,
                    name,
                    List.copyOf(env),
                    cmd != null ? List.copyOf(cmd) : null,
                    entrypoint != null ? List.copyOf(entrypoint) : null,
                    memoryBytes,
                    Map.copyOf(portBindings),
                    List.copyOf(exposedPorts),
                    networkMode,
                    List.copyOf(mounts),
                    List.copyOf(binds),
                    List.copyOf(extraHosts),
                    logConfig,
                    privileged,
                    cgroupnsMode,
                    List.copyOf(dnsServers),
                    workingDir,
                    user,
                    List.copyOf(groupAdd)
            );
        }
    }
}
