package io.github.hectorvent.floci.services.docdb.model;

import io.github.hectorvent.floci.core.common.BackupWindows;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The settings of a DocumentDB instance as a request carries them; null means left out. */
@RegisterForReflection
public record DocDbInstanceSettings(Boolean autoMinorVersionUpgrade,
                                    String preferredMaintenanceWindow,
                                    Boolean copyTagsToSnapshot,
                                    Integer promotionTier) {

    public static DocDbInstanceSettings defaults() {
        return new DocDbInstanceSettings(null, null, null, null);
    }

    public void validate() {
        if (preferredMaintenanceWindow != null) {
            BackupWindows.parseMaintenanceWindow(preferredMaintenanceWindow);
        }
    }

    public void applyTo(DocDbInstance instance) {
        if (autoMinorVersionUpgrade != null) {
            instance.setAutoMinorVersionUpgrade(autoMinorVersionUpgrade);
        }
        if (preferredMaintenanceWindow != null && !preferredMaintenanceWindow.isBlank()) {
            instance.setPreferredMaintenanceWindow(BackupWindows.lowerCase(preferredMaintenanceWindow));
        }
        if (copyTagsToSnapshot != null) {
            instance.setCopyTagsToSnapshot(copyTagsToSnapshot);
        }
        if (promotionTier != null) {
            instance.setPromotionTier(promotionTier);
        }
    }
}
