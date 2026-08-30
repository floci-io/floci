package io.github.hectorvent.floci.services.rds.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.quarkus.runtime.annotations.RegisterForReflection;


/**
 * The storage and backup settings of a DB instance as a request carries them: a null member is
 * one the request left out. On create that means the AWS default, on modify it means unchanged.
 */
@RegisterForReflection
public record DbInstanceSettings(Boolean storageEncrypted,
                                 String kmsKeyId,
                                 Integer backupRetentionPeriod,
                                 String preferredBackupWindow,
                                 String preferredMaintenanceWindow,
                                 Boolean copyTagsToSnapshot) {

    /** Where AWS picks a random 30-minute window, Floci picks these. */
    public static final String DEFAULT_BACKUP_WINDOW = BackupWindows.DEFAULT_BACKUP_WINDOW;
    public static final String DEFAULT_MAINTENANCE_WINDOW = BackupWindows.DEFAULT_MAINTENANCE_WINDOW;

    public static DbInstanceSettings defaults() {
        return new DbInstanceSettings(null, null, null, null, null, null);
    }

    public static DbInstanceSettings unchanged() {
        return defaults();
    }

    /**
     * The per-parameter checks a live account applies, with its wording. The retention period is
     * not range-checked: AWS accepted 40 days on a postgres instance. Overlap between the windows
     * is checked by the service against the windows that will be in effect, since the counterpart
     * of a window given alone comes from the instance or from a default.
     */
    public void validate() {
        if (kmsKeyId != null && !kmsKeyId.isBlank() && !Boolean.TRUE.equals(storageEncrypted)) {
            throw new AwsException("InvalidParameterCombination",
                    "You must enable StorageEncrypted when you specify KmsKeyId", 400);
        }
        if (preferredBackupWindow != null) {
            BackupWindows.parseBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null) {
            BackupWindows.parseMaintenanceWindow(preferredMaintenanceWindow);
        }
    }

    /** Whether a daily backup window and a weekly maintenance window share any minute. */
    public static boolean windowsOverlap(String backupWindow, String maintenanceWindow) {
        return BackupWindows.overlap(backupWindow, maintenanceWindow);
    }

    /**
     * A 30-minute maintenance window that starts the minute the given daily backup window ends,
     * so it is clear of it on every day — what AWS's random pick achieves when only the backup
     * window is given.
     */
    public static String maintenanceWindowAfter(String backupWindow) {
        return BackupWindows.maintenanceWindowAfter(backupWindow);
    }

    /** A 30-minute daily backup window starting the minute the given maintenance window ends. */
    public static String backupWindowAfter(String maintenanceWindow) {
        return BackupWindows.backupWindowAfter(maintenanceWindow);
    }

    /** A window given alone that leaves no room for the other kind, in AWS's words. */
    public static AwsException noRoomForMaintenanceWindow() {
        return new AwsException("InvalidParameterValue", "The specified backup window overlaps all "
                + "available default maintenance windows. Shrink the backup window or specify a "
                + "non-overlapping maintenance window.", 400);
    }

    public static AwsException noRoomForBackupWindow() {
        return new AwsException("InvalidParameterValue", "The specified maintenance window overlaps "
                + "all available default backup windows. Shrink the maintenance window or specify a "
                + "non-overlapping backup window.", 400);
    }

    public static AwsException overlappingWindows() {
        return BackupWindows.overlapping();
    }

    public DbInstanceSettings withKmsKeyId(String resolvedKmsKeyId) {
        return new DbInstanceSettings(storageEncrypted, resolvedKmsKeyId, backupRetentionPeriod,
                preferredBackupWindow, preferredMaintenanceWindow, copyTagsToSnapshot);
    }

    public void applyTo(DbInstance instance) {
        if (storageEncrypted != null) {
            instance.setStorageEncrypted(storageEncrypted);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            instance.setKmsKeyId(kmsKeyId);
        }
        if (backupRetentionPeriod != null) {
            instance.setBackupRetentionPeriod(backupRetentionPeriod);
        }
        if (preferredBackupWindow != null && !preferredBackupWindow.isBlank()) {
            instance.setPreferredBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null && !preferredMaintenanceWindow.isBlank()) {
            instance.setPreferredMaintenanceWindow(preferredMaintenanceWindow.toLowerCase());
        }
        if (copyTagsToSnapshot != null) {
            instance.setCopyTagsToSnapshot(copyTagsToSnapshot);
        }
    }
}
