package io.floci.aws.core.common.docker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Floci service each container was created for, read from its {@code io.floci.service} label, so
 * the Docker transport can count the streams it holds open per service. {@link ContainerLifecycleManager}
 * records a container when it creates one, under its id and its name; {@link FlociDockerHttpClient}
 * forgets it once a remove of it succeeds, whichever caller sent the remove. A stream that names no
 * container (an exec, a pull, a build, daemon events), and one following a container not recorded (one
 * created without the label, before a restart, or other than through {@link ContainerLifecycleManager}),
 * is {@link #UNATTRIBUTED}. A container removed outside Floci's transport keeps its entry, and so its
 * service, until the process ends.
 */
final class DockerContainerServices {

    /** The service of a stream whose container is unknown, or that follows no container. */
    static final String UNATTRIBUTED = "unattributed";

    private static final Map<String, Owner> OWNERS = new ConcurrentHashMap<>();

    private DockerContainerServices() {
    }

    /** Records the service a container's labels name; a container without the label is not recorded. */
    static void record(String containerId, String containerName, Map<String, String> labels) {
        String service = labels == null ? null
                : ContainerStorageHelper.labelValue(labels, ContainerStorageHelper.SERVICE_LABEL);
        if (containerId == null || service == null || service.isBlank()) {
            return;
        }
        boolean named = containerName != null && !containerName.isBlank();
        OWNERS.put(containerId, new Owner(service, named ? containerName : null));
        if (named) {
            Owner previous = OWNERS.put(containerName, new Owner(service, containerId));
            // An earlier container of this name (removed outside the transport) keeps its own id's
            // entry, but no longer as this name's other key, so forgetting it leaves the new name be.
            if (previous != null && previous.alias() != null && !previous.alias().equals(containerId)) {
                OWNERS.replace(previous.alias(), new Owner(previous.service(), containerName),
                        new Owner(previous.service(), null));
            }
        }
    }

    /** The service the container with this id or name was created for, or {@link #UNATTRIBUTED}. */
    static String serviceOf(String container) {
        Owner owner = container == null ? null : OWNERS.get(container);
        return owner == null ? UNATTRIBUTED : owner.service();
    }

    /** Forgets a removed container, given by its id or its name. */
    static void forget(String container) {
        Owner owner = OWNERS.remove(container);
        if (owner != null && owner.alias() != null) {
            // Only while the other key still points back here: a newer container may hold the name now.
            OWNERS.remove(owner.alias(), new Owner(owner.service(), container));
        }
    }

    /** @param alias the container's other key (its name for an id, its id for a name), or null */
    private record Owner(String service, String alias) {
    }
}
