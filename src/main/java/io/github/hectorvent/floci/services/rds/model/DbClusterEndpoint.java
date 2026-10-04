package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Aurora cluster endpoint as DescribeDBClusterEndpoints reports it: a stored custom endpoint, or
 * a built-in WRITER or READER endpoint derived from its cluster. The {@code endpoint} host is
 * resolved from the cluster whenever the endpoint is read and is never persisted meaningfully.
 */
@RegisterForReflection
public class DbClusterEndpoint {

    private String dbClusterEndpointIdentifier;
    private String dbClusterIdentifier;
    private String dbClusterEndpointResourceIdentifier;
    private String endpoint;
    private String status;
    private String endpointType;
    private String customEndpointType;
    private List<String> staticMembers = new ArrayList<>();
    private List<String> excludedMembers = new ArrayList<>();
    private String dbClusterEndpointArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getDbClusterEndpointIdentifier() { return dbClusterEndpointIdentifier; }
    public void setDbClusterEndpointIdentifier(String dbClusterEndpointIdentifier) {
        this.dbClusterEndpointIdentifier = dbClusterEndpointIdentifier;
    }
    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) {
        this.dbClusterIdentifier = dbClusterIdentifier;
    }
    public String getDbClusterEndpointResourceIdentifier() { return dbClusterEndpointResourceIdentifier; }
    public void setDbClusterEndpointResourceIdentifier(String dbClusterEndpointResourceIdentifier) {
        this.dbClusterEndpointResourceIdentifier = dbClusterEndpointResourceIdentifier;
    }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEndpointType() { return endpointType; }
    public void setEndpointType(String endpointType) { this.endpointType = endpointType; }
    public String getCustomEndpointType() { return customEndpointType; }
    public void setCustomEndpointType(String customEndpointType) { this.customEndpointType = customEndpointType; }
    public List<String> getStaticMembers() { return staticMembers; }
    public void setStaticMembers(List<String> staticMembers) { this.staticMembers = staticMembers; }
    public List<String> getExcludedMembers() { return excludedMembers; }
    public void setExcludedMembers(List<String> excludedMembers) { this.excludedMembers = excludedMembers; }
    public String getDbClusterEndpointArn() { return dbClusterEndpointArn; }
    public void setDbClusterEndpointArn(String dbClusterEndpointArn) {
        this.dbClusterEndpointArn = dbClusterEndpointArn;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
