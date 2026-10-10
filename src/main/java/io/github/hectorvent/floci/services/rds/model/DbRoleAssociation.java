package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An IAM role associated with a DB cluster or DB instance, reported as a {@code DBClusterRole} or
 * {@code DBInstanceRole} in {@code AssociatedRoles}. The feature name is optional for a cluster
 * and required for an instance. The status is {@code ACTIVE} from the moment the role is added.
 */
@RegisterForReflection
public class DbRoleAssociation {

    public static final String ACTIVE = "ACTIVE";

    private String roleArn;
    private String featureName;
    private String status;

    public DbRoleAssociation() {}

    public DbRoleAssociation(String roleArn, String featureName) {
        this.roleArn = roleArn;
        this.featureName = featureName;
        this.status = ACTIVE;
    }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
