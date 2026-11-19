package io.floci.aws.services.ec2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The client family a host-published application port admits: the host addresses its sidecar binds
 * and the instance address it dials. Not part of the AWS wire format.
 */
@RegisterForReflection
public enum PublishedPortFamily {
    /** Every host address, dialling the instance's IPv4 address. */
    ANY,
    /** Host IPv4 addresses only, dialling the instance's IPv4 address. */
    IPV4,
    /** Host IPv6 addresses only, dialling the instance's Docker IPv6 address. */
    IPV6
}
