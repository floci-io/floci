package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Checks whether a container that survived a restart still sits on the Docker network Floci uses.
 */
public final class ContainerNetworks {

    private static final Logger LOG = Logger.getLogger(ContainerNetworks.class);

    private ContainerNetworks() {
    }

    /**
     * True when {@code survivor} is attached to Docker networks but not to {@code network}, so Floci in a
     * container cannot resolve it by name. A {@code null} network, or a survivor reporting no networks,
     * is never treated as moved.
     */
    public static boolean onAnotherNetwork(Container survivor, String network) {
        if (network == null || survivor.getNetworkSettings() == null) {
            return false;
        }
        Map<String, ContainerNetwork> networks = survivor.getNetworkSettings().getNetworks();
        if (networks == null || networks.isEmpty()) {
            return false;
        }
        boolean moved = !networks.containsKey(network);
        if (moved) {
            LOG.infov("Surviving container {0} is not on Docker network {1}; replacing it (its data is kept)",
                    survivor.getId(), network);
        }
        return moved;
    }
}
