package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Utility for allocating free TCP ports for Docker container port bindings.
 * Consolidates the free port discovery logic previously duplicated across
 * container managers (RDS, ElastiCache, MSK, ECR).
 */
@ApplicationScoped
public class PortAllocator {

    private static final Logger LOG = Logger.getLogger(PortAllocator.class);

    // Ports reserved by this process but not yet bound by Docker.
    // Prevents TOCTOU races when multiple containers are launched concurrently.
    private final Set<Integer> reserved = new ConcurrentSkipListSet<>();

    // Nullable: the no-arg constructor below (used throughout this class's own
    // test suite, and by any caller that predates this field) leaves it null,
    // which keeps isPortBoundByAnyContainer a no-op and this class's behavior
    // toward every existing caller byte-identical. Only the CDI-managed
    // instance - built via the @Inject constructor - ever has one.
    private final DockerClient dockerClient;

    public PortAllocator() {
        this(null);
    }

    @Inject
    public PortAllocator(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /**
     * Atomically finds and reserves a free TCP port within the specified range.
     * The port is held in-memory until {@link #release(int)} is called, preventing
     * concurrent callers from picking the same port before Docker binds it.
     *
     * @param basePort the lowest port number to try (inclusive)
     * @param maxPort  the highest port number to try (inclusive)
     * @return a reserved free port within the range
     * @throws RuntimeException if no free port is available in the range
     */
    public synchronized int allocate(int basePort, int maxPort) {
        for (int port = basePort; port <= maxPort; port++) {
            if (!reserved.contains(port) && isPortFree(port) && !isPortBoundByAnyContainer(port)) {
                reserved.add(port);
                LOG.debugv("Allocated port {0} from range {1}-{2}", String.valueOf(port), String.valueOf(basePort), String.valueOf(maxPort));
                return port;
            }
        }
        throw new RuntimeException("No free port available in range " + basePort + "-" + maxPort);
    }

    /**
     * Marks a port as reserved without probing whether it is free. Used on restart to
     * re-reserve host ports already held by surviving containers (e.g. persisted EC2
     * port forwards) so the allocator does not hand them out again.
     */
    public synchronized void markReserved(int port) {
        reserved.add(port);
    }

    /**
     * Releases a previously allocated port back to the pool.
     * Should be called when the Docker container that was using the port is removed.
     */
    public void release(int port) {
        if (reserved.remove(port)) {
            LOG.debugv("Released port {0}", String.valueOf(port));
        }
    }

    /**
     * Finds any free TCP port using ephemeral port allocation.
     * This is the fastest method when any port will do.
     *
     * @return a free port
     * @throws RuntimeException if no free port can be allocated
     */
    public int allocateAny() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            LOG.debugv("Allocated ephemeral port {0}", String.valueOf(port));
            return port;
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port", e);
        }
    }

    /**
     * Checks if a specific port is currently free.
     *
     * <p>This binds a {@link ServerSocket} in floci's OWN network namespace. That
     * namespace is meaningful when floci itself runs as a bare process, but floci
     * normally runs inside its own Docker container, where every other network
     * namespace on the host - in particular a SIBLING container's own {@code -p
     * hostPort:containerPort} publication (an EKS cluster's k3s container, an
     * MSK broker, an ECR registry mirror) - is completely invisible to this
     * check: "is port free" here can only ever mean "free inside my own
     * container," which is not the question a caller allocating a HOST port for
     * a sibling container is actually asking. See {@link #isPortBoundByAnyContainer}
     * for the other half {@link #allocate} needs to answer that question for real.
     *
     * @param port the port to check
     * @return true if the port is available, false otherwise
     */
    public boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reports whether port is already published as a host port by ANY Docker
     * container (running or not - a container mid-{@code docker run} that has
     * not started yet, the shape a losing race between two sibling k3s
     * containers takes, still holds its port binding). This is what
     * {@link #isPortFree} cannot see from inside floci's own container network
     * namespace, and why {@link #allocate} checks both: a host running two
     * floci instances that each manage their own sibling containers on the
     * SAME Docker host - lex00/floci issue naming this class of bug: two
     * independently-started EKS real-mode clusters both allocating host port
     * 6500 for their own k3s API server container, because each floci
     * instance's own in-process/in-namespace check saw its OWN container as
     * having nothing bound - is exactly the case this closes.
     *
     * <p>Returns false (never blocks allocation) when this instance was built
     * without a {@link DockerClient} - the no-arg constructor's own contract,
     * unchanged for every caller that predates this method - or when the
     * Docker API call itself fails, matching {@link #isPortFree}'s own
     * fail-permissive posture for a probe that could not be completed.
     */
    boolean isPortBoundByAnyContainer(int port) {
        if (dockerClient == null) {
            return false;
        }
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            for (Container c : containers) {
                ContainerPort[] ports = c.getPorts();
                if (ports == null) {
                    continue;
                }
                for (ContainerPort p : ports) {
                    Integer publicPort = p.getPublicPort();
                    if (publicPort != null && publicPort == port) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Error checking Docker port bindings for {0}: {1}", port, e.getMessage());
        }
        return false;
    }
}
