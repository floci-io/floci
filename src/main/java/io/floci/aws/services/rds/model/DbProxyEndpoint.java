package io.floci.aws.services.rds.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A DB proxy endpoint as DescribeDBProxyEndpoints reports it. Endpoints a caller creates are
 * stored; a proxy's default endpoint is built from the proxy when described and carries no ARN.
 * Every endpoint reports the proxy's own host, so a connection through it reaches the proxy's
 * listener and the proxy's one target, whatever its target role. The host is not stored: a restored
 * proxy can come back on a new one, so each response reads it from the proxy.
 */
@RegisterForReflection
public class DbProxyEndpoint {

    private String dbProxyEndpointName;
    private String dbProxyEndpointArn;
    private String dbProxyName;
    private String status;
    private String vpcId;
    private List<String> vpcSubnetIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    /** The owning proxy's host, filled in for each response. */
    @JsonIgnore
    private String endpoint;
    private Instant createdDate;
    /** READ_WRITE or READ_ONLY. */
    private String targetRole;
    private boolean isDefault;
    private String endpointNetworkType;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbProxyEndpoint() {}

    public String getDbProxyEndpointName() { return dbProxyEndpointName; }
    public void setDbProxyEndpointName(String dbProxyEndpointName) { this.dbProxyEndpointName = dbProxyEndpointName; }

    public String getDbProxyEndpointArn() { return dbProxyEndpointArn; }
    public void setDbProxyEndpointArn(String dbProxyEndpointArn) { this.dbProxyEndpointArn = dbProxyEndpointArn; }

    public String getDbProxyName() { return dbProxyName; }
    public void setDbProxyName(String dbProxyName) { this.dbProxyName = dbProxyName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public List<String> getVpcSubnetIds() { return vpcSubnetIds; }
    public void setVpcSubnetIds(List<String> vpcSubnetIds) {
        this.vpcSubnetIds = vpcSubnetIds != null ? new ArrayList<>(vpcSubnetIds) : new ArrayList<>();
    }

    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds != null ? new ArrayList<>(vpcSecurityGroupIds) : new ArrayList<>();
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public Instant getCreatedDate() { return createdDate; }
    public void setCreatedDate(Instant createdDate) { this.createdDate = createdDate; }

    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public String getEndpointNetworkType() { return endpointNetworkType; }
    public void setEndpointNetworkType(String endpointNetworkType) { this.endpointNetworkType = endpointNetworkType; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
