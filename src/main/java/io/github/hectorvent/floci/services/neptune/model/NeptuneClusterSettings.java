package io.github.hectorvent.floci.services.neptune.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The settings of a Neptune cluster as a request carries them: a null member is one the request
 * left out, which means the AWS default on create and no change on modify.
 */
@RegisterForReflection
public record NeptuneClusterSettings(List<String> availabilityZones,
                                     Integer port,
                                     Integer backupRetentionPeriod,
                                     String preferredBackupWindow,
                                     String preferredMaintenanceWindow,
                                     String dbSubnetGroupName,
                                     String dbClusterParameterGroupName,
                                     List<String> vpcSecurityGroupIds,
                                     Boolean storageEncrypted,
                                     String kmsKeyId,
                                     String storageType,
                                     Boolean deletionProtection,
                                     Boolean copyTagsToSnapshot,
                                     List<String> enableLogTypes,
                                     List<String> disableLogTypes,
                                     String replicationSourceIdentifier,
                                     Double serverlessV2MinCapacity,
                                     Double serverlessV2MaxCapacity) {

    public static final Set<String> STORAGE_TYPES = Set.of("standard", "iopt1");
    public static final Set<String> LOG_TYPES = Set.of("audit", "slowquery");

    public static NeptuneClusterSettings defaults() {
        return new NeptuneClusterSettings(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public static NeptuneClusterSettings unchanged() {
        return defaults();
    }

    public void validate() {
        if (kmsKeyId != null && !kmsKeyId.isBlank() && !Boolean.TRUE.equals(storageEncrypted)) {
            throw new AwsException("InvalidParameterCombination",
                    "You cannot specify KMS key for unencrypted clusters.", 400);
        }
        if (port != null && (port < 1150 || port > 65535)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid port: " + port + ". Port must be between 1150 and 65535.", 400);
        }
        if (backupRetentionPeriod != null && (backupRetentionPeriod < 1 || backupRetentionPeriod > 35)) {
            throw new AwsException("InvalidParameterValue", "Invalid backup retention period: "
                    + backupRetentionPeriod + ". Retention period must be between 1 and 35.", 400);
        }
        if (preferredBackupWindow != null) {
            BackupWindows.parseBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null) {
            BackupWindows.parseMaintenanceWindow(preferredMaintenanceWindow);
        }
        if (storageType != null && !storageType.isBlank() && !STORAGE_TYPES.contains(storageType)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid storage type: " + storageType + ". Valid values are standard and iopt1.", 400);
        }
        if (serverlessV2MinCapacity != null && serverlessV2MaxCapacity != null
                && serverlessV2MinCapacity > serverlessV2MaxCapacity) {
            throw new AwsException("InvalidParameterCombination",
                    "ServerlessV2ScalingConfiguration MinCapacity must not exceed MaxCapacity.", 400);
        }
        requireKnownLogTypes(enableLogTypes);
        requireKnownLogTypes(disableLogTypes);
    }

    private static void requireKnownLogTypes(List<String> logTypes) {
        if (logTypes == null) {
            return;
        }
        for (String logType : logTypes) {
            if (!LOG_TYPES.contains(logType)) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid log type: " + logType + ". Valid values are audit and slowquery.", 400);
            }
        }
    }

    public void applyTo(NeptuneCluster cluster) {
        if (availabilityZones != null && !availabilityZones.isEmpty()) {
            cluster.setAvailabilityZones(availabilityZones);
        }
        if (backupRetentionPeriod != null) {
            cluster.setBackupRetentionPeriod(backupRetentionPeriod);
        }
        if (preferredBackupWindow != null && !preferredBackupWindow.isBlank()) {
            cluster.setPreferredBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null && !preferredMaintenanceWindow.isBlank()) {
            cluster.setPreferredMaintenanceWindow(BackupWindows.lowerCase(preferredMaintenanceWindow));
        }
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank()) {
            cluster.setDbSubnetGroupName(dbSubnetGroupName);
        }
        if (dbClusterParameterGroupName != null && !dbClusterParameterGroupName.isBlank()) {
            cluster.setDbClusterParameterGroupName(dbClusterParameterGroupName);
        }
        if (vpcSecurityGroupIds != null) {
            cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }
        if (storageEncrypted != null) {
            cluster.setStorageEncrypted(storageEncrypted);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            cluster.setKmsKeyId(kmsKeyId);
        }
        if (storageType != null && !storageType.isBlank()) {
            cluster.setStorageType(storageType);
        }
        if (deletionProtection != null) {
            cluster.setDeletionProtection(deletionProtection);
        }
        if (copyTagsToSnapshot != null) {
            cluster.setCopyTagsToSnapshot(copyTagsToSnapshot);
        }
        if (enableLogTypes != null || disableLogTypes != null) {
            Set<String> logTypes = new LinkedHashSet<>(cluster.getEnabledCloudwatchLogsExports());
            if (disableLogTypes != null) {
                logTypes.removeAll(disableLogTypes);
            }
            if (enableLogTypes != null) {
                logTypes.addAll(enableLogTypes);
            }
            cluster.setEnabledCloudwatchLogsExports(new ArrayList<>(logTypes));
        }
        if (replicationSourceIdentifier != null && !replicationSourceIdentifier.isBlank()) {
            cluster.setReplicationSourceIdentifier(replicationSourceIdentifier);
        }
        if (serverlessV2MinCapacity != null) {
            cluster.setServerlessV2MinCapacity(serverlessV2MinCapacity);
        }
        if (serverlessV2MaxCapacity != null) {
            cluster.setServerlessV2MaxCapacity(serverlessV2MaxCapacity);
        }
    }
}
