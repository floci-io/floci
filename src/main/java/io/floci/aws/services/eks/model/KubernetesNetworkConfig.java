package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KubernetesNetworkConfig {

    @JsonProperty("serviceIpv4Cidr")
    private String serviceIpv4Cidr;

    @JsonProperty("ipFamily")
    private String ipFamily;

    public KubernetesNetworkConfig() {}

    public String getServiceIpv4Cidr() { return serviceIpv4Cidr; }
    public void setServiceIpv4Cidr(String serviceIpv4Cidr) { this.serviceIpv4Cidr = serviceIpv4Cidr; }

    public String getIpFamily() { return ipFamily; }
    public void setIpFamily(String ipFamily) { this.ipFamily = ipFamily; }
}
