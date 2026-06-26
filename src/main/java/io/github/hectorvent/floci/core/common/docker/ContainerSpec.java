package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;

import java.util.List;
import java.util.Map;

/**
 * Immutable specification for a Docker container to be created.
 * Use {@link ContainerBuilder} to construct instances of this record.
 *
 * @param image Docker image name (required)
 * @param name Container name (optional, Docker generates one if null)
 * @param env Environment variables as "KEY=value" strings
 * @param cmd Command to run (overrides image CMD)
 * @param entrypoint Entrypoint to use (overrides image ENTRYPOINT)
 * @param memoryBytes Memory limit in bytes (null = no limit)
 * @param portBindings Map of container port to host port (0 = dynamic allocation)
 * @param exposedPorts Ports to expose (required for port bindings)
 * @param networkMode Docker network name or mode (null = default bridge)
 * @param mounts Volume mounts (named volumes, bind mounts, tmpfs)
 * @param binds Legacy bind mounts (prefer mounts for new code)
 * @param extraHosts Extra /etc/hosts entries as "hostname:ip" strings
 * @param logConfig Docker log driver configuration (null = daemon default)
 * @param privileged Whether to run the container in privileged mode (required for k3s)
 * @param cgroupnsMode Docker cgroup namespace mode (for example, "host")
 * @param dnsServers DNS server IPs to inject into the container (e.g. Floci's embedded DNS)
 * @param workingDir Working directory inside the container (overrides image WORKDIR)
 */
public record ContainerSpec(
        String image,
        String name,
        List<String> env,
        List<String> cmd,
        List<String> entrypoint,
        Long memoryBytes,
        Map<Integer, Integer> portBindings,
        List<Integer> exposedPorts,
        String networkMode,
        List<Mount> mounts,
        List<Bind> binds,
        List<String> extraHosts,
        LogConfig logConfig,
        boolean privileged,
        String cgroupnsMode,
        List<String> dnsServers,
        String workingDir
) {
    /**
     * Creates a minimal spec with just the image name.
     * All other fields will be null or empty lists.
     */
    public ContainerSpec(String image) {
        this(image, null, List.of(), null, null, null, Map.of(), List.of(), null, List.of(), List.of(), List.of(), null, false, null, List.of(), null);
    }

    /**
     * Returns true if this spec has any port bindings configured.
     */
    public boolean hasPortBindings() {
        return portBindings != null && !portBindings.isEmpty();
    }

    /**
     * Returns true if this spec has a memory limit configured.
     */
    public boolean hasMemoryLimit() {
        return memoryBytes != null && memoryBytes > 0;
    }

    /**
     * Returns true if log rotation is configured.
     */
    public boolean hasLogConfig() {
        return logConfig != null;
    }
}
