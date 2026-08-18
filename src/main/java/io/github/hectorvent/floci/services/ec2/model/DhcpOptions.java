package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DhcpOptions {

    private String dhcpOptionsId;
    private String ownerId;
    private String region;
    private List<DhcpConfiguration> dhcpConfigurationSet = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public DhcpOptions() {}

    public String getDhcpOptionsId() { return dhcpOptionsId; }
    public void setDhcpOptionsId(String dhcpOptionsId) { this.dhcpOptionsId = dhcpOptionsId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<DhcpConfiguration> getDhcpConfigurationSet() { return dhcpConfigurationSet; }
    public void setDhcpConfigurationSet(List<DhcpConfiguration> dhcpConfigurationSet) { this.dhcpConfigurationSet = dhcpConfigurationSet; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
