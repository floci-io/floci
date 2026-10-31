package io.github.hectorvent.floci.services.redshift;

import java.util.List;
import java.util.Set;

/**
 * The event IDs Floci emits, with the source type, category and severity AWS documents for each
 * (Amazon Redshift provisioned cluster event notifications). Events AWS raises for conditions the
 * emulator has no equivalent of, such as hardware failure or maintenance windows, are not listed.
 */
final class RedshiftEventCatalog {

    static final String CLUSTER = "cluster";
    static final String PARAMETER_GROUP = "cluster-parameter-group";
    static final String SECURITY_GROUP = "cluster-security-group";
    static final String SNAPSHOT = "cluster-snapshot";
    static final String SCHEDULED_ACTION = "scheduled-action";

    static final Set<String> SOURCE_TYPES =
            Set.of(CLUSTER, PARAMETER_GROUP, SECURITY_GROUP, SNAPSHOT, SCHEDULED_ACTION);
    static final Set<String> CATEGORIES = Set.of("configuration", "management", "monitoring", "security", "pending");
    static final Set<String> SEVERITIES = Set.of("INFO", "ERROR");

    record EventInfo(String id, String sourceType, String category, String severity, String description) { }

    static final EventInfo CLUSTER_PARAMETER_GROUP_CHANGED = new EventInfo("REDSHIFT-EVENT-1001", CLUSTER,
            "configuration", "INFO",
            "Your Amazon Redshift cluster [cluster name] was modified to use parameter group [parameter group name] at [time].");
    static final EventInfo PARAMETER_GROUP_CREATED = new EventInfo("REDSHIFT-EVENT-1003", PARAMETER_GROUP,
            "configuration", "INFO", "Cluster parameter group [group name] was created.");
    static final EventInfo PARAMETER_GROUP_DELETED = new EventInfo("REDSHIFT-EVENT-1004", PARAMETER_GROUP,
            "configuration", "INFO", "Cluster parameter group [group name] was deleted.");
    static final EventInfo PARAMETER_GROUP_UPDATED = new EventInfo("REDSHIFT-EVENT-1005", PARAMETER_GROUP,
            "configuration", "INFO", "Cluster parameter group [name] was updated at [time].");
    static final EventInfo CLUSTER_CREATED = new EventInfo("REDSHIFT-EVENT-2000", CLUSTER, "management", "INFO",
            "Your Amazon Redshift cluster: [cluster name] has been created and is ready for use.");
    static final EventInfo CLUSTER_DELETED = new EventInfo("REDSHIFT-EVENT-2001", CLUSTER, "management", "INFO",
            "Your Amazon Redshift cluster [cluster name] was deleted at [time]. A final snapshot [was / was not] saved.");
    static final EventInfo CLUSTER_SECURITY_GROUPS_UPDATED = new EventInfo("REDSHIFT-EVENT-2002", CLUSTER,
            "management", "INFO", "VPC security groups for cluster [cluster name] updated at [time in UTC].");
    static final EventInfo CLUSTER_RESTORE_STARTED = new EventInfo("REDSHIFT-EVENT-2008", CLUSTER, "management",
            "INFO", "Your restore operation to create a new Amazon Redshift cluster [cluster name] snapshot "
                    + "[snapshot name] was started at [time].");
    static final EventInfo SNAPSHOT_STARTED = new EventInfo("REDSHIFT-EVENT-2009", SNAPSHOT, "management", "INFO",
            "A user snapshot [snapshot name] for Amazon Redshift Cluster [cluster name] started at [time].");
    static final EventInfo SNAPSHOT_DELETED = new EventInfo("REDSHIFT-EVENT-2011", SNAPSHOT, "management", "INFO",
            "The user snapshot [snapshot name] for Amazon Redshift cluster [cluster name] was deleted at [time].");
    static final EventInfo FINAL_SNAPSHOT_STARTED = new EventInfo("REDSHIFT-EVENT-2012", SNAPSHOT, "management",
            "INFO", "The final snapshot [snapshot name] for Amazon Redshift cluster [cluster name] was started at [time].");
    static final EventInfo CLUSTER_REBOOTED = new EventInfo("REDSHIFT-EVENT-3000", CLUSTER, "monitoring", "INFO",
            "Your Amazon Redshift cluster [cluster name] was rebooted at [time].");
    static final EventInfo CLUSTER_RESTORED = new EventInfo("REDSHIFT-EVENT-3003", CLUSTER, "monitoring", "INFO",
            "Amazon Redshift cluster [cluster name] was successfully created from snapshot [snapshot name] "
                    + "and is available for use.");
    static final EventInfo SNAPSHOT_COMPLETED = new EventInfo("REDSHIFT-EVENT-3004", SNAPSHOT, "monitoring", "INFO",
            "The user snapshot [snapshot name] for your Amazon Redshift cluster [cluster name] completed "
                    + "successfully at [time].");
    static final EventInfo FINAL_SNAPSHOT_COMPLETED = new EventInfo("REDSHIFT-EVENT-3005", SNAPSHOT, "monitoring",
            "INFO", "The final snapshot [name] for Amazon Redshift cluster [name] completed successfully at [time].");
    static final EventInfo FINAL_SNAPSHOT_FAILED = new EventInfo("REDSHIFT-EVENT-3502", SNAPSHOT, "monitoring",
            "ERROR", "The final snapshot [snapshot name] for Amazon Redshift cluster [cluster name] failed at [time].");
    static final EventInfo CLUSTER_RESTORE_FAILED = new EventInfo("REDSHIFT-EVENT-3501", CLUSTER, "monitoring",
            "ERROR", "The restore operation to create Amazon Redshift cluster [cluster name] failed at [time].");
    static final EventInfo SNAPSHOT_FAILED = new EventInfo("REDSHIFT-EVENT-3503", SNAPSHOT, "monitoring", "ERROR",
            "The user snapshot [snapshot name] for your Amazon Redshift cluster [cluster name] failed at [time].");
    static final EventInfo ADMIN_CREDENTIALS_UPDATED = new EventInfo("REDSHIFT-EVENT-4000", CLUSTER, "security",
            "INFO", "Your admin credentials for your Amazon Redshift cluster: [cluster name] were updated at [time].");

    static final List<EventInfo> ALL = List.of(CLUSTER_PARAMETER_GROUP_CHANGED, PARAMETER_GROUP_CREATED,
            PARAMETER_GROUP_DELETED, PARAMETER_GROUP_UPDATED, CLUSTER_CREATED, CLUSTER_DELETED,
            CLUSTER_SECURITY_GROUPS_UPDATED, CLUSTER_RESTORE_STARTED, SNAPSHOT_STARTED, SNAPSHOT_DELETED,
            FINAL_SNAPSHOT_STARTED, CLUSTER_REBOOTED, CLUSTER_RESTORED, SNAPSHOT_COMPLETED,
            FINAL_SNAPSHOT_COMPLETED, CLUSTER_RESTORE_FAILED, FINAL_SNAPSHOT_FAILED, SNAPSHOT_FAILED,
            ADMIN_CREDENTIALS_UPDATED);

    private RedshiftEventCatalog() { }
}
