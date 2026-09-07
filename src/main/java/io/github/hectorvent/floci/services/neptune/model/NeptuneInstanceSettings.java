package io.github.hectorvent.floci.services.neptune.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record NeptuneInstanceSettings(String availabilityZone,
                                      Boolean autoMinorVersionUpgrade,
                                      Integer promotionTier,
                                      Boolean publiclyAccessible,
                                      String dbParameterGroupName,
                                      String dbSubnetGroupName,
                                      String preferredBackupWindow,
                                      String preferredMaintenanceWindow) {

    public static NeptuneInstanceSettings defaults() {
        return new NeptuneInstanceSettings(null, null, null, null, null, null, null, null);
    }

    public static NeptuneInstanceSettings unchanged() {
        return defaults();
    }

    public void validate() {
        if (promotionTier != null && (promotionTier < 0 || promotionTier > 15)) {
            throw new AwsException("InvalidParameterValue", "Invalid promotion tier: " + promotionTier
                    + ". Promotion tier must be between 0 and 15.", 400);
        }
        if (preferredBackupWindow != null && !preferredBackupWindow.isBlank()) {
            BackupWindows.parseBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null && !preferredMaintenanceWindow.isBlank()) {
            BackupWindows.parseMaintenanceWindow(preferredMaintenanceWindow);
        }
    }

    public void applyTo(NeptuneInstance instance) {
        if (availabilityZone != null && !availabilityZone.isBlank()) {
            instance.setAvailabilityZone(availabilityZone);
        }
        if (autoMinorVersionUpgrade != null) {
            instance.setAutoMinorVersionUpgrade(autoMinorVersionUpgrade);
        }
        if (promotionTier != null) {
            instance.setPromotionTier(promotionTier);
        }
        if (publiclyAccessible != null) {
            instance.setPubliclyAccessible(publiclyAccessible);
        }
        if (dbParameterGroupName != null && !dbParameterGroupName.isBlank()) {
            instance.setDbParameterGroupName(dbParameterGroupName);
        }
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank()) {
            instance.setDbSubnetGroupName(dbSubnetGroupName);
        }
        if (preferredBackupWindow != null && !preferredBackupWindow.isBlank()) {
            instance.setPreferredBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null && !preferredMaintenanceWindow.isBlank()) {
            instance.setPreferredMaintenanceWindow(BackupWindows.lowerCase(preferredMaintenanceWindow));
        }
    }
}
