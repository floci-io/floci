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
public class IpSet {

    @JsonProperty("IpFamily")
    private String ipFamily;

    @JsonProperty("IpAddresses")
    private List<String> ipAddresses = new ArrayList<>();

    @JsonProperty("IpAddressFamily")
    private String ipAddressFamily;

    public IpSet() {}

    public String getIpFamily() { return ipFamily; }
    public void setIpFamily(String ipFamily) { this.ipFamily = ipFamily; }

    public List<String> getIpAddresses() { return ipAddresses; }
    public void setIpAddresses(List<String> ipAddresses) {
        this.ipAddresses = ipAddresses != null ? ipAddresses : new ArrayList<>();
    }

    public String getIpAddressFamily() { return ipAddressFamily; }
    public void setIpAddressFamily(String ipAddressFamily) { this.ipAddressFamily = ipAddressFamily; }
}
