package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A prefix list reference inside an {@link IpPermission}, as AWS returns it under prefixListIds. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrefixListId {

    private String prefixListId;
    private String description;

    public PrefixListId() {}

    public PrefixListId(String prefixListId, String description) {
        this.prefixListId = prefixListId;
        this.description = description;
    }

    public String getPrefixListId() { return prefixListId; }
    public void setPrefixListId(String prefixListId) { this.prefixListId = prefixListId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
