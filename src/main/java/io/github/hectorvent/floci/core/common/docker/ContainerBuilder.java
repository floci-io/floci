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

    @Inject
    public ContainerBuilder(EmulatorConfig config, DockerHostResolver dockerHostResolver,
                            EmbeddedDnsServer embeddedDnsServer) {
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
    }

    /**
     * Creates a new builder for a container with the specified image.
     *
     * @param image Docker image name (e.g., "nginx:latest")
     * @return a new Builder instance
     */
    public Builder newContainer(String image) {
        return new Builder(image, config, dockerHostResolver, embeddedDnsServer);
    }

    /**
     * Fluent builder for constructing ContainerSpec instances.
     */
    public static class Builder {
        private final String image;
        private final EmulatorConfig config;
        private final DockerHostResolver dockerHostResolver;
        private final EmbeddedDnsServer embeddedDnsServer;

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
        private final List<String> dnsServers = new ArrayList<>();

        Builder(String image, EmulatorConfig config, DockerHostResolver dockerHostResolver,
                EmbeddedDnsServer embeddedDnsServer) {
            this.image = image;
            this.config = config;
            this.dockerHostResolver = dockerHostResolver;
            this.embeddedDnsServer = embeddedDnsServer;
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
            serviceNetwork
                    .or(() -> config.services().dockerNetwork())
                    .filter(n -> !n.isBlank())
                    .ifPresent(n -> this.networkMode = n);
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
         * Adds a named volume mount.
         */
        public Builder withNamedVolume(String volumeName, String containerPath) {
            this.mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(volumeName)
                    .withTarget(containerPath));
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

        /**
         * Injects Floci's embedded DNS server into the container so virtual-hosted
         * S3 hostnames (my-bucket.localhost.floci.io) resolve to Floci's Docker
         * network IP. No-op when the embedded DNS server is not running.
         */
        public Builder withEmbeddedDns() {
            embeddedDnsServer.getServerIp().ifPresent(dnsServers::add);
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
                    List.copyOf(dnsServers),
                    workingDir
            );
        }
    }
}
