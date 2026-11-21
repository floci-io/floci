package io.floci.aws.services.rds.model;

import io.floci.aws.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The maintenance, network and monitoring settings of a DB cluster as a CreateDBCluster or
 * ModifyDBCluster request carries them. A null member is one the request left out: on create that
 * means the AWS default, on modify it means unchanged.
 */
@RegisterForReflection
public record DbClusterSettings(String preferredBackupWindow,
                                String preferredMaintenanceWindow,
                                List<String> vpcSecurityGroupIds,
                                Integer monitoringInterval,
                                String monitoringRoleArn,
                                Boolean performanceInsightsEnabled,
                                Integer performanceInsightsRetentionPeriod,
                                String databaseInsightsMode) {

    public static final String DATABASE_INSIGHTS_STANDARD = "standard";
    public static final String DATABASE_INSIGHTS_ADVANCED = "advanced";

    public static DbClusterSettings unchanged() {
        return new DbClusterSettings(null, null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return equals(unchanged());
    }

    /**
     * The checks a request's members get on their own, before any is applied: window formats,
     * the monitoring interval, the Performance Insights retention period (all as on a DB instance)
     * and the Database Insights mode.
     */
    public void validate() {
        new DbInstanceSettings(null, null, null, preferredBackupWindow, preferredMaintenanceWindow,
                null, monitoringInterval, monitoringRoleArn, performanceInsightsEnabled,
                performanceInsightsRetentionPeriod, null, null, null, null).validate();
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
