package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Accelerator {

    @JsonProperty("AcceleratorArn")
    private String acceleratorArn;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("IpAddressType")
    private String ipAddressType;

    @JsonProperty("Enabled")
    private Boolean enabled;

    @JsonProperty("IpSets")
    private List<IpSet> ipSets = new ArrayList<>();

    @JsonProperty("DnsName")
    private String dnsName;

    @JsonProperty("DualStackDnsName")
    private String dualStackDnsName;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("CreatedTime")
    private Long createdTime;

    @JsonProperty("LastModifiedTime")
    private Long lastModifiedTime;

    @JsonProperty("Events")
    private List<AcceleratorEvent> events = new ArrayList<>();

    public Accelerator() {}

    public String getAcceleratorArn() { return acceleratorArn; }
    public void setAcceleratorArn(String acceleratorArn) { this.acceleratorArn = acceleratorArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public List<IpSet> getIpSets() { return ipSets; }
    public void setIpSets(List<IpSet> ipSets) { this.ipSets = ipSets != null ? ipSets : new ArrayList<>(); }

    public String getDnsName() { return dnsName; }
    public void setDnsName(String dnsName) { this.dnsName = dnsName; }

    public String getDualStackDnsName() { return dualStackDnsName; }
    public void setDualStackDnsName(String dualStackDnsName) { this.dualStackDnsName = dualStackDnsName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }

    public Long getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Long lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public List<AcceleratorEvent> getEvents() { return events; }
    public void setEvents(List<AcceleratorEvent> events) {
        this.events = events != null ? events : new ArrayList<>();
    }
}
