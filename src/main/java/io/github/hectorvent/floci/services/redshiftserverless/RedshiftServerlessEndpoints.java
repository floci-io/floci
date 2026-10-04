package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Allocates the endpoint advertised for a workgroup from the pool Redshift proxies share, so that a
 * workgroup and a provisioned cluster can never be given the same port.
 */
@ApplicationScoped
public class RedshiftServerlessEndpoints {

    private final RedshiftService redshiftService;

    @Inject
    public RedshiftServerlessEndpoints(RedshiftService redshiftService) {
        this.redshiftService = redshiftService;
    }

    public Endpoint allocate() {
        return redshiftService.advertisedEndpoint(redshiftService.reserveProxyPort());
    }

    /**
     * Re-reserves a persisted endpoint after a restart and rebuilds its address from the current
     * configuration, so a changed {@code endpoint-host} is picked up. Allocates a fresh endpoint if
     * the persisted port is taken.
     */
    public Endpoint restore(Endpoint persisted) {
        if (persisted != null && redshiftService.reserveProxyPort(persisted.getPort())) {
            return redshiftService.advertisedEndpoint(persisted.getPort());
        }
        return allocate();
    }

    public void release(Endpoint endpoint) {
        if (endpoint != null) {
            redshiftService.releaseProxyPort(endpoint.getPort());
        }
    }
}
