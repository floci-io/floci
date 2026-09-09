package io.github.hectorvent.floci.services.elasticache.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The encryption and snapshot settings of a replication group as a request carries them: a null
 * member is one the request left out — the AWS default on create, unchanged on modify.
 */
@RegisterForReflection
public record ReplicationGroupSettings(Boolean atRestEncryptionEnabled,
                                       String kmsKeyId,
                                       Integer snapshotRetentionLimit,
                                       String snapshotWindow) {

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int MINIMUM_WINDOW_MINUTES = 60;

    /** Where AWS picks a random one-hour window, Floci picks this. */
    public static final String DEFAULT_SNAPSHOT_WINDOW = "00:00-01:00";

    public static ReplicationGroupSettings defaults() {
        return new ReplicationGroupSettings(null, null, null, null);
    }

    public static ReplicationGroupSettings unchanged() {
        return defaults();
    }

    /** The checks a live account applies to these parameters, with its wording. */
    public void validate() {
        // an omitted AtRestEncryptionEnabled is false on a live account, and refused the same way
        if (kmsKeyId != null && !kmsKeyId.isBlank() && !Boolean.TRUE.equals(atRestEncryptionEnabled)) {
            throw new AwsException("InvalidParameterCombination",
                    "Please enable encryption at rest to use Customer Managed CMK", 400);
        }
        if (snapshotRetentionLimit != null && (snapshotRetentionLimit < 0 || snapshotRetentionLimit > 35)) {
            throw new AwsException("InvalidParameterValue", "Invalid snapshot retention limit: "
                    + snapshotRetentionLimit + ". Retention limit must be between 0 and 35.", 400);
        }
        if (snapshotWindow != null) {
            parseWindow(snapshotWindow);
        }
    }

    private static void parseWindow(String window) {
        String[] parts = window.split("-", -1);
        if (parts.length != 2) {
            throw invalidWindow(window);
        }
        int start = minuteOfDay(parts[0], window);
        int end = minuteOfDay(parts[1], window);
        // an end before the start wraps midnight; an end equal to the start is an empty window,
        // which a live account refuses as shorter than an hour, not a full day
        if (end < start) {
            end += MINUTES_PER_DAY;
        }
        if (end - start < MINIMUM_WINDOW_MINUTES) {
            throw new AwsException("InvalidParameterValue",
                    "Snapshot window must be at least " + MINIMUM_WINDOW_MINUTES + " minutes.", 400);
        }
    }

    private static int minuteOfDay(String time, String window) {
        Matcher m = TIME.matcher(time);
        if (!m.matches()) {
            throw invalidWindow(window);
        }
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }

    private static AwsException invalidWindow(String window) {
        return new AwsException("InvalidParameterValue", "Invalid backup window format. Should be "
                + "specified as a range hh24:mi-hh24:mi (24H Clock UTC). Example: 03:15-08:15", 400);
    }

    public ReplicationGroupSettings withKmsKeyId(String resolvedKmsKeyId) {
        return new ReplicationGroupSettings(atRestEncryptionEnabled, resolvedKmsKeyId,
                snapshotRetentionLimit, snapshotWindow);
    }

    public void applyTo(ReplicationGroup group) {
        if (atRestEncryptionEnabled != null) {
            group.setAtRestEncryptionEnabled(atRestEncryptionEnabled);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            group.setKmsKeyId(kmsKeyId);
        }
        if (snapshotRetentionLimit != null) {
            group.setSnapshotRetentionLimit(snapshotRetentionLimit);
        }
        if (snapshotWindow != null && !snapshotWindow.isBlank()) {
            group.setSnapshotWindow(snapshotWindow);
        }
    }
}
