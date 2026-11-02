package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record DelegationSet(String id, String callerReference, List<String> nameServers) {
    public DelegationSet {
        nameServers = List.copyOf(nameServers);
    }
}
