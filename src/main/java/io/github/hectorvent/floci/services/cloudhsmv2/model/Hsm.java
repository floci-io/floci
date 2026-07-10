package io.github.hectorvent.floci.services.cloudhsmv2.model;

import java.time.Instant;

/**
 * Represents an HSM instance within a CloudHSM v2 cluster.
 */
public class Hsm {

    private String hsmId;
    private String availabilityZone;
    private String clusterId;
    private String subnetId;
    private String eniId;
    private String eniIp;
    private String state;
    private String stateMessage;
    private Instant createdAt;

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
}
