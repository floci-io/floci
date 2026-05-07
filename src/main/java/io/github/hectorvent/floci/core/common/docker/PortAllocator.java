package io.github.hectorvent.floci.core.common.docker;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
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
            if (!reserved.contains(port) && isPortFree(port)) {
                reserved.add(port);
                LOG.debugv("Allocated port {0} from range {1}-{2}", port, basePort, maxPort);
                return port;
            }
        }
        throw new RuntimeException("No free port available in range " + basePort + "-" + maxPort);
    }

    /**
     * Releases a previously allocated port back to the pool.
     * Should be called when the Docker container that was using the port is removed.
     */
    public void release(int port) {
        if (reserved.remove(port)) {
            LOG.debugv("Released port {0}", port);
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
            LOG.debugv("Allocated ephemeral port {0}", port);
            return port;
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port", e);
        }
    }

    /**
     * Checks if a specific port is currently free.
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
}
