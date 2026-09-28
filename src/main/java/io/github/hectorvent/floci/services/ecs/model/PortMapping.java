package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A container's port mapping.
 *
 * <p>{@code name} and {@code appProtocol} carry no behaviour here, but they are what a service's
 * {@code serviceConnectConfiguration} and {@code vpcLatticeConfigurations} reference by name, so a
 * definition registered for Service Connect has to read back with them intact.
 */
@RegisterForReflection
public record PortMapping(int containerPort, int hostPort, String protocol, String name,
                          String appProtocol, String containerPortRange) {

    public PortMapping(int containerPort) {
        this(containerPort, 0, "tcp");
    }

    public PortMapping(int containerPort, int hostPort, String protocol) {
        this(containerPort, hostPort, protocol, null, null, null);
    }
}
