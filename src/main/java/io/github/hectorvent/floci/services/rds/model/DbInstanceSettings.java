package io.github.hectorvent.floci.services.rds.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Set;


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
                                 Boolean copyTagsToSnapshot,
                                 Integer monitoringInterval,
                                 String monitoringRoleArn,
                                 Boolean performanceInsightsEnabled,
                                 Integer performanceInsightsRetentionPeriod,
                                 String engineLifecycleSupport,
                                 List<String> enabledCloudwatchLogsExports,
                                 Integer maxAllocatedStorage) {

    /** The settings a caller that touches none of the monitoring members gives. */
    public DbInstanceSettings(Boolean storageEncrypted,
                              String kmsKeyId,
                              Integer backupRetentionPeriod,
                              String preferredBackupWindow,
                              String preferredMaintenanceWindow,
                              Boolean copyTagsToSnapshot) {
        this(storageEncrypted, kmsKeyId, backupRetentionPeriod, preferredBackupWindow,
                preferredMaintenanceWindow, copyTagsToSnapshot, null, null, null, null, null,
                null, null);
    }

    /** MonitoringInterval documents these as its valid values. */
    private static final Set<Integer> MONITORING_INTERVALS = Set.of(0, 1, 5, 10, 15, 30, 60);

    public static final String ENGINE_LIFECYCLE_SUPPORT_ENABLED = "open-source-rds-extended-support";
    public static final String ENGINE_LIFECYCLE_SUPPORT_DISABLED =
            "open-source-rds-extended-support-disabled";

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
        if (monitoringInterval != null && !MONITORING_INTERVALS.contains(monitoringInterval)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid monitoring interval: " + monitoringInterval
                            + ". Valid values are 0, 1, 5, 10, 15, 30, 60.", 400);
        }
        if (performanceInsightsRetentionPeriod != null
                && !validPerformanceInsightsRetention(performanceInsightsRetentionPeriod)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid Performance Insights retention period: "
                            + performanceInsightsRetentionPeriod
                            + ". Valid values are 7, 731, or a multiple of 31 up to 713.", 400);
        }
        if (engineLifecycleSupport != null
                && !ENGINE_LIFECYCLE_SUPPORT_ENABLED.equals(engineLifecycleSupport)
                && !ENGINE_LIFECYCLE_SUPPORT_DISABLED.equals(engineLifecycleSupport)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid engine lifecycle support: " + engineLifecycleSupport
                            + ". Valid values are " + ENGINE_LIFECYCLE_SUPPORT_ENABLED + ", "
                            + ENGINE_LIFECYCLE_SUPPORT_DISABLED + ".", 400);
        }
    }

    /**
     * 7 days, 731 days, or month * 31 for a whole number of months from 1 to 23, which is what
     * the member documents. A period outside that set, 94 for instance, is an error on AWS.
     */
    private static boolean validPerformanceInsightsRetention(int days) {
        return days == 7 || days == 731 || (days % 31 == 0 && days / 31 >= 1 && days / 31 <= 23);
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

    /** The same settings with the windows the service resolved, leaving every other member alone. */
    public DbInstanceSettings withWindows(String backupWindow, String maintenanceWindow) {
        return new DbInstanceSettings(storageEncrypted, kmsKeyId, backupRetentionPeriod,
                backupWindow, maintenanceWindow, copyTagsToSnapshot,
                monitoringInterval, monitoringRoleArn, performanceInsightsEnabled,
                performanceInsightsRetentionPeriod, engineLifecycleSupport,
                enabledCloudwatchLogsExports, maxAllocatedStorage);
    }

    public DbInstanceSettings withKmsKeyId(String resolvedKmsKeyId) {
        return new DbInstanceSettings(storageEncrypted, resolvedKmsKeyId, backupRetentionPeriod,
                preferredBackupWindow, preferredMaintenanceWindow, copyTagsToSnapshot,
                monitoringInterval, monitoringRoleArn, performanceInsightsEnabled,
                performanceInsightsRetentionPeriod, engineLifecycleSupport,
                enabledCloudwatchLogsExports, maxAllocatedStorage);
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
        if (monitoringInterval != null) {
            instance.setMonitoringInterval(monitoringInterval);
        }
        if (monitoringRoleArn != null && !monitoringRoleArn.isBlank()) {
            instance.setMonitoringRoleArn(monitoringRoleArn);
        }
        if (performanceInsightsEnabled != null) {
            instance.setPerformanceInsightsEnabled(performanceInsightsEnabled);
        }
        if (performanceInsightsRetentionPeriod != null) {
            instance.setPerformanceInsightsRetentionPeriod(performanceInsightsRetentionPeriod);
        }
        if (engineLifecycleSupport != null && !engineLifecycleSupport.isBlank()) {
            instance.setEngineLifecycleSupport(engineLifecycleSupport);
        }
        if (enabledCloudwatchLogsExports != null) {
            instance.setEnabledCloudwatchLogsExports(List.copyOf(enabledCloudwatchLogsExports));
        }
        if (maxAllocatedStorage != null) {
            instance.setMaxAllocatedStorage(maxAllocatedStorage);
        }
    }
}
