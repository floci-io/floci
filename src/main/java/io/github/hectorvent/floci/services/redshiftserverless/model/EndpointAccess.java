package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Redshift Serverless managed VPC endpoint. Floci records it and reports the workgroup's own
 * endpoint as its address; it creates no network interface in any VPC.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EndpointAccess {

    private String endpointName;
    private String endpointArn;
    private String workgroupName;
    private String status;
    private Instant createTime;
    private String address;
    private int port;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> networkInterfaceIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private String vpcEndpointId;
    private String ownerId;

    public EndpointAccess() {
    }

    public EndpointAccess(EndpointAccess other) {
        this.ownerId = other.ownerId;
        this.endpointName = other.endpointName;
        this.endpointArn = other.endpointArn;
        this.workgroupName = other.workgroupName;
        this.status = other.status;
        this.createTime = other.createTime;
        this.address = other.address;
        this.port = other.port;
        this.subnetIds = new ArrayList<>(other.subnetIds);
        this.networkInterfaceIds = new ArrayList<>(other.networkInterfaceIds);
        this.vpcSecurityGroupIds = new ArrayList<>(other.vpcSecurityGroupIds);
        this.vpcEndpointId = other.vpcEndpointId;
    }

    public String getEndpointName() {
        return endpointName;
    }

    public void setEndpointName(String endpointName) {
        this.endpointName = endpointName;
    }

    public String getEndpointArn() {
        return endpointArn;
    }

    public void setEndpointArn(String endpointArn) {
        this.endpointArn = endpointArn;
    }

    public String getWorkgroupName() {
        return workgroupName;
    }

    public void setWorkgroupName(String workgroupName) {
        this.workgroupName = workgroupName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Instant createTime) {
        this.createTime = createTime;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds == null ? new ArrayList<>() : new ArrayList<>(subnetIds);
    }

    public List<String> getNetworkInterfaceIds() {
        return networkInterfaceIds;
    }

    public void setNetworkInterfaceIds(List<String> networkInterfaceIds) {
        this.networkInterfaceIds = networkInterfaceIds == null ? new ArrayList<>() : new ArrayList<>(networkInterfaceIds);
    }

    public List<String> getVpcSecurityGroupIds() {
        return vpcSecurityGroupIds;
    }

    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds == null ? new ArrayList<>() : new ArrayList<>(vpcSecurityGroupIds);
    }

    public String getVpcEndpointId() {
        return vpcEndpointId;
    }

    public void setVpcEndpointId(String vpcEndpointId) {
        this.vpcEndpointId = vpcEndpointId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
}
