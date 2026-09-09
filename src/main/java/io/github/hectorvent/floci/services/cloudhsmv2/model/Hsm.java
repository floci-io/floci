package io.github.hectorvent.floci.services.cloudhsmv2.model;

import java.time.Instant;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Represents an HSM instance within a CloudHSM v2 cluster.
 */
@RegisterForReflection
public class Hsm {

    private String hsmId;
    private String availabilityZone;
    private String clusterId;
    private String subnetId;
    private String eniId;
    private String eniIp;
    private String ipAddress;
    private String state;
    private String stateMessage;
    private Instant createdAt;
    private String eniIpV6;
    private String hsmType;

    public String getHsmId() {
        return hsmId;
    }

    public void setHsmId(String hsmId) {
        this.hsmId = hsmId;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public String getEniId() {
        return eniId;
    }

    public void setEniId(String eniId) {
        this.eniId = eniId;
    }

    public String getEniIp() {
        return eniIp;
    }

    public void setEniIp(String eniIp) {
        this.eniIp = eniIp;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public void setStateMessage(String stateMessage) {
        this.stateMessage = stateMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getEniIpV6() {
        return eniIpV6;
    }

    public void setEniIpV6(String eniIpV6) {
        this.eniIpV6 = eniIpV6;
    }

    public String getHsmType() {
        return hsmType;
    }

    public void setHsmType(String hsmType) {
        this.hsmType = hsmType;
    }
}
