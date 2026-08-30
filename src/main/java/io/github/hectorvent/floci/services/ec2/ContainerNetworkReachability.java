package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import com.github.dockerjava.api.DockerClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers one question: is a container's own IP an address that Floci's API clients
 * (Terraform, Terratest, the user's shell) can actually open a connection to?
 *
 * <p>It matters because everything Floci reports as an instance's public address is
 * consumed by something outside Floci that will dial it on the service's real port —
 * Terratest builds {@code ssh.Host{Hostname: <public ip>}} and connects to 22. Reporting
 * {@code 127.0.0.1} is only correct when SSH is published there, and it is not: Docker
 * publishes it on a high host port. The container's own IP, where it is routable, is the
 * address on which port 22 really is port 22.
 *
 * <p>Container IPs are routable from the host on native Linux Docker and on OrbStack; they
 * are not on Docker Desktop for macOS/Windows, where the containers live behind a VM. So the
 * answer is detected, never assumed, and an explicit configuration value always wins.
 *
 * <p>Detection is a throwaway TCP connect towards the container network. A refused
 * connection is the strongest possible evidence of a route: the RST could only have come
 * from the container's network stack. A timeout or an unreachable-host error means there is
 * no route. Nothing needs to be listening for this to work, so it can run the moment a
 * container starts.
 *
 * <p>The one case the probe cannot settle is Floci running inside a container itself: then
 * the probe measures container-to-container reachability, while the clients that will use
 * the answer are on the host. There, the probe is corroborated against the Docker daemon's
 * reported backend, and a Docker Desktop backend vetoes it.
 */
@ApplicationScoped
public class ContainerNetworkReachability {

    private static final Logger LOG = Logger.getLogger(ContainerNetworkReachability.class);

    /** SSH: present on every EC2 guest image, so a refusal here is still a routing answer. */
    static final int PROBE_PORT = 22;

    static int probeTimeoutMillis = 1500;

    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final DockerClient dockerClient;

    private volatile Boolean cached;

    @Inject
    public ContainerNetworkReachability(EmulatorConfig config,
                                        ContainerDetector containerDetector,
                                        DockerClient dockerClient) {
        this.config = config;
        this.containerDetector = containerDetector;
        this.dockerClient = dockerClient;
    }

    /**
     * @param containerIp a live container's bridge IP, used as the probe target
     * @return true when addresses on the container network can be handed to API clients
     */
    public boolean isContainerIpRoutable(String containerIp) {
        Optional<Boolean> override = config.services().ec2().containerIpsRoutable();
        if (override.isPresent()) {
            return override.get();
        }
        if (containerIp == null || containerIp.isBlank()) {
            return false;
        }
        Boolean known = cached;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (cached == null) {
                cached = detect(containerIp);
                LOG.infov("Container network routability from Floci''s API clients: {0} (probed {1}:{2})",
                        cached, containerIp, String.valueOf(PROBE_PORT));
            }
            return cached;
        }
    }

    /** Resets the memoised answer. Test seam. */
    void forget() {
        cached = null;
    }

    private boolean detect(String containerIp) {
        if (!probe(containerIp)) {
            return false;
        }
        if (!containerDetector.isRunningInContainer()) {
            // Floci is a host process, so it shares a vantage point with its API clients:
            // what Floci can reach, Terraform and Terratest can reach.
            return true;
        }
        // Floci is containerised. The probe just proved container-to-container reachability,
        // which is true even on Docker Desktop, where the host still cannot route there.
        String backend = dockerBackend();
        if (backend.contains("docker desktop")) {
            LOG.infov("Docker backend is {0}: container IPs are not routable from the host, so "
                            + "EC2 public addresses stay on 127.0.0.1 with published ports. Set "
                            + "FLOCI_SERVICES_EC2_CONTAINER_IPS_ROUTABLE=true to override.",
                    backend);
            return false;
        }
        return true;
    }

    private boolean probe(String containerIp) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(containerIp, PROBE_PORT), probeTimeoutMillis);
            return true;
        } catch (ConnectException e) {
            // Refused: a RST came back, which means the packet reached the container network.
            // NoRouteToHostException is a sibling of ConnectException, not a subclass, so it
            // is deliberately not caught here.
            return true;
        } catch (Exception e) {
            LOG.debugv("Container network probe to {0}:{1} failed ({2}): treating container IPs as unroutable",
                    containerIp, String.valueOf(PROBE_PORT), e.toString());
            return false;
        }
    }

    private String dockerBackend() {
        try {
            String os = dockerClient.infoCmd().exec().getOperatingSystem();
            return os == null ? "" : os.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            LOG.debugv("Could not read the Docker daemon's operating system: {0}", e.getMessage());
            return "";
        }
    }
}
