package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class ResourceShare {

    private final String resourceShareArn;
    private final String name;
    private final String owningAccountId;
    private final List<String> principals;
    private final List<String> resourceArns;
    private final boolean allowExternalPrincipals;
    private final String status = "ACTIVE";
    private final Instant creationTime = Instant.now();

    public ResourceShare(String resourceShareArn, String name, String owningAccountId,
                         List<String> principals, List<String> resourceArns,
                         boolean allowExternalPrincipals) {
        this.resourceShareArn = resourceShareArn;
        this.name = name;
        this.owningAccountId = owningAccountId;
        this.principals = List.copyOf(principals);
        this.resourceArns = List.copyOf(resourceArns);
        this.allowExternalPrincipals = allowExternalPrincipals;
    }

    public String getResourceShareArn() { return resourceShareArn; }
    public String getName() { return name; }
    public String getOwningAccountId() { return owningAccountId; }
    public List<String> getPrincipals() { return principals; }
    public List<String> getResourceArns() { return resourceArns; }
    public boolean isAllowExternalPrincipals() { return allowExternalPrincipals; }
    public String getStatus() { return status; }
    public Instant getCreationTime() { return creationTime; }
}
