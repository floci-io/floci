package io.github.hectorvent.floci.services.ecs.container;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hands each task container its own address in 169.254.0.0/16, which is what lets it reach the
 * credentials endpoint at all: without an address in that range the container's routing table has
 * no connected route for it, so the kernel sends the packet to the default gateway instead of
 * resolving the peer directly, and the connection is refused before it ever reaches the proxy.
 *
 * <p>Uniqueness is this class's job because Docker will not do it: two containers can be given the
 * same link-local address on one network and the daemon accepts both, after which which one
 * answers depends on whose ARP reply wins. Addresses are tracked per network, since separate
 * Docker networks are separate L2 segments and can reuse the same address safely, and released
 * when the task they belong to goes away so a long-running emulator does not leak the range.
 */
@ApplicationScoped
public class EcsTaskLinkLocalAddresses {

    /** Held by the credentials proxy itself, so it is never handed to a task. */
    static final String CREDENTIALS_ENDPOINT_ADDRESS = "169.254.170.2";
    /**
     * Allocation walks the /16 from 169.254.170.1 upward, stepping over the endpoint's own .2, so
     * a task's addresses sit next to the proxy where someone debugging will look for them rather
     * than somewhere unrelated in the range.
     */
    private static final int FIRST_THIRD_OCTET = 170;

    private final Map<String, Set<String>> takenByNetwork = new HashMap<>();
    private final Map<String, List<Allocation>> byTaskArn = new HashMap<>();

    /**
     * Reserves an unused address on this network for a container of this task.
     *
     * @throws IllegalStateException if the whole range is already spoken for on that network,
     *         which takes more than 16,000 simultaneous task containers on one network
     */
    public synchronized String allocate(String network, String taskArn) {
        Set<String> taken = takenByNetwork.computeIfAbsent(network, ignored -> new HashSet<>());
        // Linear from the start of the range each time rather than a moving cursor: a released
        // address becomes immediately reusable, which matters more here than the scan cost at
        // these sizes, and it keeps the addresses a person sees while debugging low and stable.
        for (int offset = 0; offset < 256; offset++) {
            int third = (FIRST_THIRD_OCTET + offset) % 256;
            for (int fourth = 1; fourth <= 254; fourth++) {
                String candidate = "169.254." + third + "." + fourth;
                if (CREDENTIALS_ENDPOINT_ADDRESS.equals(candidate) || taken.contains(candidate)) {
                    continue;
                }
                taken.add(candidate);
                byTaskArn.computeIfAbsent(taskArn, ignored -> new ArrayList<>())
                        .add(new Allocation(network, candidate));
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No link-local address left in 169.254.0.0/16 on network " + network);
    }

    /** Releases every address held by a task, on stop, exit, or a launch that failed part way. */
    public synchronized void releaseAll(String taskArn) {
        List<Allocation> allocations = byTaskArn.remove(taskArn);
        if (allocations == null) {
            return;
        }
        for (Allocation allocation : allocations) {
            Set<String> taken = takenByNetwork.get(allocation.network());
            if (taken != null) {
                taken.remove(allocation.address());
                if (taken.isEmpty()) {
                    takenByNetwork.remove(allocation.network());
                }
            }
        }
    }

    private record Allocation(String network, String address) {}
}
