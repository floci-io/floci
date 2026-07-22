package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTarget;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
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
    private final EmulatorConfig config;

    @Inject
    public RdsQueryHandler(RdsService service, EmulatorConfig config) {
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
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        String dbClusterIdentifier = params.getFirst("DBClusterIdentifier");
        boolean manageMasterUserPassword = "true".equalsIgnoreCase(params.getFirst("ManageMasterUserPassword"));
        String masterUserSecretKmsKeyId = params.getFirst("MasterUserSecretKmsKeyId");
        Map<String, String> tags = parseTags(params);
        String availabilityZone = params.getFirst("AvailabilityZone");
        boolean multiAz = "true".equalsIgnoreCase(params.getFirst("MultiAZ"));

        if (dbInstanceClass == null) {
            dbInstanceClass = "db.t3.micro";
        }
        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            List<String> vpcSecurityGroupIds = vpcSecurityGroupIds(params);
            DbInstance instance = service.createDbInstance(id, engine, engineVersion, masterUsername,
                    masterPassword, dbName, dbInstanceClass, allocatedStorage, iamEnabled,
                    paramGroupName, dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                    manageMasterUserPassword, masterUserSecretKmsKeyId, tags, vpcSecurityGroupIds, region);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbInstances(
            MultivaluedMap<String, String> params, String region) {
        String filterId = params.getFirst("DBInstanceIdentifier");
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-instance-id");
        }
        try {
            Collection<DbInstance> result = service.listDbInstances(filterId, region);
            XmlBuilder xml = new XmlBuilder().start("DBInstances");
            for (DbInstance i : result) {
                xml.start("DBInstance").raw(dbInstanceInnerXml(i)).end("DBInstance");
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
        try {
            List<String> vpcSecurityGroupIds = vpcSecurityGroupIds(params);
            DbInstance instance = service.modifyDbInstance(
                    id, newPassword, iamEnabled, dbSubnetGroupName,
                    vpcSecurityGroupIds, region);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
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
            DbSubnetGroup group = service.createDbSubnetGroup(name, description, subnetIds, region);
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
            DbCluster cluster = service.createDbCluster(id, engine, engineVersion, masterUsername,
                    masterPassword, databaseName, iamEnabled, paramGroupName,
                    dbSubnetGroupName, availabilityZone, multiAz, region);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusters(
            MultivaluedMap<String, String> params, String region) {
        String filterId = params.getFirst("DBClusterIdentifier");
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-cluster-id");
        }
        try {
            Collection<DbCluster> result = service.listDbClusters(filterId, region);
            XmlBuilder xml = new XmlBuilder().start("DBClusters");
            for (DbCluster c : result) {
                xml.start("DBCluster").raw(dbClusterInnerXml(c)).end("DBCluster");
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
            DbCluster cluster = service.modifyDbCluster(id, newPassword, iamEnabled, region);
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
        if (tgName == null || tgName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetGroupName is required.", 400);
        }
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
        if (targetGroupName == null || targetGroupName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetGroupName is required.", 400);
        }
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
        if (tgName == null || tgName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TargetGroupName is required.", 400);
        }
        XmlBuilder xml = new XmlBuilder().start("Targets");
        for (DbProxyTarget t : service.describeDbProxyTargets(name, tgName, region)) {
            xml.start("member").raw(dbProxyTargetInnerXml(t)).end("member");
        }
        xml.end("Targets");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBProxyTargets", AwsNamespaces.RDS, xml.build())).build();
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
        String engineStr = i.getEngine() != null ? i.getEngine().name() : "";
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
           .elem("StorageType", "gp2")
           .elem("PubliclyAccessible", false)
           .elem("AvailabilityZone", i.getAvailabilityZone() != null ? i.getAvailabilityZone() : config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", "mon:00:00-mon:03:00")
           .elem("PreferredBackupWindow", "04:00-06:00")
           .raw(vpcSecurityGroupsXml(i))
           .raw(dbParameterGroupsXml(i))
           .raw(dbSubnetGroupXml(dbSubnetGroupForInstance(i)))
           .elem("DbiResourceId", i.getDbiResourceId())
           .elem("DBInstanceArn", i.getDbInstanceArn());
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
        String engineStr = c.getEngine() != null ? c.getEngine().name() : "";
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
           .elem("DBClusterArn", c.getDbClusterArn())
           .start("DBClusterMembers");
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
                .build();
    }

    private String clusterParamGroupXml(DbClusterParameterGroup g) {
        return new XmlBuilder().start("DBClusterParameterGroup").raw(clusterParamGroupInnerXml(g)).end("DBClusterParameterGroup").build();
    }

    private String clusterParamGroupInnerXml(DbClusterParameterGroup g) {
        return new XmlBuilder()
                .elem("DBClusterParameterGroupName", g.getDbClusterParameterGroupName())
                .elem("DBParameterGroupFamily", g.getDbParameterGroupFamily())
                .elem("Description", g.getDescription())
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
    private static String extractRdsFilterValue(MultivaluedMap<String, String> params, String filterName) {
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
            default -> quoted + "(\\.member)?\\.\\d+";
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
            tags.put(key, params.getFirst(prefix + "." + i + ".Value"));
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
