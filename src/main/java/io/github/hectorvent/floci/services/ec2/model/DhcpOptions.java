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
    private List<DhcpConfiguration> configurations = new ArrayList<>();

    public DhcpOptions() {}

    public String getDhcpOptionsId() { return dhcpOptionsId; }
    public void setDhcpOptionsId(String dhcpOptionsId) { this.dhcpOptionsId = dhcpOptionsId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public List<DhcpConfiguration> getConfigurations() { return configurations; }
    public void setConfigurations(List<DhcpConfiguration> configurations) { this.configurations = configurations; }
}
