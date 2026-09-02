package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Cluster {
    private String clusterIdentifier;
    private String nodeType;
    private String masterUsername;
    private String masterPassword;
    private String clusterStatus;
    private Endpoint endpoint;
    private String clusterSubnetGroupName;
    private String clusterParameterGroupName;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String clusterIdentifier) { this.clusterIdentifier = clusterIdentifier; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }
    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String masterPassword) { this.masterPassword = masterPassword; }
    public String getClusterStatus() { return clusterStatus; }
    public void setClusterStatus(String clusterStatus) { this.clusterStatus = clusterStatus; }
    public Endpoint getEndpoint() { return endpoint; }
    public void setEndpoint(Endpoint endpoint) { this.endpoint = endpoint; }
    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) { this.clusterSubnetGroupName = clusterSubnetGroupName; }
    public String getClusterParameterGroupName() { return clusterParameterGroupName; }
    public void setClusterParameterGroupName(String clusterParameterGroupName) { this.clusterParameterGroupName = clusterParameterGroupName; }
    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) { this.vpcSecurityGroupIds = vpcSecurityGroupIds; }

    // Real backend address of this cluster's PostgreSQL container. `endpoint` now points at the
    // auth proxy, not the container, so the container address is kept here for proxy wiring and
    // for restarting the proxy after a reboot or an adopt-on-startup.
    private String containerHost = null;
    private int containerPort = 0;

    // Host port the cluster's auth proxy binds. Kept stable across reboot and adopt so the
    // advertised endpoint does not change.
    private int proxyPort = 0;

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }
    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    private Map<String, String> tags = new LinkedHashMap<>();

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
