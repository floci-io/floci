package io.github.hectorvent.floci.services.redshiftserverless.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Redshift Serverless workgroup. Carries its own endpoint, which is what the
 * {@code x-aws-redshiftserverless} chart's VDNSEntry ({@code vreds-{namespaceName}}) points at
 * once the workgroup is Ready.
 */
public class RedshiftServerlessWorkgroup {

    private String workgroupId;
    private String workgroupName;
    private String workgroupArn;
    private String namespaceName;
    private String region;
    private String accountId;
    private Integer baseCapacity;
    private Integer maxCapacity;
    private Boolean enhancedVpcRouting = Boolean.FALSE;
    private Boolean publiclyAccessible = Boolean.FALSE;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private Integer port = 5439;
    private String status = "AVAILABLE";
    private String endpointAddress;
    private Integer endpointPort = 5439;
    private Instant creationDate;

    public String getWorkgroupId() {
        return workgroupId;
    }

    public void setWorkgroupId(String workgroupId) {
        this.workgroupId = workgroupId;
    }

    public String getWorkgroupName() {
        return workgroupName;
    }

    public void setWorkgroupName(String workgroupName) {
        this.workgroupName = workgroupName;
    }

    public String getWorkgroupArn() {
        return workgroupArn;
    }

    public void setWorkgroupArn(String workgroupArn) {
        this.workgroupArn = workgroupArn;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Integer getBaseCapacity() {
        return baseCapacity;
    }

    public void setBaseCapacity(Integer baseCapacity) {
        this.baseCapacity = baseCapacity;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public Boolean getEnhancedVpcRouting() {
        return enhancedVpcRouting;
    }

    public void setEnhancedVpcRouting(Boolean enhancedVpcRouting) {
        this.enhancedVpcRouting = enhancedVpcRouting;
    }

    public Boolean getPubliclyAccessible() {
        return publiclyAccessible;
    }

    public void setPubliclyAccessible(Boolean publiclyAccessible) {
        this.publiclyAccessible = publiclyAccessible;
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? securityGroupIds : new ArrayList<>();
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? subnetIds : new ArrayList<>();
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEndpointAddress() {
        return endpointAddress;
    }

    public void setEndpointAddress(String endpointAddress) {
        this.endpointAddress = endpointAddress;
    }

    public Integer getEndpointPort() {
        return endpointPort;
    }

    public void setEndpointPort(Integer endpointPort) {
        this.endpointPort = endpointPort;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
    }
}
