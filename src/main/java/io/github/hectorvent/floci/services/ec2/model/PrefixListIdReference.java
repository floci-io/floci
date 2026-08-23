package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of an {@code IpPermission}'s {@code PrefixListIds} member: a managed prefix list used
 * as the rule's source/destination instead of a CIDR block or a referenced security group.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrefixListIdReference {

    private String prefixListId;
    private String description;

    public PrefixListIdReference() {}

    public PrefixListIdReference(String prefixListId) {
        this.prefixListId = prefixListId;
    }

    public PrefixListIdReference(String prefixListId, String description) {
        this.prefixListId = prefixListId;
        this.description = description;
    }

    public String getPrefixListId() { return prefixListId; }
    public void setPrefixListId(String prefixListId) { this.prefixListId = prefixListId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
