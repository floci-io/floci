package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.docdb.DocDbQueryHandler;
import io.github.hectorvent.floci.services.neptune.NeptuneQueryHandler;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceSettings;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTarget;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.model.OptionGroup;
import io.github.hectorvent.floci.services.rds.model.OptionGroupOption;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-protocol handler for all RDS actions (form-encoded POST, XML response).
 */
@ApplicationScoped
public class RdsQueryHandler {

    private static final Logger LOG = Logger.getLogger(RdsQueryHandler.class);

    private final RdsService service;
    private final DocDbQueryHandler docDbQueryHandler;
    private final NeptuneQueryHandler neptuneQueryHandler;
    private final EmulatorConfig config;

    @Inject
    public RdsQueryHandler(RdsService service, EmulatorConfig config, DocDbQueryHandler docDbQueryHandler,
                           NeptuneQueryHandler neptuneQueryHandler) {
        this.docDbQueryHandler = docDbQueryHandler;
        this.neptuneQueryHandler = neptuneQueryHandler;
        this.service = service;
        this.config = config;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        return handle(action, params, null);
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.infov("RDS action: {0}", action);
        try {
            return switch (action) {
                case "CreateDBInstance" -> handleCreateDbInstance(params, region);
                case "DescribeDBInstances" -> handleDescribeDbInstances(params, region);
                case "DeleteDBInstance" -> handleDeleteDbInstance(params, region);
                case "ModifyDBInstance" -> handleModifyDbInstance(params, region);
                case "RebootDBInstance" -> handleRebootDbInstance(params, region);
                case "DescribeOrderableDBInstanceOptions" -> handleDescribeOrderableDbInstanceOptions(params);
                case "CreateDBSubnetGroup" -> handleCreateDbSubnetGroup(params, region);
                case "DescribeDBSubnetGroups" -> handleDescribeDbSubnetGroups(params, region);
                case "ModifyDBSubnetGroup" -> handleModifyDbSubnetGroup(params, region);
                case "DeleteDBSubnetGroup" -> handleDeleteDbSubnetGroup(params, region);
                case "CreateDBCluster" -> handleCreateDbCluster(params, region);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params, region);
                case "DeleteDBCluster" -> handleDeleteDbCluster(params, region);
                case "ModifyDBCluster" -> handleModifyDbCluster(params, region);
                case "CreateDBParameterGroup" -> handleCreateDbParameterGroup(params, region);
                case "DescribeDBParameterGroups" -> handleDescribeDbParameterGroups(params, region);
                case "DeleteDBParameterGroup" -> handleDeleteDbParameterGroup(params, region);
                case "ModifyDBParameterGroup" -> handleModifyDbParameterGroup(params, region);
                case "DescribeDBParameters" -> handleDescribeDbParameters(params, region);
                case "CreateDBClusterParameterGroup" -> handleCreateDbClusterParameterGroup(params, region);
                case "DescribeDBClusterParameterGroups" -> handleDescribeDbClusterParameterGroups(params, region);
                case "DeleteDBClusterParameterGroup" -> handleDeleteDbClusterParameterGroup(params, region);
                case "ModifyDBClusterParameterGroup" -> handleModifyDbClusterParameterGroup(params, region);
                case "DescribeDBClusterParameters" -> handleDescribeDbClusterParameters(params, region);
                case "CreateOptionGroup" -> handleCreateOptionGroup(params, region);
                case "DescribeOptionGroups" -> handleDescribeOptionGroups(params, region);
                case "ModifyOptionGroup" -> handleModifyOptionGroup(params, region);
                case "DeleteOptionGroup" -> handleDeleteOptionGroup(params, region);
                case "DescribeDBSnapshots" -> handleDescribeDbSnapshots(params);
                case "DescribeDBProxies" -> handleDescribeDbProxies(params, region);
                case "CreateDBProxy" -> handleCreateDbProxy(params, region);
                case "ModifyDBProxy" -> handleModifyDbProxy(params, region);
                case "DeleteDBProxy" -> handleDeleteDbProxy(params, region);
                case "RegisterDBProxyTargets" -> handleRegisterDbProxyTargets(params, region);
                case "DeregisterDBProxyTargets" -> handleDeregisterDbProxyTargets(params, region);
                case "DescribeDBProxyTargetGroups" -> handleDescribeDbProxyTargetGroups(params, region);
                case "ModifyDBProxyTargetGroup" -> handleModifyDbProxyTargetGroup(params, region);
                case "DescribeDBProxyTargets" -> handleDescribeDbProxyTargets(params, region);
                case "DescribeDBClusterSnapshots" -> handleDescribeDbClusterSnapshots(params);
                case "DescribeGlobalClusters" -> handleDescribeGlobalClusters(params);
                case "AddTagsToResource" -> handleAddTagsToResource(params, region);
                case "ListTagsForResource" -> handleListTagsForResource(params, region);
                case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(params, region);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported.", AwsNamespaces.RDS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in RDS {0}", action);
            return Response.serverError().entity("Unexpected error: " + e.getMessage()).build();
        }
    }

    // ── DB Instances ──────────────────────────────────────────────────────────

    private Response handleCreateDbInstance(MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }

        String engine = params.getFirst("Engine");
        String engineVersion = params.getFirst("EngineVersion");
        String masterUsername = params.getFirst("MasterUsername");
        String masterPassword = params.getFirst("MasterUserPassword");
        String dbName = params.getFirst("DBName");
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String allocatedStorageStr = params.getFirst("AllocatedStorage");
        int allocatedStorage = allocatedStorageStr != null ? parseIntSafe(allocatedStorageStr, 20) : 20;
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));
        String paramGroupName = params.getFirst("DBParameterGroupName");
        String optionGroupName = params.getFirst("OptionGroupName");
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        String dbClusterIdentifier = params.getFirst("DBClusterIdentifier");
        boolean manageMasterUserPassword = "true".equalsIgnoreCase(params.getFirst("ManageMasterUserPassword"));
        String masterUserSecretKmsKeyId = params.getFirst("MasterUserSecretKmsKeyId");
        Map<String, String> tags = parseTags(params);
        String availabilityZone = params.getFirst("AvailabilityZone");
        boolean multiAz = "true".equalsIgnoreCase(params.getFirst("MultiAZ"));
        // AWS defaults this to true when the request omits it - unlike most boolean flags here,
        // which default to false.
        boolean autoMinorVersionUpgrade = !"false".equalsIgnoreCase(params.getFirst("AutoMinorVersionUpgrade"));

        if (dbInstanceClass == null) {
            dbInstanceClass = "db.t3.micro";
        }
        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            DbInstanceSettings settings = instanceSettings(params);
            List<String> vpcSecurityGroupIds = vpcSecurityGroupIds(params);
            DbInstance instance = service.createDbInstance(id, engine, engineVersion, masterUsername,
                    masterPassword, dbName, dbInstanceClass, allocatedStorage, iamEnabled,
                    paramGroupName, dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                    manageMasterUserPassword, masterUserSecretKmsKeyId, tags, vpcSecurityGroupIds,
                    optionGroupName, region, autoMinorVersionUpgrade, settings);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbInstances(
            MultivaluedMap<String, String> params, String region) {
        String identifier = params.getFirst("DBInstanceIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-instance-id");
        }
        try {
            Collection<DbInstance> result;
            if (filterId != null && !filterId.isBlank()) {
                result = service.listDbInstances(filterId, region);
            } else {
                List<String> resourceIds = extractRdsFilterValues(params, "dbi-resource-id");
                result = resourceIds.isEmpty()
                        ? service.listDbInstances(null, region)
                        : service.listDbInstancesByDbiResourceIds(resourceIds, region);
            }
            // AWS parity: the DBInstanceIdentifier PARAMETER faults with
            // DBInstanceNotFound when no instance matches, while the
            // db-instance-id Filters form returns an empty list.
            if (identifier != null && !identifier.isBlank() && result.isEmpty()) {
                throw new AwsException("DBInstanceNotFound",
                        "DBInstance " + identifier + " not found.", 404);
            }
            List<String> engines = engineFilter(params);
            XmlBuilder xml = new XmlBuilder().start("DBInstances");
            for (DbInstance i : result) {
                if (engines.isEmpty() || engines.contains(instanceEngine(i))) {
                    xml.start("DBInstance").raw(dbInstanceInnerXml(i)).end("DBInstance");
                }
            }
            boolean listForm = (identifier == null || identifier.isBlank())
                    && extractRdsFilterValues(params, "dbi-resource-id").isEmpty();
            if (listForm && (engines.isEmpty() || engines.contains(DOCDB_ENGINE))) {
                for (String row : docDbQueryHandler.instanceRowsXml(filterId)) {
                    xml.start("DBInstance").raw(row).end("DBInstance");
                }
            }
            if (listForm && (engines.isEmpty() || engines.contains(NEPTUNE_ENGINE))) {
                for (String row : neptuneQueryHandler.instanceRowsXml(filterId, region)) {
                    xml.start("DBInstance").raw(row).end("DBInstance");
                }
            }
            xml.end("DBInstances").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBInstances", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbInstance(
            MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbInstance instance = service.getDbInstance(id, region);
            service.deleteDbInstance(id, region);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbInstance(
            MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String newPassword = params.getFirst("MasterUserPassword");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        String optionGroupName = params.getFirst("OptionGroupName");
        String autoMinorVersionUpgradeStr = params.getFirst("AutoMinorVersionUpgrade");
        Boolean autoMinorVersionUpgrade = autoMinorVersionUpgradeStr != null
                ? Boolean.parseBoolean(autoMinorVersionUpgradeStr) : null;
        try {
            DbInstanceSettings settings = instanceSettings(params, false);
            List<String> vpcSecurityGroupIds = vpcSecurityGroupIds(params);
            DbInstance instance = service.modifyDbInstance(
                    id, newPassword, iamEnabled, dbSubnetGroupName,
                    vpcSecurityGroupIds, optionGroupName, region, autoMinorVersionUpgrade, settings);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private static DbInstanceSettings instanceSettings(MultivaluedMap<String, String> params) {
        return instanceSettings(params, true);
    }

    /**
     * ModifyDBInstance has no StorageEncrypted or KmsKeyId in its request shape — encryption is
     * fixed at create — so a modify reads only the backup settings and the windows.
     */
    private static DbInstanceSettings instanceSettings(MultivaluedMap<String, String> params,
                                                       boolean includeEncryption) {
        return new DbInstanceSettings(
                includeEncryption ? optionalBoolean(params.getFirst("StorageEncrypted")) : null,
                includeEncryption ? params.getFirst("KmsKeyId") : null,
                optionalInt(params.getFirst("BackupRetentionPeriod")),
                params.getFirst("PreferredBackupWindow"),
                params.getFirst("PreferredMaintenanceWindow"),
                optionalBoolean(params.getFirst("CopyTagsToSnapshot")));
    }

    private static Boolean optionalBoolean(String value) {
        return value == null ? null : Boolean.parseBoolean(value);
    }

    private static Integer optionalInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "Value " + value + " is not a valid integer.", 400);
        }
    }

    private Response handleDescribeOrderableDbInstanceOptions(MultivaluedMap<String, String> params) {
        Collection<Map<String, String>> options = service.describeOrderableDbInstanceOptions(
                params.getFirst("Engine"),
                params.getFirst("EngineVersion"),
                params.getFirst("DBInstanceClass"));
        XmlBuilder xml = new XmlBuilder().start("OrderableDBInstanceOptions");
        for (Map<String, String> option : options) {
            xml.start("OrderableDBInstanceOption")
               .elem("Engine", option.get("engine"))
               .elem("EngineVersion", option.get("engineVersion"))
               .elem("DBInstanceClass", option.get("dbInstanceClass"))
               .elem("LicenseModel", "postgresql-license")
               .start("AvailabilityZones")
                 .start("AvailabilityZone")
                   .elem("Name", config.defaultAvailabilityZone())
                 .end("AvailabilityZone")
               .end("AvailabilityZones")
               .end("OrderableDBInstanceOption");
        }
        xml.end("OrderableDBInstanceOptions").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeOrderableDBInstanceOptions",
                AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params, String region) {
        String resourceName = params.getFirst("ResourceName");
        try {
            service.addTagsToResource(resourceName, parseTags(params), region);
            return Response.ok(AwsQueryResponse.envelope("AddTagsToResource", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params, String region) {
        String resourceName = params.getFirst("ResourceName");
        try {
            XmlBuilder xml = new XmlBuilder().start("TagList");
            writeTags(xml, service.listTagsForResource(resourceName, region));
            xml.end("TagList");
            return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params, String region) {
        String resourceName = params.getFirst("ResourceName");
        try {
            service.removeTagsFromResource(resourceName, memberList(params, "TagKeys"), region);
            return Response.ok(AwsQueryResponse.envelope("RemoveTagsFromResource", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleCreateDbSubnetGroup(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("MissingParameter",
                    "The request must contain the parameter DBSubnetGroupName.", AwsNamespaces.RDS, 400);
        }
        String description = params.getFirst("DBSubnetGroupDescription");
        List<String> subnetIds = memberList(params, "SubnetIds");
        try {
            DbSubnetGroup group = service.createDbSubnetGroup(name, description, subnetIds, region,
                    parseTags(params));
            return Response.ok(AwsQueryResponse.envelope("CreateDBSubnetGroup",
                    AwsNamespaces.RDS, dbSubnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbSubnetGroups(MultivaluedMap<String, String> params, String region) {
        String filterName = params.getFirst("DBSubnetGroupName");
        try {
            Collection<DbSubnetGroup> result = service.listDbSubnetGroups(filterName, region);
            XmlBuilder xml = new XmlBuilder().start("DBSubnetGroups");
            for (DbSubnetGroup group : result) {
                xml.start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(group)).end("DBSubnetGroup");
            }
            xml.end("DBSubnetGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBSubnetGroups", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbSubnetGroup(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        List<String> subnetIds = memberList(params, "SubnetIds");
        try {
            DbSubnetGroup group = service.modifyDbSubnetGroup(name, subnetIds, region);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBSubnetGroup",
                    AwsNamespaces.RDS, dbSubnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbSubnetGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbSubnetGroup(name, region);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBSubnetGroup", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleRebootDbInstance(
            MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbInstance instance = service.rebootDbInstance(id, region);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("RebootDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── DB Clusters ───────────────────────────────────────────────────────────

    private Response handleCreateDbCluster(MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }

        String engine = params.getFirst("Engine");
        String engineVersion = params.getFirst("EngineVersion");
        String masterUsername = params.getFirst("MasterUsername");
        String masterPassword = params.getFirst("MasterUserPassword");
        String databaseName = params.getFirst("DatabaseName");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));
        String paramGroupName = params.getFirst("DBClusterParameterGroupName");
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        String availabilityZone = params.getFirst("AvailabilityZone");
        boolean multiAz = "true".equalsIgnoreCase(params.getFirst("MultiAZ"));

        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            Double serverlessV2Min = parseDoubleParam(params, "ServerlessV2ScalingConfiguration.MinCapacity");
            Double serverlessV2Max = parseDoubleParam(params, "ServerlessV2ScalingConfiguration.MaxCapacity");
            Integer serverlessV2SecondsUntilAutoPause = parseIntegerParam(
                    params, "ServerlessV2ScalingConfiguration.SecondsUntilAutoPause");
            DbCluster cluster = service.createDbCluster(id, engine, engineVersion, masterUsername,
                    masterPassword, databaseName, iamEnabled, paramGroupName,
                    dbSubnetGroupName, availabilityZone, multiAz, region,
                    serverlessV2Min, serverlessV2Max, serverlessV2SecondsUntilAutoPause);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private static Double parseDoubleParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " must be a number.", 400);
        }
    }

    private static Integer parseIntegerParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " must be an integer.", 400);
        }
    }

    private Response handleDescribeDbClusters(
            MultivaluedMap<String, String> params, String region) {
        String identifier = params.getFirst("DBClusterIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-cluster-id");
        }
        try {
            Collection<DbCluster> result = service.listDbClusters(filterId, region);
            // AWS parity: the DBClusterIdentifier PARAMETER faults with
            // DBClusterNotFoundFault when no cluster matches, while the
            // db-cluster-id Filters form returns an empty list.
            if (identifier != null && !identifier.isBlank() && result.isEmpty()) {
                throw new AwsException("DBClusterNotFoundFault",
                        "DBCluster " + identifier + " not found.", 404);
            }
            List<String> engines = engineFilter(params);
            XmlBuilder xml = new XmlBuilder().start("DBClusters");
            for (DbCluster c : result) {
                if (engines.isEmpty() || engines.contains(clusterEngine(c))) {
                    xml.start("DBCluster").raw(dbClusterInnerXml(c)).end("DBCluster");
                }
            }
            // The list form covers the whole RDS family: a live account lists DocumentDB and
            // Neptune clusters here too. The identifier form is routed to the store that owns the
            // identifier.
            if (identifier == null || identifier.isBlank()) {
                if (engines.isEmpty() || engines.contains(DOCDB_ENGINE)) {
                    for (String row : docDbQueryHandler.clusterRowsXml(filterId)) {
                        xml.start("DBCluster").raw(row).end("DBCluster");
                    }
                }
                if (engines.isEmpty() || engines.contains(NEPTUNE_ENGINE)) {
                    for (String row : neptuneQueryHandler.clusterRowsXml(filterId, region)) {
                        xml.start("DBCluster").raw(row).end("DBCluster");
                    }
                }
            }
            xml.end("DBClusters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBClusters", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbCluster(
            MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbCluster cluster = service.getDbCluster(id, region);
            service.deleteDbCluster(id, region);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbCluster(
            MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String newPassword = params.getFirst("MasterUserPassword");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;
        try {
            Double serverlessV2Min = parseDoubleParam(params, "ServerlessV2ScalingConfiguration.MinCapacity");
            Double serverlessV2Max = parseDoubleParam(params, "ServerlessV2ScalingConfiguration.MaxCapacity");
            Integer serverlessV2SecondsUntilAutoPause = parseIntegerParam(
                    params, "ServerlessV2ScalingConfiguration.SecondsUntilAutoPause");
            DbCluster cluster = service.modifyDbCluster(id, newPassword, iamEnabled,
                    serverlessV2Min, serverlessV2Max, serverlessV2SecondsUntilAutoPause, region);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Parameter Groups ──────────────────────────────────────────────────────

    private Response handleCreateDbParameterGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbParameterGroup group = service.createDbParameterGroup(
                    name, family, description, region);
            // Tags given at create are readable back on a live account.
            Map<String, String> tags = parseTags(params);
            if (!tags.isEmpty()) {
                service.addTagsToResource(group.getDbParameterGroupArn(), tags, region);
            }
            String result = paramGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbParameterGroups(
            MultivaluedMap<String, String> params, String region) {
        String filterName = params.getFirst("DBParameterGroupName");
        try {
            Collection<DbParameterGroup> result = service.listDbParameterGroups(filterName, region);
            XmlBuilder xml = new XmlBuilder().start("DBParameterGroups");
            for (DbParameterGroup g : result) {
                xml.start("DBParameterGroup").raw(paramGroupInnerXml(g)).end("DBParameterGroup");
            }
            xml.end("DBParameterGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBParameterGroups", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbParameterGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbParameterGroup(name, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBParameterGroup", AwsNamespaces.RDS)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbParameterGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        Map<String, String> parameters = new HashMap<>();
        for (int n = 1; ; n++) {
            String paramName = params.getFirst("Parameters.member." + n + ".ParameterName");
            if (paramName == null) {
                break;
            }
            String paramValue = params.getFirst("Parameters.member." + n + ".ParameterValue");
            if (paramValue != null) {
                parameters.put(paramName, paramValue);
            }
        }
        try {
            DbParameterGroup group = service.modifyDbParameterGroup(name, parameters, region);
            String result = new XmlBuilder()
                    .elem("DBParameterGroupName", group.getDbParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ModifyDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbParameters(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbParameterGroup group = service.getDbParameterGroup(name, region);
            XmlBuilder xml = new XmlBuilder().start("Parameters");
            for (Map.Entry<String, String> entry : group.getParameters().entrySet()) {
                xml.start("member")
                   .elem("ParameterName", entry.getKey())
                   .elem("ParameterValue", entry.getValue())
                   .elem("IsModifiable", true)
                   .end("member");
            }
            xml.end("Parameters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBParameters", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Cluster Parameter Groups ──────────────────────────────────────────────

    private Response handleCreateDbClusterParameterGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBClusterParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbClusterParameterGroup group = service.createDbClusterParameterGroup(
                    name, family, description, region);
            Map<String, String> tags = parseTags(params);
            if (!tags.isEmpty()) {
                service.addTagsToResource(group.getDbClusterParameterGroupArn(), tags, region);
            }
            String result = clusterParamGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterParameterGroups(
            MultivaluedMap<String, String> params, String region) {
        String filterName = params.getFirst("DBClusterParameterGroupName");
        try {
            Collection<DbClusterParameterGroup> result =
                    service.listDbClusterParameterGroups(filterName, region);
            XmlBuilder xml = new XmlBuilder().start("DBClusterParameterGroups");
            for (DbClusterParameterGroup g : result) {
                xml.start("DBClusterParameterGroup").raw(clusterParamGroupInnerXml(g)).end("DBClusterParameterGroup");
            }
            xml.end("DBClusterParameterGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterParameterGroups", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbClusterParameterGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbClusterParameterGroup(name, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBClusterParameterGroup", AwsNamespaces.RDS)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbClusterParameterGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        Map<String, String> parameters = new HashMap<>();
        for (int n = 1; ; n++) {
            String paramName = params.getFirst("Parameters.member." + n + ".ParameterName");
            if (paramName == null) {
                break;
            }
            String paramValue = params.getFirst("Parameters.member." + n + ".ParameterValue");
            if (paramValue != null) {
                parameters.put(paramName, paramValue);
            }
        }
        try {
            DbClusterParameterGroup group = service.modifyDbClusterParameterGroup(
                    name, parameters, region);
            String result = new XmlBuilder()
                    .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ModifyDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterParameters(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbClusterParameterGroup group = service.getDbClusterParameterGroup(name, region);
            XmlBuilder xml = new XmlBuilder().start("Parameters");
            for (Map.Entry<String, String> entry : group.getParameters().entrySet()) {
                xml.start("member")
                   .elem("ParameterName", entry.getKey())
                   .elem("ParameterValue", entry.getValue())
                   .elem("IsModifiable", true)
                   .end("member");
            }
            xml.end("Parameters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterParameters", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Option Groups ─────────────────────────────────────────────────────────

    private Response handleCreateOptionGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("OptionGroupName");
        String engineName = params.getFirst("EngineName");
        String majorEngineVersion = params.getFirst("MajorEngineVersion");
        String description = params.getFirst("OptionGroupDescription");
        Response missing = firstMissingParam(
                "OptionGroupName", name,
                "EngineName", engineName,
                "MajorEngineVersion", majorEngineVersion,
                "OptionGroupDescription", description);
        if (missing != null) {
            return missing;
        }
        OptionGroup group = service.createOptionGroup(
                name, engineName, majorEngineVersion, description, parseTags(params), region);
        return Response.ok(AwsQueryResponse.envelope(
                "CreateOptionGroup", AwsNamespaces.RDS, optionGroupXml(group))).build();
    }

    private Response handleDescribeOptionGroups(
            MultivaluedMap<String, String> params, String region) {
        Collection<OptionGroup> result = service.listOptionGroups(
                params.getFirst("OptionGroupName"),
                params.getFirst("EngineName"),
                params.getFirst("MajorEngineVersion"),
                region);
        XmlBuilder xml = new XmlBuilder().start("OptionGroupsList");
        for (OptionGroup group : result) {
            xml.start("OptionGroup").raw(optionGroupInnerXml(group)).end("OptionGroup");
        }
        xml.end("OptionGroupsList").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope(
                "DescribeOptionGroups", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleModifyOptionGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("OptionGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "OptionGroupName is required.", AwsNamespaces.RDS, 400);
        }
        OptionGroup group = service.modifyOptionGroup(name,
                parseOptionConfigurations(params),
                memberList(params, "OptionsToRemove"),
                region);
        return Response.ok(AwsQueryResponse.envelope(
                "ModifyOptionGroup", AwsNamespaces.RDS, optionGroupXml(group))).build();
    }

    private Response handleDeleteOptionGroup(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("OptionGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "OptionGroupName is required.", AwsNamespaces.RDS, 400);
        }
        service.deleteOptionGroup(name, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult(
                "DeleteOptionGroup", AwsNamespaces.RDS)).build();
    }

    /**
     * Returns an {@code InvalidParameterValue} response for the first blank value in the given
     * name/value pairs, or {@code null} when they are all present.
     */
    private static Response firstMissingParam(String... nameValuePairs) {
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            String value = nameValuePairs[i + 1];
            if (value == null || value.isBlank()) {
                return AwsQueryResponse.error("InvalidParameterValue",
                        nameValuePairs[i] + " is required.", AwsNamespaces.RDS, 400);
            }
        }
        return null;
    }

    /**
     * Parses {@code OptionsToInclude.OptionConfiguration.N.*} (and the legacy
     * {@code OptionsToInclude.member.N.*} encoding older SDKs emit).
     */
    private static List<OptionGroupOption> parseOptionConfigurations(
            MultivaluedMap<String, String> params) {
        List<OptionGroupOption> options = new java.util.ArrayList<>();
        for (String prefix : List.of("OptionsToInclude.OptionConfiguration",
                "OptionsToInclude.member")) {
            for (int n = 1; ; n++) {
                String base = prefix + "." + n + ".";
                String optionName = params.getFirst(base + "OptionName");
                if (optionName == null) {
                    break;
                }
                OptionGroupOption option = new OptionGroupOption(optionName);
                option.setOptionVersion(params.getFirst(base + "OptionVersion"));
                String port = params.getFirst(base + "Port");
                if (port != null && !port.isBlank()) {
                    try {
                        option.setPort(Integer.parseInt(port.trim()));
                    } catch (NumberFormatException e) {
                        throw new AwsException("InvalidParameterValue",
                                "Port must be an integer for option " + optionName + ".", 400);
                    }
                }
                option.setOptionSettings(parseOptionSettings(params, base));
                option.setVpcSecurityGroupMemberships(
                        memberList(params, base + "VpcSecurityGroupMemberships"));
                option.setDbSecurityGroupMemberships(
                        memberList(params, base + "DBSecurityGroupMemberships"));
                options.add(option);
            }
        }
        return options;
    }

    private static Map<String, String> parseOptionSettings(
            MultivaluedMap<String, String> params, String optionPrefix) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String prefix : List.of("OptionSettings.OptionSetting", "OptionSettings.member")) {
            for (int n = 1; ; n++) {
                String settingName = params.getFirst(optionPrefix + prefix + "." + n + ".Name");
                if (settingName == null) {
                    break;
                }
                settings.put(settingName,
                        params.getFirst(optionPrefix + prefix + "." + n + ".Value"));
            }
        }
        return settings;
    }

    // ── Snapshots & Proxies (not modeled — empty lists) ───────────────────────

    private Response handleDescribeDbSnapshots(MultivaluedMap<String, String> params) {
        // DB snapshots are not modeled; return the RDS Query API's wire-accurate empty
        // result (empty <DBSnapshots> wrapper, no <Marker>) so SDK clients complete the
        // read instead of failing with UnsupportedOperation.
        String result = new XmlBuilder().start("DBSnapshots").end("DBSnapshots").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeDBSnapshots", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDescribeDbProxies(MultivaluedMap<String, String> params, String region) {
        XmlBuilder xml = new XmlBuilder().start("DBProxies");
        for (DbProxy p : service.listDbProxies(params.getFirst("DBProxyName"), region)) {
            xml.start("member").raw(dbProxyInnerXml(p)).end("member");
        }
        xml.end("DBProxies");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBProxies", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleCreateDbProxy(MultivaluedMap<String, String> params, String region) {
        validateIpv4NetworkType(params.getFirst("EndpointNetworkType"),
                "EndpointNetworkType", true, "IPV4, IPV6, or DUAL");
        validateIpv4NetworkType(params.getFirst("TargetConnectionNetworkType"),
                "TargetConnectionNetworkType", false, "IPV4 or IPV6");
        String name = params.getFirst("DBProxyName");
        String engineFamily = params.getFirst("EngineFamily");
        boolean requireTls = "true".equalsIgnoreCase(params.getFirst("RequireTLS"));
        boolean debugLogging = "true".equalsIgnoreCase(params.getFirst("DebugLogging"));
        int idleClientTimeout = 1800;
        String idleClientTimeoutValue = params.getFirst("IdleClientTimeout");
        if (idleClientTimeoutValue != null) {
            try {
                idleClientTimeout = Integer.parseInt(idleClientTimeoutValue);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParameterValue", "IdleClientTimeout must be an integer.", 400);
            }
        }
        String roleArn = params.getFirst("RoleArn");
        List<String> subnetIds = memberList(params, "VpcSubnetIds");
        List<String> sgIds = memberList(params, "VpcSecurityGroupIds");
        List<DbProxyAuth> auth = parseProxyAuth(params);
        String defaultAuthScheme = params.getFirst("DefaultAuthScheme");
        boolean iamEnabled = auth.stream().anyMatch(a ->
                "REQUIRED".equalsIgnoreCase(a.getIamAuth())
                        || "ENABLED".equalsIgnoreCase(a.getIamAuth()));
        iamEnabled = iamEnabled || "IAM_AUTH".equalsIgnoreCase(defaultAuthScheme);
        DbProxy proxy = service.createDbProxy(
                name, engineFamily, requireTls, iamEnabled, defaultAuthScheme, roleArn,
                subnetIds, sgIds, auth, idleClientTimeout, debugLogging, parseTags(params), region);
        String result = new XmlBuilder().start("DBProxy").raw(dbProxyInnerXml(proxy)).end("DBProxy").build();
        return Response.ok(AwsQueryResponse.envelope("CreateDBProxy", AwsNamespaces.RDS, result)).build();
    }

    private Response handleModifyDbProxy(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBProxyName");
        String newName = params.getFirst("NewDBProxyName");
        if (newName != null && !newName.isBlank()) {
            throw new AwsException("UnsupportedOperation",
                    "NewDBProxyName is not supported; renaming a persisted proxy requires "
                            + "an atomic storage-key migration.", 400);
        }
        List<DbProxyAuth> auth = hasProxyAuthKeys(params) ? parseProxyAuth(params) : null;
        List<String> securityGroups = hasMemberKeys(params, "SecurityGroups")
                ? memberList(params, "SecurityGroups")
                : (hasMemberKeys(params, "VpcSecurityGroupIds")
                ? vpcSecurityGroupIds(params) : null);
        DbProxy proxy = service.modifyDbProxy(
                name,
                params.getFirst("DefaultAuthScheme"),
                auth,
                parseOptionalBoolean(params, "RequireTLS"),
                parseOptionalInteger(params, "IdleClientTimeout"),
                parseOptionalBoolean(params, "DebugLogging"),
                params.getFirst("RoleArn"),
                securityGroups,
                null,
                region);
        String result = new XmlBuilder().start("DBProxy")
                .raw(dbProxyInnerXml(proxy)).end("DBProxy").build();
        return Response.ok(AwsQueryResponse.envelope(
                "ModifyDBProxy", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDeleteDbProxy(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBProxyName");
        DbProxy proxy = service.getDbProxy(name, region);
        String result = new XmlBuilder().start("DBProxy").raw(dbProxyInnerXml(proxy)).end("DBProxy").build();
        service.deleteDbProxy(name, region);
        return Response.ok(AwsQueryResponse.envelope("DeleteDBProxy", AwsNamespaces.RDS, result)).build();
    }

    private Response handleRegisterDbProxyTargets(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBProxyName");
        String tgName = params.getFirst("TargetGroupName");
        validateOptionalTargetGroupName(tgName);
        List<String> clusterIds = memberList(params, "DBClusterIdentifiers");
        List<String> instanceIds = memberList(params, "DBInstanceIdentifiers");
        DbProxyTargetGroup tg = service.registerDbProxyTargets(
                name, tgName, clusterIds, instanceIds, 0, 0, region);
        XmlBuilder xml = new XmlBuilder().start("DBProxyTargets");
        for (DbProxyTarget t : tg.getTargets()) {
            xml.start("member").raw(dbProxyTargetInnerXml(t)).end("member");
        }
        xml.end("DBProxyTargets");
        return Response.ok(AwsQueryResponse.envelope("RegisterDBProxyTargets", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDeregisterDbProxyTargets(MultivaluedMap<String, String> params, String region) {
        String targetGroupName = params.getFirst("TargetGroupName");
        validateOptionalTargetGroupName(targetGroupName);
        service.deregisterDbProxyTargets(params.getFirst("DBProxyName"), targetGroupName,
                memberList(params, "DBClusterIdentifiers"), memberList(params, "DBInstanceIdentifiers"), region);
        return Response.ok(AwsQueryResponse.envelope("DeregisterDBProxyTargets", AwsNamespaces.RDS, "")).build();
    }

    private Response handleDescribeDbProxyTargetGroups(
            MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBProxyName");
        XmlBuilder xml = new XmlBuilder().start("TargetGroups");
        for (DbProxyTargetGroup tg : service.describeDbProxyTargetGroups(
                name, params.getFirst("TargetGroupName"), region)) {
            xml.start("member").raw(dbProxyTargetGroupInnerXml(tg)).end("member");
        }
        xml.end("TargetGroups");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBProxyTargetGroups", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleModifyDbProxyTargetGroup(
            MultivaluedMap<String, String> params, String region) {
        String targetGroupName = params.getFirst("TargetGroupName");
        if (targetGroupName == null || targetGroupName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "TargetGroupName is required.", 400);
        }
        String newName = params.getFirst("NewName");
        if (newName != null && !newName.isBlank()) {
            throw new AwsException("UnsupportedOperation",
                    "The default DB proxy target group cannot be renamed.", 400);
        }
        String poolPrefix = "ConnectionPoolConfig.";
        List<String> pinningFilters = hasMemberKeys(
                params, poolPrefix + "SessionPinningFilters")
                ? memberList(params, poolPrefix + "SessionPinningFilters") : null;
        DbProxyTargetGroup targetGroup = service.configureDbProxyTargetGroup(
                params.getFirst("DBProxyName"),
                targetGroupName,
                parseOptionalInteger(params, poolPrefix + "MaxConnectionsPercent"),
                parseOptionalInteger(params, poolPrefix + "MaxIdleConnectionsPercent"),
                parseOptionalInteger(params, poolPrefix + "ConnectionBorrowTimeout"),
                params.containsKey(poolPrefix + "InitQuery")
                        ? params.getFirst(poolPrefix + "InitQuery") : null,
                pinningFilters,
                region);
        String result = new XmlBuilder().start("DBProxyTargetGroup")
                .raw(dbProxyTargetGroupInnerXml(targetGroup)).end("DBProxyTargetGroup").build();
        return Response.ok(AwsQueryResponse.envelope(
                "ModifyDBProxyTargetGroup", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDescribeDbProxyTargets(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBProxyName");
        String tgName = params.getFirst("TargetGroupName");
        validateOptionalTargetGroupName(tgName);
        XmlBuilder xml = new XmlBuilder().start("Targets");
        for (DbProxyTarget t : service.describeDbProxyTargets(name, tgName, region)) {
            xml.start("member").raw(dbProxyTargetInnerXml(t)).end("member");
        }
        xml.end("Targets");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBProxyTargets", AwsNamespaces.RDS, xml.build())).build();
    }

    private void validateOptionalTargetGroupName(String targetGroupName) {
        if (targetGroupName != null && targetGroupName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "TargetGroupName must be at least 1 character.", 400);
        }
    }

    private String dbProxyInnerXml(DbProxy p) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBProxyName", p.getDbProxyName())
                .elem("DBProxyArn", p.getDbProxyArn())
                .elem("Status", p.getStatus())
                .elem("EngineFamily", p.getEngineFamily())
                .elem("Endpoint", p.getEndpoint())
                .elem("RequireTLS", String.valueOf(p.isRequireTls()))
                .elem("DefaultAuthScheme", p.getDefaultAuthScheme())
                .elem("EndpointNetworkType", p.getEndpointNetworkType() != null
                        ? p.getEndpointNetworkType() : "IPV4")
                .elem("TargetConnectionNetworkType", p.getTargetConnectionNetworkType() != null
                        ? p.getTargetConnectionNetworkType() : "IPV4")
                .elem("IdleClientTimeout", p.getIdleClientTimeout())
                .elem("DebugLogging", String.valueOf(p.isDebugLogging()));
        if (p.getVpcId() != null) {
            xml.elem("VpcId", p.getVpcId());
        }
        if (p.getRoleArn() != null) {
            xml.elem("RoleArn", p.getRoleArn());
        }
        xml.start("Auth");
        for (DbProxyAuth a : p.getAuth()) {
            xml.start("member")
               .elem("AuthScheme", a.getAuthScheme())
               .elem("SecretArn", a.getSecretArn())
               .elem("IAMAuth", a.getIamAuth());
            if (a.getClientPasswordAuthType() != null) {
                xml.elem("ClientPasswordAuthType", a.getClientPasswordAuthType());
            }
            if (a.getDescription() != null) {
                xml.elem("Description", a.getDescription());
            }
            if (a.getUserName() != null) {
                xml.elem("UserName", a.getUserName());
            }
            xml.end("member");
        }
        xml.end("Auth");
        xml.start("VpcSubnetIds");
        for (String s : p.getVpcSubnetIds()) {
            xml.elem("member", s);
        }
        xml.end("VpcSubnetIds");
        xml.start("VpcSecurityGroupIds");
        for (String securityGroupId : p.getVpcSecurityGroupIds()) {
            xml.elem("member", securityGroupId);
        }
        xml.end("VpcSecurityGroupIds");
        if (p.getCreatedAt() != null) {
            xml.elem("CreatedDate", p.getCreatedAt().toString());
        }
        if (p.getUpdatedAt() != null) {
            xml.elem("UpdatedDate", p.getUpdatedAt().toString());
        }
        return xml.build();
    }

    private static void validateIpv4NetworkType(
            String value, String parameterName, boolean dualAllowed, String validValues) {
        if (value == null) {
            return;
        }
        if ("IPV4".equalsIgnoreCase(value)) {
            return;
        }
        boolean supportedAwsValue = "IPV6".equalsIgnoreCase(value)
                || (dualAllowed && "DUAL".equalsIgnoreCase(value));
        if (value.isBlank() || !supportedAwsValue) {
            throw new AwsException("InvalidParameterValue",
                    parameterName + " must be " + validValues + ".", 400);
        }
        throw new AwsException("UnsupportedOperation",
                parameterName + " " + value.toUpperCase()
                        + " is not supported because Floci currently exposes IPv4 proxy networking only.",
                400);
    }

    private String dbProxyTargetGroupInnerXml(DbProxyTargetGroup tg) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBProxyName", tg.getDbProxyName())
                .elem("TargetGroupName", tg.getTargetGroupName())
                .elem("TargetGroupArn", tg.getTargetGroupArn())
                .elem("Status", tg.getStatus())
                .elem("IsDefault", String.valueOf(tg.isDefaultTargetGroup()))
                .start("ConnectionPoolConfig")
                  .elem("MaxConnectionsPercent", tg.getMaxConnectionsPercent())
                  .elem("MaxIdleConnectionsPercent", tg.getMaxIdleConnectionsPercent())
                  .elem("ConnectionBorrowTimeout", tg.getConnectionBorrowTimeout());
        if (tg.getInitQuery() != null) {
            xml.elem("InitQuery", tg.getInitQuery());
        }
        xml.start("SessionPinningFilters");
        for (String filter : tg.getSessionPinningFilters()) {
            xml.elem("member", filter);
        }
        xml.end("SessionPinningFilters")
                .end("ConnectionPoolConfig");
        if (tg.getCreatedAt() != null) {
            xml.elem("CreatedDate", tg.getCreatedAt().toString());
        }
        if (tg.getUpdatedAt() != null) {
            xml.elem("UpdatedDate", tg.getUpdatedAt().toString());
        }
        return xml.build();
    }

    private String dbProxyTargetInnerXml(DbProxyTarget t) {
        XmlBuilder xml = new XmlBuilder()
                .elem("Type", t.getType())
                .elem("RdsResourceId", t.getRdsResourceId())
                .elem("Endpoint", t.getEndpoint())
                .elem("Port", t.getPort())
                .start("TargetHealth").elem("State", t.getTargetHealth()).end("TargetHealth");
        if (t.getTargetArn() != null) {
            xml.elem("TargetArn", t.getTargetArn());
        }
        return xml.build();
    }

    private Response handleDescribeGlobalClusters(MultivaluedMap<String, String> params) {
        // Global clusters are not modeled. Both providers read this on every cluster read, and
        // DocumentDB signs with the "rds" scope, so this handler answers for either service.
        // MaxRecords is rejected before the identifier is looked up, and a marker after it —
        // the order a live account applies them in.
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
            // Naming one is a different question from listing none, and AWS errors on it.
            throw new AwsException("GlobalClusterNotFoundFault",
                    "Global cluster '" + identifier + "' not found", 404);
        }
        // No page is ever handed out, so any marker a caller presents came from somewhere else.
        String marker = params.getFirst("Marker");
        if (marker != null && !marker.isBlank()) {
            throw new AwsException("InvalidParameterValue", "The request token is invalid.", 400);
        }
        // Filters are not validated: the answer is empty for every name AWS accepts, and a partial
        // list of accepted names would reject filters a live account allows.
        String result = new XmlBuilder().start("GlobalClusters").end("GlobalClusters").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeGlobalClusters", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDescribeDbClusterSnapshots(MultivaluedMap<String, String> params) {
        // DB cluster snapshots are not modeled; return the RDS Query API's wire-accurate
        // empty result (empty <DBClusterSnapshots> wrapper, no <Marker>) so SDK clients
        // complete the read instead of failing with UnsupportedOperation.
        String result = new XmlBuilder().start("DBClusterSnapshots").end("DBClusterSnapshots").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterSnapshots", AwsNamespaces.RDS, result)).build();
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String dbInstanceXml(DbInstance i) {
        return new XmlBuilder().start("DBInstance").raw(dbInstanceInnerXml(i)).end("DBInstance").build();
    }

    private String dbInstanceInnerXml(DbInstance i) {
        DbEndpoint ep = i.getEndpoint();
        String engineStr = instanceEngine(i);
        String statusStr = i.getStatus() != null ? statusLabel(i.getStatus()) : "available";

        XmlBuilder xml = new XmlBuilder()
                .elem("DBInstanceIdentifier", i.getDbInstanceIdentifier())
                .elem("DBInstanceStatus", statusStr)
                .elem("Engine", engineStr.toLowerCase())
                .elem("EngineVersion", i.getEngineVersion())
                .elem("MasterUsername", i.getMasterUsername());
        if (i.getDbName() != null && !i.getDbName().isBlank()) {
            xml.elem("DBName", i.getDbName());
        }
        xml.elem("DBInstanceClass", i.getDbInstanceClass())
           .elem("AllocatedStorage", i.getAllocatedStorage());
        if (ep != null) {
            xml.start("Endpoint")
               .elem("Address", ep.address())
               .elem("Port", ep.port())
               .end("Endpoint");
        }
        xml.elem("IAMDatabaseAuthenticationEnabled", i.isIamDatabaseAuthenticationEnabled())
           .elem("MultiAZ", i.isMultiAz())
           .elem("AutoMinorVersionUpgrade", i.isAutoMinorVersionUpgrade())
           .elem("StorageType", "gp2")
           .elem("PubliclyAccessible", false)
           .elem("AvailabilityZone", i.getAvailabilityZone() != null ? i.getAvailabilityZone() : config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", i.getPreferredMaintenanceWindow() != null
                   ? i.getPreferredMaintenanceWindow() : DbInstanceSettings.DEFAULT_MAINTENANCE_WINDOW)
           .elem("PreferredBackupWindow", i.getPreferredBackupWindow() != null
                   ? i.getPreferredBackupWindow() : DbInstanceSettings.DEFAULT_BACKUP_WINDOW)
           .elem("BackupRetentionPeriod", i.getBackupRetentionPeriod())
           .elem("StorageEncrypted", i.isStorageEncrypted())
           .elem("CopyTagsToSnapshot", i.isCopyTagsToSnapshot())
           .raw(vpcSecurityGroupsXml(i))
           .raw(dbParameterGroupsXml(i))
           .raw(optionGroupMembershipsXml(i))
           .raw(dbSubnetGroupXml(dbSubnetGroupForInstance(i)))
           .elem("DbiResourceId", i.getDbiResourceId())
           .elem("DBInstanceArn", i.getDbInstanceArn());
        if (i.getKmsKeyId() != null && !i.getKmsKeyId().isBlank()) {
            xml.elem("KmsKeyId", i.getKmsKeyId());
        }
        if (i.getMasterUserSecretArn() != null && !i.getMasterUserSecretArn().isBlank()) {
            xml.start("MasterUserSecret")
                    .elem("SecretArn", i.getMasterUserSecretArn())
                    .elem("SecretStatus", i.getMasterUserSecretStatus() == null ? "active" : i.getMasterUserSecretStatus());
            if (i.getMasterUserSecretKmsKeyId() != null && !i.getMasterUserSecretKmsKeyId().isBlank()) {
                xml.elem("KmsKeyId", i.getMasterUserSecretKmsKeyId());
            }
            xml.end("MasterUserSecret");
        }
        if (i.getDbClusterIdentifier() != null && !i.getDbClusterIdentifier().isBlank()) {
            xml.elem("DBClusterIdentifier", i.getDbClusterIdentifier());
        }
        xml.start("TagList");
        writeTags(xml, i.getTags());
        xml.end("TagList");
        return xml.build();
    }

    private String vpcSecurityGroupsXml(DbInstance i) {
        List<String> groupIds = i.getVpcSecurityGroupIds().isEmpty()
                ? List.of("sg-00000000")
                : i.getVpcSecurityGroupIds();
        XmlBuilder xml = new XmlBuilder().start("VpcSecurityGroups");
        for (String groupId : groupIds) {
            xml.start("VpcSecurityGroupMembership")
                    .elem("VpcSecurityGroupId", groupId)
                    .elem("Status", "active")
                    .end("VpcSecurityGroupMembership");
        }
        return xml.end("VpcSecurityGroups").build();
    }

    private static List<String> vpcSecurityGroupIds(MultivaluedMap<String, String> params) {
        List<String> values = memberList(params, "VpcSecurityGroupIds");
        if (values.isEmpty() && hasMemberKeys(params, "VpcSecurityGroupIds")) {
            throw new AwsException("InvalidParameterValue",
                    "VpcSecurityGroupIds must contain at least one non-empty VpcSecurityGroupId.", 400);
        }
        return values;
    }

    private static String dbParameterGroupsXml(DbInstance instance) {
        String name = dbParameterGroupName(instance);

        XmlBuilder xml = new XmlBuilder().start("DBParameterGroups");
        xml.start("DBParameterGroup")
           .elem("DBParameterGroupName", name)
           .elem("ParameterApplyStatus", "in-sync")
           .end("DBParameterGroup");
        return xml.end("DBParameterGroups").build();
    }

    private static String dbParameterGroupName(DbInstance instance) {
        String name = instance.getParameterGroupName();
        if (name != null && !name.isBlank()) {
            return name;
        }

        String engine = instance.getEngine() != null
                ? instance.getEngine().name().toLowerCase()
                : "unknown";
        return "default." + engine + dbEngineMajorVersion(instance);
    }

    private static String dbEngineMajorVersion(DbInstance instance) {
        String engineVersion = instance.getEngineVersion();
        if ((engineVersion == null || engineVersion.isBlank()) && instance.getEngine() != null) {
            engineVersion = defaultEngineVersion(instance.getEngine().name());
        }
        if (engineVersion == null || engineVersion.isBlank()) {
            return "";
        }

        String trimmed = engineVersion.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : trimmed.substring(0, end);
    }

    private static void writeTags(XmlBuilder xml, Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        tags.forEach((key, value) -> xml.start("Tag")
                .elem("Key", key)
                .elem("Value", value)
                .end("Tag"));
    }

    private String dbClusterXml(DbCluster c) {
        return new XmlBuilder().start("DBCluster").raw(dbClusterInnerXml(c)).end("DBCluster").build();
    }

    private String dbClusterInnerXml(DbCluster c) {
        DbEndpoint ep = c.getEndpoint();
        DbEndpoint readerEp = c.getReaderEndpoint();
        String engineStr = c.getEngineIdentifier() != null
                ? c.getEngineIdentifier()
                : c.getEngine() != null ? c.getEngine().name() : "";
        String statusStr = c.getStatus() != null ? statusLabel(c.getStatus()) : "available";

        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterIdentifier", c.getDbClusterIdentifier())
                .elem("Status", statusStr)
                .elem("Engine", engineStr.toLowerCase())
                .elem("EngineVersion", c.getEngineVersion())
                .elem("MasterUsername", c.getMasterUsername());
        if (c.getDatabaseName() != null && !c.getDatabaseName().isBlank()) {
            xml.elem("DatabaseName", c.getDatabaseName());
        }
        if (ep != null) {
            xml.elem("Endpoint", ep.address())
               .elem("Port", ep.port());
        }
        if (readerEp != null) {
            xml.elem("ReaderEndpoint", readerEp.address());
        }
        xml.elem("IAMDatabaseAuthenticationEnabled", c.isIamDatabaseAuthenticationEnabled())
           .elem("MultiAZ", c.isMultiAz())
           .elem("AvailabilityZone", c.getAvailabilityZone() != null ? c.getAvailabilityZone() : config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", "mon:00:00-mon:03:00")
           .elem("PreferredBackupWindow", "04:00-06:00")
           .start("VpcSecurityGroups")
             .start("VpcSecurityGroupMembership")
               .elem("VpcSecurityGroupId", "sg-00000000")
               .elem("Status", "active")
             .end("VpcSecurityGroupMembership")
           .end("VpcSecurityGroups")
           .elem("DBSubnetGroup", c.getDbSubnetGroupName() != null ? c.getDbSubnetGroupName() : "default")
           .elem("DbClusterResourceId", c.getDbClusterResourceId())
           .elem("DBClusterArn", c.getDbClusterArn());
        if (c.getServerlessV2MinCapacity() != null || c.getServerlessV2MaxCapacity() != null) {
            xml.start("ServerlessV2ScalingConfiguration");
            if (c.getServerlessV2MinCapacity() != null) {
                xml.elem("MinCapacity", String.valueOf(c.getServerlessV2MinCapacity()));
            }
            if (c.getServerlessV2MaxCapacity() != null) {
                xml.elem("MaxCapacity", String.valueOf(c.getServerlessV2MaxCapacity()));
            }
            if (c.getServerlessV2SecondsUntilAutoPause() != null) {
                xml.elem("SecondsUntilAutoPause", String.valueOf(c.getServerlessV2SecondsUntilAutoPause()));
            }
            xml.end("ServerlessV2ScalingConfiguration");
        }
        xml.start("DBClusterMembers");
        if (c.getDbClusterMembers() != null) {
            for (String memberId : c.getDbClusterMembers()) {
                xml.start("member")
                   .elem("DBInstanceIdentifier", memberId)
                   .elem("IsClusterWriter", true)
                   .end("member");
            }
        }
        xml.end("DBClusterMembers");
        return xml.build();
    }

    private String paramGroupXml(DbParameterGroup g) {
        return new XmlBuilder().start("DBParameterGroup").raw(paramGroupInnerXml(g)).end("DBParameterGroup").build();
    }

    private String dbSubnetGroupXml(DbSubnetGroup g) {
        return new XmlBuilder().start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(g)).end("DBSubnetGroup").build();
    }

    private String dbSubnetGroupInnerXml(DbSubnetGroup g) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBSubnetGroupName", g.getDbSubnetGroupName())
                .elem("DBSubnetGroupDescription", g.getDescription())
                .elem("VpcId", g.getVpcId() != null ? g.getVpcId() : "vpc-00000000")
                .elem("SubnetGroupStatus", g.getSubnetGroupStatus() != null ? g.getSubnetGroupStatus() : "Complete")
                .elem("DBSubnetGroupArn", g.getDbSubnetGroupArn())
                .start("Subnets");
        for (String subnetId : g.getSubnetIds()) {
            String az = g.getSubnetAvailabilityZones().get(subnetId);
            xml.start("Subnet")
               .elem("SubnetIdentifier", subnetId)
               .start("SubnetAvailabilityZone")
                 .elem("Name", az != null ? az : config.defaultAvailabilityZone())
               .end("SubnetAvailabilityZone")
               .elem("SubnetStatus", "Active")
               .end("Subnet");
        }
        return xml.end("Subnets").build();
    }

    private DbSubnetGroup dbSubnetGroupForInstance(DbInstance instance) {
        String groupName = instance.getDbSubnetGroupName();
        if (groupName == null || groupName.isBlank() || "default".equalsIgnoreCase(groupName)) {
            return fallbackSubnetGroup(instance, "default", "default subnet group");
        }
        return service.getDbSubnetGroup(
                groupName, regionFromRdsArn(instance.getDbInstanceArn()));
    }

    private String regionFromRdsArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return config.defaultRegion();
        }
        String[] parts = arn.split(":", 6);
        return parts.length == 6 && !parts[3].isBlank()
                ? parts[3] : config.defaultRegion();
    }

    private DbSubnetGroup fallbackSubnetGroup(DbInstance instance, String name, String description) {
        DbSubnetGroup fallback = new DbSubnetGroup();
        fallback.setDbSubnetGroupName(name);
        fallback.setDescription(description);
        fallback.setVpcId(instance.getVpcId() != null ? instance.getVpcId() : "vpc-00000000");
        fallback.setSubnetGroupStatus("Complete");
        fallback.setDbSubnetGroupArn(subnetGroupArnForInstance(instance, name));
        Map<String, String> zones = instance.getSubnetAvailabilityZones();
        if (!zones.isEmpty()) {
            fallback.setSubnetIds(List.copyOf(zones.keySet()));
            fallback.setSubnetAvailabilityZones(zones);
        } else {
            fallback.setSubnetIds(List.of("subnet-00000000"));
            fallback.setSubnetAvailabilityZones(Map.of("subnet-00000000", config.defaultAvailabilityZone()));
        }
        return fallback;
    }

    private static String subnetGroupArnForInstance(DbInstance instance, String name) {
        String arn = instance.getDbInstanceArn();
        if (arn == null || arn.isBlank()) {
            return null;
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            return null;
        }
        return String.join(":", parts[0], parts[1], parts[2], parts[3], parts[4], "subgrp:" + name);
    }

    private String paramGroupInnerXml(DbParameterGroup g) {
        return new XmlBuilder()
                .elem("DBParameterGroupName", g.getDbParameterGroupName())
                .elem("DBParameterGroupFamily", g.getDbParameterGroupFamily())
                .elem("Description", g.getDescription())
                .elem("DBParameterGroupArn", g.getDbParameterGroupArn())
                .build();
    }

    private String optionGroupXml(OptionGroup g) {
        return new XmlBuilder().start("OptionGroup").raw(optionGroupInnerXml(g)).end("OptionGroup").build();
    }

    private String optionGroupInnerXml(OptionGroup g) {
        XmlBuilder xml = new XmlBuilder()
                .elem("OptionGroupName", g.getOptionGroupName())
                .elem("OptionGroupDescription", g.getOptionGroupDescription())
                .elem("EngineName", g.getEngineName())
                .elem("MajorEngineVersion", g.getMajorEngineVersion())
                .start("Options");
        for (OptionGroupOption option : g.getOptions()) {
            xml.start("Option").raw(optionInnerXml(option)).end("Option");
        }
        return xml.end("Options")
                .elem("AllowsVpcAndNonVpcInstanceMemberships",
                        g.isAllowsVpcAndNonVpcInstanceMemberships())
                .elem("OptionGroupArn", g.getOptionGroupArn())
                .build();
    }

    private String optionInnerXml(OptionGroupOption o) {
        XmlBuilder xml = new XmlBuilder()
                .elem("OptionName", o.getOptionName())
                .elem("OptionDescription", o.getOptionDescription())
                .elem("OptionVersion", o.getOptionVersion());
        if (o.getPort() != null) {
            xml.elem("Port", o.getPort().longValue());
        }
        xml.elem("Persistent", o.isPersistent())
           .elem("Permanent", o.isPermanent())
           .start("OptionSettings");
        for (Map.Entry<String, String> setting : o.getOptionSettings().entrySet()) {
            xml.start("OptionSetting")
               .elem("Name", setting.getKey())
               .elem("Value", setting.getValue())
               .elem("IsModifiable", true)
               .elem("IsCollection", false)
               .end("OptionSetting");
        }
        xml.end("OptionSettings").start("VpcSecurityGroupMemberships");
        for (String securityGroupId : o.getVpcSecurityGroupMemberships()) {
            xml.start("VpcSecurityGroupMembership")
               .elem("VpcSecurityGroupId", securityGroupId)
               .elem("Status", "active")
               .end("VpcSecurityGroupMembership");
        }
        xml.end("VpcSecurityGroupMemberships").start("DBSecurityGroupMemberships");
        for (String securityGroupName : o.getDbSecurityGroupMemberships()) {
            xml.start("DBSecurityGroup")
               .elem("DBSecurityGroupName", securityGroupName)
               .elem("Status", "authorized")
               .end("DBSecurityGroup");
        }
        return xml.end("DBSecurityGroupMemberships").build();
    }

    private static String optionGroupMembershipsXml(DbInstance instance) {
        return new XmlBuilder().start("OptionGroupMemberships")
                .start("OptionGroupMembership")
                  .elem("OptionGroupName", instanceOptionGroupName(instance))
                  .elem("Status", "in-sync")
                .end("OptionGroupMembership")
                .end("OptionGroupMemberships")
                .build();
    }

    private static String instanceOptionGroupName(DbInstance instance) {
        String name = instance.getOptionGroupName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String engine = instance.getEngine() != null
                ? instance.getEngine().name().toLowerCase()
                : "unknown";
        String majorVersion = optionGroupMajorVersion(instance);
        return majorVersion.isEmpty()
                ? "default:" + engine
                : RdsService.defaultOptionGroupName(engine, majorVersion);
    }

    private static String optionGroupMajorVersion(DbInstance instance) {
        String engineVersion = instance.getEngineVersion();
        if ((engineVersion == null || engineVersion.isBlank()) && instance.getEngine() != null) {
            engineVersion = defaultEngineVersion(instance.getEngine().name());
        }
        String engine = instance.getEngine() == null ? null : instance.getEngine().name();
        return RdsService.optionGroupMajorVersion(engine, engineVersion);
    }

    private String clusterParamGroupXml(DbClusterParameterGroup g) {
        return new XmlBuilder().start("DBClusterParameterGroup").raw(clusterParamGroupInnerXml(g)).end("DBClusterParameterGroup").build();
    }

    private String clusterParamGroupInnerXml(DbClusterParameterGroup g) {
        return new XmlBuilder()
                .elem("DBClusterParameterGroupName", g.getDbClusterParameterGroupName())
                .elem("DBParameterGroupFamily", g.getDbParameterGroupFamily())
                .elem("Description", g.getDescription())
                .elem("DBClusterParameterGroupArn", g.getDbClusterParameterGroupArn())
                .build();
    }

    private String statusLabel(DbInstanceStatus status) {
        return switch (status) {
            case CREATING -> "creating";
            case AVAILABLE -> "available";
            case DELETING -> "deleting";
            case REBOOTING -> "rebooting";
            case MODIFYING -> "modifying";
            case FAILED -> "failed";
        };
    }

    /**
     * Extracts the first value for a named filter from RDS Query API encoded params:
     * {@code Filters.Filter.N.Name=filterName} / {@code Filters.Filter.N.Values.Value.1=value}.
     * Returns null if no matching filter is present.
     */
    private static final String DOCDB_ENGINE = "docdb";
    private static final String NEPTUNE_ENGINE = "neptune";

    /**
     * Every engine name the RDS family knows (the CreateDBInstance / CreateDBCluster lists in the
     * API reference, plus DocumentDB and Neptune, which share the API and whose records the list
     * form merges in). A live account refuses an {@code engine} filter naming anything else, and
     * answers an empty list for a known engine it holds nothing of — including ones Floci cannot
     * create.
     */
    private static final java.util.Set<String> KNOWN_ENGINES = java.util.Set.of(
            "aurora", "aurora-mysql", "aurora-postgresql", "mysql", "mariadb", "postgres",
            "custom-oracle-ee", "custom-oracle-ee-cdb", "custom-oracle-se2", "custom-oracle-se2-cdb",
            "custom-sqlserver-dev", "custom-sqlserver-ee", "custom-sqlserver-se", "custom-sqlserver-web",
            "db2-ae", "db2-se", "oracle-ee", "oracle-ee-cdb", "oracle-se2", "oracle-se2-cdb",
            "sqlserver-ee", "sqlserver-ex", "sqlserver-se", "sqlserver-web",
            DOCDB_ENGINE, NEPTUNE_ENGINE);

    /** The {@code engine} filter, lower-cased: a live account matches engine names case-insensitively. */
    private static List<String> engineFilter(MultivaluedMap<String, String> params) {
        List<String> engines = extractRdsFilterValues(params, "engine").stream().map(String::toLowerCase).toList();
        for (String engine : engines) {
            if (!KNOWN_ENGINES.contains(engine)) {
                throw new AwsException("InvalidParameterValue", "Unrecognized engine name: " + engine, 400);
            }
        }
        return engines;
    }

    private static String clusterEngine(DbCluster c) {
        String engine = c.getEngineIdentifier() != null
                ? c.getEngineIdentifier()
                : c.getEngine() != null ? c.getEngine().name() : "";
        return engine.toLowerCase();
    }

    /**
     * The engine name AWS reports for the instance: the one the request gave (an Aurora member
     * says aurora-postgresql, not postgres), or the enum for a record persisted before it was kept.
     */
    private static String instanceEngine(DbInstance i) {
        if (i.getEngineIdentifier() != null && !i.getEngineIdentifier().isBlank()) {
            return i.getEngineIdentifier().toLowerCase();
        }
        return i.getEngine() != null ? i.getEngine().name().toLowerCase() : "";
    }

    private static String extractRdsFilterValue(MultivaluedMap<String, String> params, String filterName) {
        List<String> values = extractRdsFilterValues(params, filterName);
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * Extracts all values for a named RDS filter. Values within one filter use
     * AWS OR semantics and are encoded as {@code Values.Value.N}.
     */
    private static List<String> extractRdsFilterValues(MultivaluedMap<String, String> params, String filterName) {
        for (int i = 1; ; i++) {
            String name = params.getFirst("Filters.Filter." + i + ".Name");
            if (name == null) {
                break;
            }
            if (filterName.equals(name)) {
                java.util.ArrayList<String> values = new java.util.ArrayList<>();
                for (int valueIndex = 1; ; valueIndex++) {
                    String value = params.getFirst("Filters.Filter." + i + ".Values.Value." + valueIndex);
                    if (value == null) {
                        break;
                    }
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
                return List.copyOf(values);
            }
        }
        return List.of();
    }

    private static List<String> memberList(MultivaluedMap<String, String> params, String baseName) {
        return params.keySet().stream()
                .filter(key -> key.matches(memberKeyRegex(baseName)))
                .sorted(java.util.Comparator.comparingInt(RdsQueryHandler::numericSuffix))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static boolean hasMemberKeys(MultivaluedMap<String, String> params, String baseName) {
        return params.keySet().stream().anyMatch(key -> key.matches(memberKeyRegex(baseName)));
    }

    private static String memberKeyRegex(String baseName) {
        String quoted = java.util.regex.Pattern.quote(baseName);
        return switch (baseName) {
            case "SubnetIds" -> quoted + "(\\.member|\\.SubnetIdentifier)?\\.\\d+";
            case "VpcSecurityGroupIds" -> quoted + "(\\.member|\\.VpcSecurityGroupId)?\\.\\d+";
            case "OptionsToRemove" -> quoted + "(\\.member|\\.OptionName)?\\.\\d+";
            default -> {
                // Option configurations nest their membership lists under an indexed prefix,
                // so the alternate member names have to be matched on the suffix.
                if (baseName.endsWith("VpcSecurityGroupMemberships")) {
                    yield quoted + "(\\.member|\\.VpcSecurityGroupId)?\\.\\d+";
                }
                if (baseName.endsWith("DBSecurityGroupMemberships")) {
                    yield quoted + "(\\.member|\\.DBSecurityGroupName)?\\.\\d+";
                }
                yield quoted + "(\\.member)?\\.\\d+";
            }
        };
    }

    private static int numericSuffix(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(dot + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<DbProxyAuth> parseProxyAuth(MultivaluedMap<String, String> params) {
        List<DbProxyAuth> auth = new java.util.ArrayList<>();
        for (int index = 1; ; index++) {
            String prefix = "Auth.member." + index + ".";
            String authScheme = params.getFirst(prefix + "AuthScheme");
            String secretArn = params.getFirst(prefix + "SecretArn");
            String iamAuth = params.getFirst(prefix + "IAMAuth");
            String passwordType = params.getFirst(prefix + "ClientPasswordAuthType");
            String description = params.getFirst(prefix + "Description");
            String userName = params.getFirst(prefix + "UserName");
            if (authScheme == null && secretArn == null && iamAuth == null
                    && passwordType == null && description == null && userName == null) {
                break;
            }
            DbProxyAuth entry = new DbProxyAuth(
                    authScheme, secretArn, iamAuth, passwordType, description);
            entry.setUserName(userName);
            auth.add(entry);
        }
        return auth;
    }

    private static boolean hasProxyAuthKeys(MultivaluedMap<String, String> params) {
        return params.keySet().stream().anyMatch(key -> key.startsWith("Auth.member."));
    }

    private static Boolean parseOptionalBoolean(
            MultivaluedMap<String, String> params, String parameterName) {
        String value = params.getFirst(parameterName);
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new AwsException("InvalidParameterValue",
                parameterName + " must be true or false.", 400);
    }

    private static Integer parseOptionalInteger(
            MultivaluedMap<String, String> params, String parameterName) {
        String value = params.getFirst(parameterName);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    parameterName + " must be an integer.", 400);
        }
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        readTags(params, "Tags.member", tags);
        readTags(params, "Tags.Tag", tags);
        readTags(params, "Tag", tags);
        return tags;
    }

    private static void readTags(MultivaluedMap<String, String> params, String prefix, Map<String, String> tags) {
        for (int i = 1; ; i++) {
            String key = params.getFirst(prefix + "." + i + ".Key");
            if (key == null) {
                break;
            }
            // A key given without a value is stored as an empty value, as AWS stores it —
            // a null would also break the immutable copy the read hands back.
            String value = params.getFirst(prefix + "." + i + ".Value");
            tags.put(key, value == null ? "" : value);
        }
    }

    private static int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String defaultEngineVersion(String engine) {
        if (engine == null) {
            return "16.3";
        }
        return switch (engine.toLowerCase()) {
            case "postgres", "aurora-postgresql" -> "16.3";
            case "mysql", "aurora-mysql", "aurora" -> "8.0.36";
            case "mariadb" -> "11.2";
            default -> "1.0";
        };
    }
}
