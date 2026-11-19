package io.floci.aws.core.common;

/**
 * A service's application UIs that Floci opens for real, each on a host of its own, {@link AppUiHost}, which
 * {@link AppUiProxy} sends on to the interface.
 */
public interface AppUiRoutes {

    /** The label under Floci's host the hosts of the service's interfaces are in. */
    String hostLabel();

    /** The service whose interfaces these are, which must be enabled for a request to one to go on. */
    String service();

    /**
     * The route of a request whose {@code Host} is {@code hostHeader}, over {@code scheme}; null where the host is none
     * of the service's links. Called off the event loop, as finding an interface may ask Docker.
     */
    AppUiRoute route(String hostHeader, String scheme);
}
