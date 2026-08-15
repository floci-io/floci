package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A registered target of an {@code AWS::RDS::DBProxyTargetGroup} (a DB cluster or instance). */
@RegisterForReflection
public class DbProxyTarget {

    private String type;            // "TRACKED_CLUSTER" | "RDS_INSTANCE"
    private String rdsResourceId;   // the cluster/instance identifier
    private String targetArn;
    private String endpoint;        // target backend host
    private int port;
    private String targetHealth = "AVAILABLE";

    public DbProxyTarget() {}

    public DbProxyTarget(String type, String rdsResourceId, String targetArn, String endpoint, int port) {
        this.type = type;
        this.rdsResourceId = rdsResourceId;
        this.targetArn = targetArn;
        this.endpoint = endpoint;
        this.port = port;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRdsResourceId() { return rdsResourceId; }
    public void setRdsResourceId(String rdsResourceId) { this.rdsResourceId = rdsResourceId; }

    public String getTargetArn() { return targetArn; }
    public void setTargetArn(String targetArn) { this.targetArn = targetArn; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getTargetHealth() { return targetHealth; }
    public void setTargetHealth(String targetHealth) { this.targetHealth = targetHealth; }
}
