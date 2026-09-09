package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrefixListEntry {

    private String cidr;
    private String description;

    public PrefixListEntry() {}

    public PrefixListEntry(String cidr, String description) {
        this.cidr = cidr;
        this.description = description;
    }

    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
