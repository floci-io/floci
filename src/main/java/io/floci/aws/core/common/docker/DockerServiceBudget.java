package io.floci.aws.core.common.docker;

import java.util.HashMap;
import java.util.Map;

/**
 * How many containers each service may run at once ({@code floci.docker.service-max-containers.<service>}),
 * so one service cannot take every container the daemon can hold and leave the others to time out. A
 * service's live containers are the ones {@link DockerContainerServices} has recorded and not yet seen
 * removed; a create in progress holds a reservation, so concurrent creates cannot both take the last
 * one. A service with no budget is not limited, and neither is a container without a service label.
 */
final class DockerServiceBudget {

    private static final Map<String, Integer> RESERVED = new HashMap<>();

    private DockerServiceBudget() {
    }

    /**
     * Reserves one container for {@code service}, or throws {@link DockerCapacityExceededException} when
     * its running containers and reservations already reach {@code limit}. Release the reservation with
     * {@link #release} once the create has succeeded or failed.
     */
    static void reserve(String service, int limit) {
        synchronized (RESERVED) {
            int reserved = RESERVED.getOrDefault(service, 0);
            if (DockerContainerServices.liveContainers(service) + reserved >= limit) {
                throw new DockerCapacityExceededException(service, limit);
            }
            RESERVED.put(service, reserved + 1);
        }
    }

    static void release(String service) {
        synchronized (RESERVED) {
            RESERVED.computeIfPresent(service, (key, reserved) -> reserved > 1 ? reserved - 1 : null);
        }
    }
}
