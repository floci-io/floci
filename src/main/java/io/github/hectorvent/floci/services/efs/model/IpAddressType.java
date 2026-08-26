package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
@RegisterForReflection
public enum IpAddressType {

    IPV4_ONLY,
    IPV6_ONLY,
    DUAL_STACK
}