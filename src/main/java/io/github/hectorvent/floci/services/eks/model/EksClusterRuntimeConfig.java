package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Floci-only runtime settings for one EKS cluster, persisted separately from the AWS response
 * model so a DescribeCluster response remains AWS-shaped.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EksClusterRuntimeConfig {

    @JsonProperty("image")
    private String image;

    @JsonProperty("nodeIpv4Address")
    private String nodeIpv4Address;

    @JsonProperty("podIpv4Cidr")
    private String podIpv4Cidr;

    public EksClusterRuntimeConfig() {
    }

    public EksClusterRuntimeConfig(String image, String nodeIpv4Address, String podIpv4Cidr) {
        this.image = image;
        this.nodeIpv4Address = nodeIpv4Address;
        this.podIpv4Cidr = podIpv4Cidr;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getNodeIpv4Address() {
        return nodeIpv4Address;
    }

    public void setNodeIpv4Address(String nodeIpv4Address) {
        this.nodeIpv4Address = nodeIpv4Address;
    }

    public String getPodIpv4Cidr() {
        return podIpv4Cidr;
    }

    public void setPodIpv4Cidr(String podIpv4Cidr) {
        this.podIpv4Cidr = podIpv4Cidr;
    }
}
