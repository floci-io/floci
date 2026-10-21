package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The RestoreDBInstanceToPointInTime request as the service consumes it. The source is named by
 * identifier, DbiResourceId or automated backups ARN; the time by RestoreTime or
 * UseLatestRestorableTime. A null override means the API reference's default: the source's
 * instance class, and the Region's defaults for everything else.
 */
@RegisterForReflection
public record PointInTimeRestoreRequest(String targetDbInstanceIdentifier,
                                        String sourceDbInstanceIdentifier,
                                        String sourceDbiResourceId,
                                        String sourceDbInstanceAutomatedBackupsArn,
                                        Instant restoreTime,
                                        Boolean useLatestRestorableTime,
                                        String dbInstanceClass,
                                        Integer port,
                                        String availabilityZone,
                                        Boolean multiAz,
                                        Boolean autoMinorVersionUpgrade,
                                        String dbName,
                                        String optionGroupName,
                                        String dbParameterGroupName,
                                        Boolean publiclyAccessible,
                                        String dbSubnetGroupName,
                                        List<String> vpcSecurityGroupIds,
                                        Boolean copyTagsToSnapshot,
                                        Boolean iamDatabaseAuthenticationEnabled,
                                        Integer backupRetentionPeriod,
                                        String preferredBackupWindow,
                                        Boolean deletionProtection,
                                        Map<String, String> tags,
                                        String storageType,
                                        String caCertificateIdentifier) {
    public PointInTimeRestoreRequest(String targetDbInstanceIdentifier,
            String sourceDbInstanceIdentifier,
            String sourceDbiResourceId,
            String sourceDbInstanceAutomatedBackupsArn,
            Instant restoreTime,
            Boolean useLatestRestorableTime,
            String dbInstanceClass,
            Integer port,
            String availabilityZone,
            Boolean multiAz,
            Boolean autoMinorVersionUpgrade,
            String dbName,
            String optionGroupName,
            String dbParameterGroupName,
            Boolean publiclyAccessible,
            String dbSubnetGroupName,
            List<String> vpcSecurityGroupIds,
            Boolean copyTagsToSnapshot,
            Boolean iamDatabaseAuthenticationEnabled,
            Integer backupRetentionPeriod,
            String preferredBackupWindow,
            Boolean deletionProtection,
            Map<String, String> tags,
            String storageType) {
        this(targetDbInstanceIdentifier, sourceDbInstanceIdentifier, sourceDbiResourceId,
                sourceDbInstanceAutomatedBackupsArn, restoreTime, useLatestRestorableTime, dbInstanceClass,
                port, availabilityZone, multiAz, autoMinorVersionUpgrade, dbName, optionGroupName,
                dbParameterGroupName, publiclyAccessible, dbSubnetGroupName, vpcSecurityGroupIds,
                copyTagsToSnapshot, iamDatabaseAuthenticationEnabled, backupRetentionPeriod,
                preferredBackupWindow, deletionProtection, tags, storageType, null);
    }


    public PointInTimeRestoreRequest(String targetDbInstanceIdentifier,
            String sourceDbInstanceIdentifier,
            String sourceDbiResourceId,
            String sourceDbInstanceAutomatedBackupsArn,
            Instant restoreTime,
            Boolean useLatestRestorableTime,
            String dbInstanceClass,
            Integer port,
            String availabilityZone,
            Boolean multiAz,
            Boolean autoMinorVersionUpgrade,
            String dbName,
            String optionGroupName,
            String dbParameterGroupName,
            Boolean publiclyAccessible,
            String dbSubnetGroupName,
            List<String> vpcSecurityGroupIds,
            Boolean copyTagsToSnapshot,
            Boolean iamDatabaseAuthenticationEnabled,
            Integer backupRetentionPeriod,
            String preferredBackupWindow,
            Boolean deletionProtection,
            Map<String, String> tags) {
        this(targetDbInstanceIdentifier, sourceDbInstanceIdentifier, sourceDbiResourceId,
                sourceDbInstanceAutomatedBackupsArn, restoreTime, useLatestRestorableTime, dbInstanceClass,
                port, availabilityZone, multiAz, autoMinorVersionUpgrade, dbName, optionGroupName,
                dbParameterGroupName, publiclyAccessible, dbSubnetGroupName, vpcSecurityGroupIds,
                copyTagsToSnapshot, iamDatabaseAuthenticationEnabled, backupRetentionPeriod,
                preferredBackupWindow, deletionProtection, tags, null, null);
    }
}
