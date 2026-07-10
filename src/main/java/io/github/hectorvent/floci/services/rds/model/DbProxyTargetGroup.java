package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The (single, always named {@code "default"}) target group of an {@code AWS::RDS::DBProxy}. Holds
 * the registered targets and the connection-pool configuration.
 */
@RegisterForReflection
public class DbProxyTargetGroup {

    private String dbProxyName;             // owner + physical id
    private String targetGroupName = "default";
    private String targetGroupArn;
    private String status = "available";
    private int maxConnectionsPercent = 100;
    private int maxIdleConnectionsPercent = 50;
    private List<DbProxyTarget> targets = new ArrayList<>();

    public DbProxyTargetGroup() {}

    public String getDbProxyName() { return dbProxyName; }
    public void setDbProxyName(String dbProxyName) { this.dbProxyName = dbProxyName; }

    public String getTargetGroupName() { return targetGroupName; }
    public void setTargetGroupName(String targetGroupName) { this.targetGroupName = targetGroupName; }

    public String getTargetGroupArn() { return targetGroupArn; }
    public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getMaxConnectionsPercent() { return maxConnectionsPercent; }
    public void setMaxConnectionsPercent(int maxConnectionsPercent) { this.maxConnectionsPercent = maxConnectionsPercent; }

    public int getMaxIdleConnectionsPercent() { return maxIdleConnectionsPercent; }
    public void setMaxIdleConnectionsPercent(int maxIdleConnectionsPercent) { this.maxIdleConnectionsPercent = maxIdleConnectionsPercent; }

    public List<DbProxyTarget> getTargets() { return targets; }
    public void setTargets(List<DbProxyTarget> targets) {
        this.targets = targets != null ? new ArrayList<>(targets) : new ArrayList<>();
    }
}
