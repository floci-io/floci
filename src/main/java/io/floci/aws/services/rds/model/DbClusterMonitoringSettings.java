package io.floci.aws.services.rds.model;

import io.floci.aws.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Enhanced Monitoring, Performance Insights and Database Insights members of a CreateDBCluster
 * or ModifyDBCluster request. A null member is one the request left out: on create that means the
 * AWS default, on modify it means unchanged.
 */
@RegisterForReflection
public record DbClusterMonitoringSettings(Integer monitoringInterval,
                                          String monitoringRoleArn,
                                          Boolean performanceInsightsEnabled,
                                          Integer performanceInsightsRetentionPeriod,
                                          String databaseInsightsMode) {

    public static final String DATABASE_INSIGHTS_STANDARD = "standard";
    public static final String DATABASE_INSIGHTS_ADVANCED = "advanced";

    public boolean isEmpty() {
        return monitoringInterval == null && monitoringRoleArn == null && performanceInsightsEnabled == null
                && performanceInsightsRetentionPeriod == null && databaseInsightsMode == null;
    }

    /**
     * The checks the members get on their own, before any is applied: the monitoring interval and
     * the Performance Insights retention period as on a DB instance, and the Database Insights mode.
     */
    public void validate() {
        new DbInstanceSettings(null, null, null, null, null, null, monitoringInterval, monitoringRoleArn,
                performanceInsightsEnabled, performanceInsightsRetentionPeriod, null, null, null, null).validate();
        if (databaseInsightsMode != null
                && !DATABASE_INSIGHTS_STANDARD.equals(databaseInsightsMode)
                && !DATABASE_INSIGHTS_ADVANCED.equals(databaseInsightsMode)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid database insights mode: " + databaseInsightsMode
                            + ". Valid values are " + DATABASE_INSIGHTS_STANDARD + ", "
                            + DATABASE_INSIGHTS_ADVANCED + ".", 400);
        }
    }
}
