package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@code AWS::RDS::DBProxy}. In real AWS a DB Proxy fronts a target DB cluster/instance behind a
 * pooled endpoint. Floci emulates it as a second auth-relay (see {@code RdsProxyManager}) on its own
 * allocated port, forwarding to the registered target's backend container — so the proxy exposes a
 * distinct, reachable endpoint just like the real service.
 */
@RegisterForReflection
public class DbProxy {

    private String dbProxyName;          // physical id + Ref return value
    private String dbProxyArn;           // GetAtt "DBProxyArn"
    private String dbProxyResourceId;    // "prx-XXXX..."
    private String endpointHost;         // proxy endpoint hostname
    private int proxyPort;               // relay port (engine family default, or a pool port if taken)
    private String engineFamily;         // POSTGRESQL | MYSQL | SQLSERVER (as sent by the client)
    private boolean requireTls = true;
    private boolean iamAuth;             // true if any Auth[].IAMAuth == REQUIRED
    private String roleArn;
    private int idleClientTimeout = 1800;
    private boolean debugLogging;
    private String status = "available";
    private Instant createdAt;
    private List<String> vpcSubnetIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private List<DbProxyAuth> auth = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbProxy() {}

    /** The reachable proxy endpoint. AWS RDS Proxy exposes a bare hostname and clients connect on
     *  the engine's default port; Floci mirrors that (the relay listens on the default port), so the
     *  endpoint is a bare host — consumers that split host/port and default to 5432/3306 work as-is. */
    public String getEndpoint() {
        return endpointHost;
    }

    public String getDbProxyName() { return dbProxyName; }
    public void setDbProxyName(String dbProxyName) { this.dbProxyName = dbProxyName; }

    public String getDbProxyArn() { return dbProxyArn; }
    public void setDbProxyArn(String dbProxyArn) { this.dbProxyArn = dbProxyArn; }

    public String getDbProxyResourceId() { return dbProxyResourceId; }
    public void setDbProxyResourceId(String dbProxyResourceId) { this.dbProxyResourceId = dbProxyResourceId; }

    public String getEndpointHost() { return endpointHost; }
    public void setEndpointHost(String endpointHost) { this.endpointHost = endpointHost; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public String getEngineFamily() { return engineFamily; }
    public void setEngineFamily(String engineFamily) { this.engineFamily = engineFamily; }

    public boolean isRequireTls() { return requireTls; }
    public void setRequireTls(boolean requireTls) { this.requireTls = requireTls; }

    public boolean isIamAuth() { return iamAuth; }
    public void setIamAuth(boolean iamAuth) { this.iamAuth = iamAuth; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public int getIdleClientTimeout() { return idleClientTimeout; }
    public void setIdleClientTimeout(int idleClientTimeout) { this.idleClientTimeout = idleClientTimeout; }

    public boolean isDebugLogging() { return debugLogging; }
    public void setDebugLogging(boolean debugLogging) { this.debugLogging = debugLogging; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<String> getVpcSubnetIds() { return vpcSubnetIds; }
    public void setVpcSubnetIds(List<String> vpcSubnetIds) {
        this.vpcSubnetIds = vpcSubnetIds != null ? new ArrayList<>(vpcSubnetIds) : new ArrayList<>();
    }

    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds != null ? new ArrayList<>(vpcSecurityGroupIds) : new ArrayList<>();
    }

    public List<DbProxyAuth> getAuth() { return auth; }
    public void setAuth(List<DbProxyAuth> auth) {
        this.auth = auth != null ? new ArrayList<>(auth) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>(); }
}
