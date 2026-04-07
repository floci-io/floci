package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects the hostname that Lambda containers should use to reach the Floci host.
 * Different on Linux native Docker vs Docker Desktop (macOS/Windows).
 */
@ApplicationScoped
public class DockerHostResolver {

    private static final Logger LOG = Logger.getLogger(DockerHostResolver.class);

    private static final String DOCKER_ENV_MARKER = "/.dockerenv";
    private static final String CGROUP_FILE = "/proc/1/cgroup";
    private static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    private static final String LINUX_DOCKER_BRIDGE = "172.17.0.1";

    private final EmulatorConfig config;
    private final AtomicBoolean ufwHintLogged = new AtomicBoolean(false);

    @Inject
    public DockerHostResolver(EmulatorConfig config) {
        this.config = config;
    }

    public String resolve() {
        java.util.Optional<String> override = config.services().lambda().dockerHostOverride();
        if (override.isPresent() && !override.get().isBlank()) {
            LOG.debugv("Using configured docker host override: {0}", override.get());
            return override.get();
        }

        boolean runningInDocker = Files.exists(Path.of(DOCKER_ENV_MARKER))
                || isRunningInContainer();

        if (runningInDocker) {
            // Use this container's own IP so Lambda containers on the same network
            // can reach the Runtime API server bound to all interfaces inside this container.
            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                LOG.infov("Running in Docker — using container IP for Runtime API: {0}", ip);
                return ip;
            } catch (Exception e) {
                LOG.warnv("Could not resolve local host address, falling back to bridge IP: {0}", e.getMessage());
                return LINUX_DOCKER_BRIDGE;
            }
        }

        // Floci is running natively on the host. Always return host.docker.internal:
        //   - On macOS/Windows (Docker Desktop), the alias is auto-injected into every
        //     container's /etc/hosts and routes through the Docker VM to the host.
        //   - On native Linux Docker, the alias is NOT auto-injected, so ContainerLauncher
        //     must add `host.docker.internal:host-gateway` to each Lambda container's
        //     extra-hosts at create time. ContainerLauncher does that on Linux only.
        // Either way, the in-container Lambda RIC can resolve "host.docker.internal" to
        // the host gateway and reach Floci's Runtime API server.
        LOG.debugv("Floci on host ({0}) — Lambda containers will use host.docker.internal",
                System.getProperty("os.name"));
        if (isLinuxHost() && ufwHintLogged.compareAndSet(false, true)) {
            LOG.info("Lambda containers will reach Floci via host.docker.internal "
                    + "(translated to the docker bridge gateway). On Linux hosts running UFW "
                    + "with the default 'INPUT DROP' policy, this path is blocked and Lambda "
                    + "invocations will time out with Function.TimedOut. If you see that, run: "
                    + "'sudo ufw allow in on docker0' (see README \u2192 'Lambda on native Linux Docker').");
        }
        return HOST_DOCKER_INTERNAL;
    }

    /** True when the Floci JVM is running natively on a Linux host (not on Docker Desktop). */
    public boolean isLinuxHost() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }

    private boolean isRunningInContainer() {
        Path cgroup = Path.of(CGROUP_FILE);
        if (!Files.exists(cgroup)) {
            return false;
        }
        try {
            String content = Files.readString(cgroup);
            return content.contains("docker") || content.contains("kubepods");
        } catch (Exception e) {
            return false;
        }
    }

}
