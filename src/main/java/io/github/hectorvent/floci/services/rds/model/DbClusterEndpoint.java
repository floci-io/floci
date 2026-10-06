package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Aurora cluster endpoint as DescribeDBClusterEndpoints reports it. Custom endpoints are stored;
 * a cluster's built-in WRITER and READER endpoints are built from the cluster when described and
 * carry no identifier, ARN or custom type.
 */
@RegisterForReflection
public class DbClusterEndpoint {

    private String dbClusterEndpointIdentifier;
    private String dbClusterIdentifier;
    private String dbClusterEndpointResourceIdentifier;
    private String endpoint;
    private String status;
    /** WRITER, READER or CUSTOM. */
    private String endpointType;
    /** READER or ANY for a custom endpoint; null for a built-in one. */
    private String customEndpointType;
    private List<String> staticMembers = new ArrayList<>();
    private List<String> excludedMembers = new ArrayList<>();
    private String dbClusterEndpointArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbClusterEndpoint() {}

    public String getDbClusterEndpointIdentifier() { return dbClusterEndpointIdentifier; }
    public void setDbClusterEndpointIdentifier(String dbClusterEndpointIdentifier) { this.dbClusterEndpointIdentifier = dbClusterEndpointIdentifier; }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public String getDbClusterEndpointResourceIdentifier() { return dbClusterEndpointResourceIdentifier; }
    public void setDbClusterEndpointResourceIdentifier(String dbClusterEndpointResourceIdentifier) { this.dbClusterEndpointResourceIdentifier = dbClusterEndpointResourceIdentifier; }

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
    public void setDbClusterEndpointArn(String dbClusterEndpointArn) { this.dbClusterEndpointArn = dbClusterEndpointArn; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
