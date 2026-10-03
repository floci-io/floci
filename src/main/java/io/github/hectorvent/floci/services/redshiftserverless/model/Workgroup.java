package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Workgroup {
    private String workgroupName;
    private String workgroupId;
    private String workgroupArn;
    private String namespaceName;
    private Integer baseCapacity;
    private Integer maxCapacity;
    private boolean enhancedVpcRouting;
    private boolean publiclyAccessible;
    private boolean extraComputeForAutomaticOptimization;
    private List<ConfigParameter> configParameters = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private String status;
    private Endpoint endpoint;
    private int port;
    private String ipAddressType;
    private String trackName;
    private String pendingTrackName;
    private PricePerformanceTarget pricePerformanceTarget;
    private Instant creationDate;
    private Map<String, String> tags = new LinkedHashMap<>();
    /** Address of the PostgreSQL container behind the proxy; not part of the AWS resource. */
    private String runtimeHost;
    private int runtimePort;
    /** The PostgreSQL role the container was created with; it cannot be renamed afterwards. */
    private String masterUsername;

    public Workgroup() {
    }

    /**
     * Copy used by services to build an update on a private instance and store that, so a reader
     * holding the previous instance never sees a half-applied update. Collections are copied; the
     * nested endpoint and target are replaced wholesale rather than mutated, so they are shared.
     */
    public Workgroup(Workgroup other) {
        this.workgroupName = other.workgroupName;
        this.workgroupId = other.workgroupId;
        this.workgroupArn = other.workgroupArn;
        this.namespaceName = other.namespaceName;
        this.baseCapacity = other.baseCapacity;
        this.maxCapacity = other.maxCapacity;
        this.enhancedVpcRouting = other.enhancedVpcRouting;
        this.publiclyAccessible = other.publiclyAccessible;
        this.extraComputeForAutomaticOptimization = other.extraComputeForAutomaticOptimization;
        this.configParameters = new ArrayList<>(other.configParameters);
        this.securityGroupIds = new ArrayList<>(other.securityGroupIds);
        this.subnetIds = new ArrayList<>(other.subnetIds);
        this.status = other.status;
        this.endpoint = other.endpoint;
        this.port = other.port;
        this.ipAddressType = other.ipAddressType;
        this.trackName = other.trackName;
        this.pendingTrackName = other.pendingTrackName;
        this.pricePerformanceTarget = other.pricePerformanceTarget;
        this.creationDate = other.creationDate;
        this.tags = new LinkedHashMap<>(other.tags);
        this.runtimeHost = other.runtimeHost;
        this.runtimePort = other.runtimePort;
        this.masterUsername = other.masterUsername;
    }

    public String getWorkgroupName() {
        return workgroupName;
    }

    public void setWorkgroupName(String workgroupName) {
        this.workgroupName = workgroupName;
    }

    public String getWorkgroupId() {
        return workgroupId;
    }

    public void setWorkgroupId(String workgroupId) {
        this.workgroupId = workgroupId;
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

    public boolean isEnhancedVpcRouting() {
        return enhancedVpcRouting;
    }

    public void setEnhancedVpcRouting(boolean enhancedVpcRouting) {
        this.enhancedVpcRouting = enhancedVpcRouting;
    }

    public boolean isPubliclyAccessible() {
        return publiclyAccessible;
    }

    public void setPubliclyAccessible(boolean publiclyAccessible) {
        this.publiclyAccessible = publiclyAccessible;
    }

    public boolean isExtraComputeForAutomaticOptimization() {
        return extraComputeForAutomaticOptimization;
    }

    public void setExtraComputeForAutomaticOptimization(boolean extraComputeForAutomaticOptimization) {
        this.extraComputeForAutomaticOptimization = extraComputeForAutomaticOptimization;
    }

    public List<ConfigParameter> getConfigParameters() {
        return configParameters;
    }

    public void setConfigParameters(List<ConfigParameter> configParameters) {
        this.configParameters = configParameters;
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getIpAddressType() {
        return ipAddressType;
    }

    public void setIpAddressType(String ipAddressType) {
        this.ipAddressType = ipAddressType;
    }

    public String getTrackName() {
        return trackName;
    }

    public void setTrackName(String trackName) {
        this.trackName = trackName;
    }

    public String getPendingTrackName() {
        return pendingTrackName;
    }

    public void setPendingTrackName(String pendingTrackName) {
        this.pendingTrackName = pendingTrackName;
    }

    public PricePerformanceTarget getPricePerformanceTarget() {
        return pricePerformanceTarget;
    }

    public void setPricePerformanceTarget(PricePerformanceTarget pricePerformanceTarget) {
        this.pricePerformanceTarget = pricePerformanceTarget;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
    }

    public String getRuntimeHost() {
        return runtimeHost;
    }

    public void setRuntimeHost(String runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    public int getRuntimePort() {
        return runtimePort;
    }

    public void setRuntimePort(int runtimePort) {
        this.runtimePort = runtimePort;
    }

    public String getMasterUsername() {
        return masterUsername;
    }

    public void setMasterUsername(String masterUsername) {
        this.masterUsername = masterUsername;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
