package io.github.hectorvent.floci.services.docdb.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The placement, encryption and backup settings of a DocumentDB cluster as a request carries
 * them: a null member is one the request left out — the AWS default on create, unchanged on modify.
 */
@RegisterForReflection
public record DocDbClusterSettings(String dbSubnetGroupName,
                                   String dbClusterParameterGroupName,
                                   List<String> vpcSecurityGroupIds,
                                   Boolean storageEncrypted,
                                   String kmsKeyId,
                                   Integer backupRetentionPeriod,
                                   String preferredBackupWindow,
                                   String preferredMaintenanceWindow,
                                   Boolean deletionProtection) {

    public static DocDbClusterSettings defaults() {
        return new DocDbClusterSettings(null, null, null, null, null, null, null, null, null);
    }

    public static DocDbClusterSettings unchanged() {
        return defaults();
    }

    /** The per-parameter checks a live account applies, with its wording. */
    public void validate() {
        if (kmsKeyId != null && !kmsKeyId.isBlank() && !Boolean.TRUE.equals(storageEncrypted)) {
            throw new AwsException("InvalidParameterCombination",
                    "You cannot specify KMS key for unencrypted clusters.", 400);
        }
        if (backupRetentionPeriod != null && (backupRetentionPeriod < 1 || backupRetentionPeriod > 354)) {
            throw new AwsException("InvalidParameterValue", "Invalid backup retention period: "
                    + backupRetentionPeriod + ". Retention period must be between 1 and 354.", 400);
        }
        if (preferredBackupWindow != null) {
            BackupWindows.parseBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null) {
            BackupWindows.parseMaintenanceWindow(preferredMaintenanceWindow);
        }
    }

    public DocDbClusterSettings with(String resolvedKmsKeyId, String backupWindow, String maintenanceWindow) {
        return new DocDbClusterSettings(dbSubnetGroupName, dbClusterParameterGroupName, vpcSecurityGroupIds,
                storageEncrypted, resolvedKmsKeyId, backupRetentionPeriod, backupWindow, maintenanceWindow,
                deletionProtection);
    }

    public void applyTo(DocDbCluster cluster) {
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank()) {
            cluster.setDbSubnetGroupName(dbSubnetGroupName);
        }
        if (dbClusterParameterGroupName != null && !dbClusterParameterGroupName.isBlank()) {
            cluster.setDbClusterParameterGroupName(dbClusterParameterGroupName);
        }
        if (vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty()) {
            cluster.setVpcSecurityGroupIds(List.copyOf(vpcSecurityGroupIds));
        }
        if (storageEncrypted != null) {
            cluster.setStorageEncrypted(storageEncrypted);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            cluster.setKmsKeyId(kmsKeyId);
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
        if (deletionProtection != null) {
            cluster.setDeletionProtection(deletionProtection);
        }
    }
}
