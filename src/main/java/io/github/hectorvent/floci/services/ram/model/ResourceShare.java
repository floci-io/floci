package io.github.hectorvent.floci.services.ram.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ResourceShare {

    private final String resourceShareArn;
    private final String name;
    private final String owningAccountId;
    private final List<String> principals;
    private final List<String> resourceArns;
    private final boolean allowExternalPrincipals;
    private final String status;
    private final Instant creationTime;
    private final Map<String, String> tags;

    public ResourceShare(String resourceShareArn, String name, String owningAccountId,
                         List<String> principals, List<String> resourceArns,
                         boolean allowExternalPrincipals) {
        this(resourceShareArn, name, owningAccountId, principals, resourceArns,
                allowExternalPrincipals, "ACTIVE", Instant.now(), Map.of());
    }

    @JsonCreator
    public ResourceShare(
            @JsonProperty("resourceShareArn") String resourceShareArn,
            @JsonProperty("name") String name,
            @JsonProperty("owningAccountId") String owningAccountId,
            @JsonProperty("principals") List<String> principals,
            @JsonProperty("resourceArns") List<String> resourceArns,
            @JsonProperty("allowExternalPrincipals") boolean allowExternalPrincipals,
            @JsonProperty("status") String status,
            @JsonProperty("creationTime") Instant creationTime,
            @JsonProperty("tags") Map<String, String> tags) {
        this.resourceShareArn = resourceShareArn;
        this.name = name;
        this.owningAccountId = owningAccountId;
        this.principals = List.copyOf(principals);
        this.resourceArns = List.copyOf(resourceArns);
        this.allowExternalPrincipals = allowExternalPrincipals;
        this.status = status;
        this.creationTime = creationTime;
        this.tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public String getResourceShareArn() { return resourceShareArn; }
    public String getName() { return name; }
    public String getOwningAccountId() { return owningAccountId; }
    public List<String> getPrincipals() { return principals; }
    public List<String> getResourceArns() { return resourceArns; }
    public boolean isAllowExternalPrincipals() { return allowExternalPrincipals; }
    public String getStatus() { return status; }
    public Instant getCreationTime() { return creationTime; }
    public Map<String, String> getTags() { return tags; }

    public ResourceShare withName(String newName) {
        return new ResourceShare(resourceShareArn, newName, owningAccountId, principals, resourceArns,
                allowExternalPrincipals, status, creationTime, tags);
    }

    public ResourceShare withAllowExternalPrincipals(boolean value) {
        return new ResourceShare(resourceShareArn, name, owningAccountId, principals, resourceArns,
                value, status, creationTime, tags);
    }

    public ResourceShare withStatus(String newStatus) {
        return new ResourceShare(resourceShareArn, name, owningAccountId, principals, resourceArns,
                allowExternalPrincipals, newStatus, creationTime, tags);
    }

    public ResourceShare withPrincipalsAndResources(List<String> newPrincipals, List<String> newResourceArns) {
        return new ResourceShare(resourceShareArn, name, owningAccountId, newPrincipals, newResourceArns,
                allowExternalPrincipals, status, creationTime, tags);
    }

    public ResourceShare withTags(Map<String, String> newTags) {
        return new ResourceShare(resourceShareArn, name, owningAccountId, principals, resourceArns,
                allowExternalPrincipals, status, creationTime, newTags);
    }
}
