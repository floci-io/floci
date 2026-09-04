package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSettings;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstanceSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class NeptuneQueryHandler {

    private static final Logger LOG = Logger.getLogger(NeptuneQueryHandler.class);

    private final NeptuneService service;
    private final EmulatorConfig config;

    @Inject
    public NeptuneQueryHandler(NeptuneService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.infov("Neptune action: {0}", action);
        try {
            return switch (action) {
                case "CreateDBCluster"    -> handleCreateDbCluster(params);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params);
                case "DeleteDBCluster"    -> handleDeleteDbCluster(params);
                case "ModifyDBCluster"    -> handleModifyDbCluster(params);
                case "AddRoleToDBCluster" -> handleAddRoleToDbCluster(params);
                case "RemoveRoleFromDBCluster" -> handleRemoveRoleFromDbCluster(params);
                case "DescribeGlobalClusters" -> handleDescribeGlobalClusters(params);
                case "CreateDBInstance"   -> handleCreateDbInstance(params);
                case "DescribeDBInstances"-> handleDescribeDbInstances(params);
                case "DeleteDBInstance"   -> handleDeleteDbInstance(params);
                case "ModifyDBInstance"   -> handleModifyDbInstance(params);
                case "ListTagsForResource" -> handleListTagsForResource(params);
                case "AddTagsToResource"   -> handleAddTagsToResource(params);
                case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(params);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported by Neptune.", AwsNamespaces.RDS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in Neptune {0}", action);
            return Response.serverError().entity("Unexpected error: " + e.getMessage()).build();
        }
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    private Response handleCreateDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String engineVersion = params.getFirst("EngineVersion");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));

        NeptuneCluster cluster = service.createDbCluster(id, engineVersion, iamEnabled,
                clusterSettings(params, true), parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    private Response handleDescribeDbClusters(MultivaluedMap<String, String> params) {
        String identifier = params.getFirst("DBClusterIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractFilterValue(params, "db-cluster-id");
        }

        // AWS parity: the DBClusterIdentifier parameter faults with
        // DBClusterNotFoundFault when no cluster matches, while the
        // db-cluster-id Filters form returns an empty list.
        if (identifier != null && !identifier.isBlank()) {
            service.getDbCluster(identifier); // throws DBClusterNotFoundFault if absent
        }

        Collection<NeptuneCluster> result = service.listDbClusters(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBClusters");
        for (NeptuneCluster c : result) {
            xml.start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster");
        }
        xml.end("DBClusters").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusters", AwsNamespaces.RDS, xml.build())).build();
    }

    /**
     * The rows the list form of DescribeDBClusters would return for the region a request is signed
     * for, for the RDS-family listing {@code RdsQueryHandler} assembles: a live account lists
     * Neptune clusters from the RDS endpoint too. The Neptune store is not keyed by region, so the
     * region is read off each record's ARN.
     */
    public List<String> clusterRowsXml(String filterId, String region) {
        return service.listDbClusters(filterId).stream()
                .filter(c -> inRegion(c.getDbClusterArn(), region))
                .map(this::clusterInnerXml)
                .toList();
    }

    public List<String> instanceRowsXml(String filterId, String region) {
        return service.listDbInstances(filterId).stream()
                .filter(i -> inRegion(i.getDbInstanceArn(), region))
                .map(this::instanceInnerXml)
                .toList();
    }

    private static boolean inRegion(String arn, String region) {
        if (region == null || region.isBlank()) {
            return true;
        }
        String[] parts = arn == null ? new String[0] : arn.split(":", -1);
        return parts.length >= 4 && region.equals(parts[3]);
    }

    private Response handleDeleteDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneCluster cluster = service.getDbCluster(id);
        service.deleteDbCluster(id);
        return Response.ok(AwsQueryResponse.envelope("DeleteDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    private Response handleModifyDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String engineVersion = params.getFirst("EngineVersion");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;

        NeptuneCluster cluster = service.modifyDbCluster(id, engineVersion, iamEnabled,
                clusterSettings(params, false));
        return Response.ok(AwsQueryResponse.envelope("ModifyDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    private Response handleAddRoleToDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        service.addRoleToDbCluster(id, params.getFirst("RoleArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AddRoleToDBCluster", AwsNamespaces.RDS)).build();
    }

    private Response handleRemoveRoleFromDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        service.removeRoleFromDbCluster(id, params.getFirst("RoleArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("RemoveRoleFromDBCluster", AwsNamespaces.RDS)).build();
    }

    private Response handleDescribeGlobalClusters(MultivaluedMap<String, String> params) {
        String maxRecords = params.getFirst("MaxRecords");
        if (maxRecords != null && !maxRecords.isBlank()) {
            int max = -1;
            try {
                max = Integer.parseInt(maxRecords.trim());
            } catch (NumberFormatException e) {
                LOG.debugv("Non-numeric MaxRecords {0} on DescribeGlobalClusters", maxRecords);
            }
            if (max < 20 || max > 100) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid value " + maxRecords + " for MaxRecords. Must be between 20 and 100", 400);
            }
        }
        String identifier = params.getFirst("GlobalClusterIdentifier");
        if (identifier != null && !identifier.isBlank()) {
            throw new AwsException("GlobalClusterNotFoundFault",
                    "Global cluster '" + identifier + "' not found", 404);
        }
        String marker = params.getFirst("Marker");
        if (marker != null && !marker.isBlank()) {
            throw new AwsException("InvalidParameterValue", "The request token is invalid.", 400);
        }
        XmlBuilder xml = new XmlBuilder().start("GlobalClusters").end("GlobalClusters");
        return Response.ok(AwsQueryResponse.envelope("DescribeGlobalClusters", AwsNamespaces.RDS, xml.build())).build();
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    private Response handleCreateDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String dbClusterIdentifier = params.getFirst("DBClusterIdentifier");
        if (dbClusterIdentifier == null || dbClusterIdentifier.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required for Neptune instances.", AwsNamespaces.RDS, 400);
        }
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String engineVersion = params.getFirst("EngineVersion");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));

        NeptuneInstance instance = service.createDbInstance(id, dbClusterIdentifier,
                dbInstanceClass, engineVersion, iamEnabled, instanceSettings(params, true), parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    private Response handleDescribeDbInstances(MultivaluedMap<String, String> params) {
        String identifier = params.getFirst("DBInstanceIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractFilterValue(params, "db-instance-id");
        }

        // AWS parity: the DBInstanceIdentifier parameter faults with
        // DBInstanceNotFound when no instance matches, while the
        // db-instance-id Filters form returns an empty list.
        if (identifier != null && !identifier.isBlank()) {
            service.getDbInstance(identifier); // throws DBInstanceNotFound if absent
        }

        Collection<NeptuneInstance> result = service.listDbInstances(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBInstances");
        for (NeptuneInstance i : result) {
            xml.start("DBInstance").raw(instanceInnerXml(i)).end("DBInstance");
        }
        xml.end("DBInstances").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBInstances", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDeleteDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneInstance instance = service.getDbInstance(id);
        service.deleteDbInstance(id);
        return Response.ok(AwsQueryResponse.envelope("DeleteDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    private Response handleModifyDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;

        NeptuneInstance instance = service.modifyDbInstance(id, dbInstanceClass, iamEnabled,
                instanceSettings(params, false));
        return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        Map<String, String> tags = service.listTagsForResource(params.getFirst("ResourceName"));
        XmlBuilder xml = new XmlBuilder().start("TagList");
        tags.forEach((key, value) -> xml.start("Tag").elem("Key", key).elem("Value", value).end("Tag"));
        xml.end("TagList");
        return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params) {
        service.addTagsToResource(params.getFirst("ResourceName"), parseTags(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AddTagsToResource", AwsNamespaces.RDS)).build();
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params) {
        service.removeTagsFromResource(params.getFirst("ResourceName"), tagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("RemoveTagsFromResource", AwsNamespaces.RDS)).build();
    }

    // ── Request parsing ───────────────────────────────────────────────────────

    private static NeptuneClusterSettings clusterSettings(MultivaluedMap<String, String> params, boolean create) {
        return new NeptuneClusterSettings(
                create ? listParam(params, "AvailabilityZones", "AvailabilityZone") : null,
                create ? optionalInt(params.getFirst("Port")) : null,
                optionalInt(params.getFirst("BackupRetentionPeriod")),
                params.getFirst("PreferredBackupWindow"),
                params.getFirst("PreferredMaintenanceWindow"),
                create ? params.getFirst("DBSubnetGroupName") : null,
                params.getFirst("DBClusterParameterGroupName"),
                listParam(params, "VpcSecurityGroupIds", "VpcSecurityGroupId"),
                create ? optionalBoolean(params.getFirst("StorageEncrypted")) : null,
                create ? params.getFirst("KmsKeyId") : null,
                params.getFirst("StorageType"),
                optionalBoolean(params.getFirst("DeletionProtection")),
                optionalBoolean(params.getFirst("CopyTagsToSnapshot")),
                create ? listParam(params, "EnableCloudwatchLogsExports", "member")
                       : listParam(params, "CloudwatchLogsExportConfiguration.EnableLogTypes", "member"),
                create ? null : listParam(params, "CloudwatchLogsExportConfiguration.DisableLogTypes", "member"),
                create ? params.getFirst("ReplicationSourceIdentifier") : null,
                optionalDouble(params.getFirst("ServerlessV2ScalingConfiguration.MinCapacity")),
                optionalDouble(params.getFirst("ServerlessV2ScalingConfiguration.MaxCapacity")));
    }

    private static NeptuneInstanceSettings instanceSettings(MultivaluedMap<String, String> params, boolean create) {
        return new NeptuneInstanceSettings(
                create ? params.getFirst("AvailabilityZone") : null,
                optionalBoolean(params.getFirst("AutoMinorVersionUpgrade")),
                optionalInt(params.getFirst("PromotionTier")),
                create ? optionalBoolean(params.getFirst("PubliclyAccessible")) : null,
                params.getFirst("DBParameterGroupName"),
                create ? params.getFirst("DBSubnetGroupName") : null,
                params.getFirst("PreferredBackupWindow"),
                params.getFirst("PreferredMaintenanceWindow"));
    }

    private static List<String> listParam(MultivaluedMap<String, String> params, String name, String memberName) {
        Set<String> prefixes = new LinkedHashSet<>(List.of(name + "." + memberName + ".", name + ".member."));
        for (String prefix : prefixes) {
            List<String> values = new ArrayList<>();
            for (int i = 1; ; i++) {
                String value = params.getFirst(prefix + i);
                if (value == null) {
                    break;
                }
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        return params.containsKey(name) ? new ArrayList<>() : null;
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String prefix : List.of("Tags.Tag", "Tags.member", "Tag")) {
            for (int i = 1; ; i++) {
                String key = params.getFirst(prefix + "." + i + ".Key");
                if (key == null) {
                    break;
                }
                String value = params.getFirst(prefix + "." + i + ".Value");
                tags.put(key, value == null ? "" : value);
            }
        }
        return tags;
    }

    private static List<String> tagKeys(MultivaluedMap<String, String> params) {
        List<String> keys = new ArrayList<>();
        for (String prefix : List.of("TagKeys.member", "TagKeys.TagKey", "TagKeys")) {
            for (int i = 1; ; i++) {
                String key = params.getFirst(prefix + "." + i);
                if (key == null) {
                    break;
                }
                keys.add(key);
            }
        }
        return keys;
    }

    private static Boolean optionalBoolean(String value) {
        return value == null ? null : !"false".equalsIgnoreCase(value.trim());
    }

    private static Integer optionalInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "Value " + value + " is not a valid integer.", 400);
        }
    }

    private static Double optionalDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "Value " + value + " is not a valid number.", 400);
        }
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String clusterXml(NeptuneCluster c) {
        return new XmlBuilder().start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster").build();
    }

    private String clusterInnerXml(NeptuneCluster c) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterIdentifier", c.getDbClusterIdentifier())
                .elem("Status", c.getStatus())
                .elem("Engine", "neptune")
                .elem("EngineVersion", c.getEngineVersion())
                .elem("Endpoint", c.getEndpoint())
                .elem("ReaderEndpoint", c.getReaderEndpoint())
                .elem("Port", c.getPort())
                .elem("IAMDatabaseAuthenticationEnabled", c.isIamDatabaseAuthenticationEnabled())
                .elem("MultiAZ", false)
                .elem("StorageEncrypted", c.isStorageEncrypted())
                .elem("KmsKeyId", c.getKmsKeyId())
                .elem("StorageType", c.getStorageType())
                .elem("DBSubnetGroup", c.getDbSubnetGroupName() != null ? c.getDbSubnetGroupName() : "default")
                .elem("DBClusterParameterGroup", c.getDbClusterParameterGroupName() != null
                        ? c.getDbClusterParameterGroupName()
                        : "default." + NeptuneService.parameterGroupFamily(c.getEngineVersion()))
                .elem("BackupRetentionPeriod", c.getBackupRetentionPeriod())
                .elem("PreferredBackupWindow", c.getPreferredBackupWindow() != null
                        ? c.getPreferredBackupWindow() : BackupWindows.DEFAULT_BACKUP_WINDOW)
                .elem("PreferredMaintenanceWindow", c.getPreferredMaintenanceWindow() != null
                        ? c.getPreferredMaintenanceWindow() : BackupWindows.DEFAULT_MAINTENANCE_WINDOW)
                .elem("DeletionProtection", c.isDeletionProtection())
                .elem("CopyTagsToSnapshot", c.isCopyTagsToSnapshot())
                .elem("ReplicationSourceIdentifier", c.getReplicationSourceIdentifier())
                .elem("DbClusterResourceId", c.getDbClusterResourceId())
                .elem("DBClusterArn", c.getDbClusterArn());
        if (c.getCreatedAt() != null) {
            xml.elem("ClusterCreateTime",
                    DateTimeFormatter.ISO_INSTANT.format(c.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)));
        }
        xml.start("AvailabilityZones");
        List<String> zones = c.getAvailabilityZones();
        if (zones.isEmpty()) {
            xml.elem("AvailabilityZone", config.defaultAvailabilityZone());
        } else {
            zones.forEach(zone -> xml.elem("AvailabilityZone", zone));
        }
        xml.end("AvailabilityZones")
           .start("EnabledCloudwatchLogsExports");
        c.getEnabledCloudwatchLogsExports().forEach(logType -> xml.elem("member", logType));
        xml.end("EnabledCloudwatchLogsExports")
           .start("AssociatedRoles");
        for (String roleArn : c.getAssociatedRoleArns()) {
            xml.start("DBClusterRole")
               .elem("RoleArn", roleArn)
               .elem("Status", "ACTIVE")
               .end("DBClusterRole");
        }
        xml.end("AssociatedRoles")
           .start("VpcSecurityGroups");
        for (String groupId : c.getVpcSecurityGroupIds()) {
            xml.start("VpcSecurityGroupMembership")
               .elem("VpcSecurityGroupId", groupId)
               .elem("Status", "active")
               .end("VpcSecurityGroupMembership");
        }
        xml.end("VpcSecurityGroups");
        if (c.getServerlessV2MinCapacity() != null || c.getServerlessV2MaxCapacity() != null) {
            xml.start("ServerlessV2ScalingConfiguration");
            if (c.getServerlessV2MinCapacity() != null) {
                xml.elem("MinCapacity", String.valueOf(c.getServerlessV2MinCapacity()));
            }
            if (c.getServerlessV2MaxCapacity() != null) {
                xml.elem("MaxCapacity", String.valueOf(c.getServerlessV2MaxCapacity()));
            }
            xml.end("ServerlessV2ScalingConfiguration");
        }
        xml.start("DBClusterMembers");
        List<String> members = c.getDbClusterMembers();
        if (members != null) {
            for (String memberId : members) {
                xml.start("DBClusterMember")
                   .elem("DBInstanceIdentifier", memberId)
                   .elem("IsClusterWriter", memberId.equals(members.get(0)))
                   .end("DBClusterMember");
            }
        }
        xml.end("DBClusterMembers");
        return xml.build();
    }

    private String instanceXml(NeptuneInstance i) {
        return new XmlBuilder().start("DBInstance").raw(instanceInnerXml(i)).end("DBInstance").build();
    }

    private String instanceInnerXml(NeptuneInstance i) {
        NeptuneCluster cluster = service.findDbCluster(i.getDbClusterIdentifier()).orElse(null);
        String backupWindow = i.getPreferredBackupWindow() != null ? i.getPreferredBackupWindow()
                : cluster != null && cluster.getPreferredBackupWindow() != null ? cluster.getPreferredBackupWindow()
                : BackupWindows.DEFAULT_BACKUP_WINDOW;
        String subnetGroup = i.getDbSubnetGroupName() != null ? i.getDbSubnetGroupName()
                : cluster != null && cluster.getDbSubnetGroupName() != null ? cluster.getDbSubnetGroupName()
                : "default";
        String parameterGroup = i.getDbParameterGroupName() != null ? i.getDbParameterGroupName()
                : "default." + NeptuneService.parameterGroupFamily(i.getEngineVersion());
        XmlBuilder xml = new XmlBuilder()
                .elem("DBInstanceIdentifier", i.getDbInstanceIdentifier())
                .elem("DBClusterIdentifier", i.getDbClusterIdentifier())
                .elem("DBInstanceClass", i.getDbInstanceClass())
                .elem("DBInstanceStatus", i.getStatus())
                .elem("Engine", "neptune")
                .elem("EngineVersion", i.getEngineVersion())
                .start("Endpoint")
                  .elem("Address", i.getEndpoint())
                  .elem("Port", i.getPort())
                .end("Endpoint")
                .elem("IAMDatabaseAuthenticationEnabled", i.isIamDatabaseAuthenticationEnabled())
                .elem("MultiAZ", false)
                .elem("AutoMinorVersionUpgrade", i.isAutoMinorVersionUpgrade())
                .elem("PromotionTier", i.getPromotionTier())
                .elem("PubliclyAccessible", i.isPubliclyAccessible())
                .elem("StorageEncrypted", cluster != null && cluster.isStorageEncrypted())
                .elem("KmsKeyId", cluster != null ? cluster.getKmsKeyId() : null)
                .elem("StorageType", cluster != null ? cluster.getStorageType() : null)
                .elem("AvailabilityZone", i.getAvailabilityZone() != null
                        ? i.getAvailabilityZone() : config.defaultAvailabilityZone())
                .elem("PreferredBackupWindow", backupWindow)
                .elem("PreferredMaintenanceWindow", i.getPreferredMaintenanceWindow() != null
                        ? i.getPreferredMaintenanceWindow() : BackupWindows.DEFAULT_MAINTENANCE_WINDOW)
                .elem("DbiResourceId", i.getDbiResourceId())
                .elem("DBInstanceArn", i.getDbInstanceArn());
        if (i.getCreatedAt() != null) {
            xml.elem("InstanceCreateTime",
                    DateTimeFormatter.ISO_INSTANT.format(i.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)));
        }
        xml.start("DBSubnetGroup")
           .elem("DBSubnetGroupName", subnetGroup)
           .elem("SubnetGroupStatus", "Complete")
           .end("DBSubnetGroup")
           .start("DBParameterGroups")
           .start("DBParameterGroup")
           .elem("DBParameterGroupName", parameterGroup)
           .elem("ParameterApplyStatus", "in-sync")
           .end("DBParameterGroup")
           .end("DBParameterGroups");
        return xml.build();
    }

    private static String extractFilterValue(MultivaluedMap<String, String> params, String filterName) {
        for (int i = 1; ; i++) {
            String name = params.getFirst("Filters.Filter." + i + ".Name");
            if (name == null) {
                break;
            }
            if (filterName.equals(name)) {
                return params.getFirst("Filters.Filter." + i + ".Values.Value.1");
            }
        }
        return null;
    }
}
