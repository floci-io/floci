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
        LOG.infov("RDS action: {0}", action);
        try {
            return switch (action) {
                case "CreateDBInstance" -> handleCreateDbInstance(params);
                case "DescribeDBInstances" -> handleDescribeDbInstances(params);
                case "DeleteDBInstance" -> handleDeleteDbInstance(params);
                case "ModifyDBInstance" -> handleModifyDbInstance(params);
                case "RebootDBInstance" -> handleRebootDbInstance(params);
                case "DescribeOrderableDBInstanceOptions" -> handleDescribeOrderableDbInstanceOptions(params);
                case "CreateDBSubnetGroup" -> handleCreateDbSubnetGroup(params);
                case "DescribeDBSubnetGroups" -> handleDescribeDbSubnetGroups(params);
                case "ModifyDBSubnetGroup" -> handleModifyDbSubnetGroup(params);
                case "DeleteDBSubnetGroup" -> handleDeleteDbSubnetGroup(params);
                case "CreateDBCluster" -> handleCreateDbCluster(params);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params);
                case "DeleteDBCluster" -> handleDeleteDbCluster(params);
                case "ModifyDBCluster" -> handleModifyDbCluster(params);
                case "CreateDBParameterGroup" -> handleCreateDbParameterGroup(params);
                case "DescribeDBParameterGroups" -> handleDescribeDbParameterGroups(params);
                case "DeleteDBParameterGroup" -> handleDeleteDbParameterGroup(params);
                case "ModifyDBParameterGroup" -> handleModifyDbParameterGroup(params);
                case "DescribeDBParameters" -> handleDescribeDbParameters(params);
                case "CreateDBClusterParameterGroup" -> handleCreateDbClusterParameterGroup(params);
                case "DescribeDBClusterParameterGroups" -> handleDescribeDbClusterParameterGroups(params);
                case "DeleteDBClusterParameterGroup" -> handleDeleteDbClusterParameterGroup(params);
                case "ModifyDBClusterParameterGroup" -> handleModifyDbClusterParameterGroup(params);
                case "DescribeDBClusterParameters" -> handleDescribeDbClusterParameters(params);
                case "AddTagsToResource" -> handleAddTagsToResource(params);
                case "ListTagsForResource" -> handleListTagsForResource(params);
                case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(params);
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

    private Response handleCreateDbInstance(MultivaluedMap<String, String> params) {
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

        if (dbInstanceClass == null) {
            dbInstanceClass = "db.t3.micro";
        }
        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            DbInstance instance = service.createDbInstance(id, engine, engineVersion, masterUsername,
                    masterPassword, dbName, dbInstanceClass, allocatedStorage, iamEnabled,
                    paramGroupName, dbSubnetGroupName, dbClusterIdentifier,
                    manageMasterUserPassword, masterUserSecretKmsKeyId, tags);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbInstances(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("DBInstanceIdentifier");
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-instance-id");
        }
        try {
            Collection<DbInstance> result = service.listDbInstances(filterId);
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

    private Response handleDeleteDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbInstance instance = service.getDbInstance(id);
            service.deleteDbInstance(id);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String newPassword = params.getFirst("MasterUserPassword");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        try {
            DbInstance instance = service.modifyDbInstance(id, newPassword, iamEnabled, dbSubnetGroupName);
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

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params) {
        String resourceName = params.getFirst("ResourceName");
        try {
            service.addTagsToResource(resourceName, parseTags(params));
            return Response.ok(AwsQueryResponse.envelope("AddTagsToResource", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        String resourceName = params.getFirst("ResourceName");
        try {
            XmlBuilder xml = new XmlBuilder().start("TagList");
            writeTags(xml, service.listTagsForResource(resourceName));
            xml.end("TagList");
            return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params) {
        String resourceName = params.getFirst("ResourceName");
        try {
            service.removeTagsFromResource(resourceName, memberList(params, "TagKeys"));
            return Response.ok(AwsQueryResponse.envelope("RemoveTagsFromResource", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleCreateDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        String description = params.getFirst("DBSubnetGroupDescription");
        List<String> subnetIds = memberList(params, "SubnetIds");
        try {
            DbSubnetGroup group = service.createDbSubnetGroup(name, description, subnetIds);
            return Response.ok(AwsQueryResponse.envelope("CreateDBSubnetGroup",
                    AwsNamespaces.RDS, dbSubnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbSubnetGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBSubnetGroupName");
        try {
            Collection<DbSubnetGroup> result = service.listDbSubnetGroups(filterName);
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

    private Response handleModifyDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        List<String> subnetIds = memberList(params, "SubnetIds");
        try {
            DbSubnetGroup group = service.modifyDbSubnetGroup(name, subnetIds);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBSubnetGroup",
                    AwsNamespaces.RDS, dbSubnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbSubnetGroup(name);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBSubnetGroup", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleRebootDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbInstance instance = service.rebootDbInstance(id);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("RebootDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── DB Clusters ───────────────────────────────────────────────────────────

    private Response handleCreateDbCluster(MultivaluedMap<String, String> params) {
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

        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            DbCluster cluster = service.createDbCluster(id, engine, engineVersion, masterUsername,
                    masterPassword, databaseName, iamEnabled, paramGroupName);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusters(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("DBClusterIdentifier");
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-cluster-id");
        }
        try {
            Collection<DbCluster> result = service.listDbClusters(filterId);
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

    private Response handleDeleteDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbCluster cluster = service.getDbCluster(id);
            service.deleteDbCluster(id);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String newPassword = params.getFirst("MasterUserPassword");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;
        try {
            DbCluster cluster = service.modifyDbCluster(id, newPassword, iamEnabled);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Parameter Groups ──────────────────────────────────────────────────────

    private Response handleCreateDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbParameterGroup group = service.createDbParameterGroup(name, family, description);
            String result = paramGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbParameterGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBParameterGroupName");
        try {
            Collection<DbParameterGroup> result = service.listDbParameterGroups(filterName);
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

    private Response handleDeleteDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbParameterGroup(name);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBParameterGroup", AwsNamespaces.RDS)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbParameterGroup(MultivaluedMap<String, String> params) {
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
            DbParameterGroup group = service.modifyDbParameterGroup(name, parameters);
            String result = new XmlBuilder()
                    .elem("DBParameterGroupName", group.getDbParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ModifyDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbParameterGroup group = service.getDbParameterGroup(name);
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

    private Response handleCreateDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbClusterParameterGroup group = service.createDbClusterParameterGroup(name, family, description);
            String result = clusterParamGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterParameterGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBClusterParameterGroupName");
        try {
            Collection<DbClusterParameterGroup> result = service.listDbClusterParameterGroups(filterName);
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

    private Response handleDeleteDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbClusterParameterGroup(name);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBClusterParameterGroup", AwsNamespaces.RDS)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbClusterParameterGroup(MultivaluedMap<String, String> params) {
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
            DbClusterParameterGroup group = service.modifyDbClusterParameterGroup(name, parameters);
            String result = new XmlBuilder()
                    .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ModifyDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbClusterParameterGroup group = service.getDbClusterParameterGroup(name);
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
           .elem("MultiAZ", false)
           .elem("StorageType", "gp2")
           .elem("PubliclyAccessible", false)
           .elem("AvailabilityZone", config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", "mon:00:00-mon:03:00")
           .elem("PreferredBackupWindow", "04:00-06:00")
           .start("VpcSecurityGroups")
             .start("VpcSecurityGroupMembership")
               .elem("VpcSecurityGroupId", "sg-00000000")
               .elem("Status", "active")
             .end("VpcSecurityGroupMembership")
           .end("VpcSecurityGroups")
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

    private String dbSubnetGroupXml(DbSubnetGroup group) {
        return new XmlBuilder().start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(group)).end("DBSubnetGroup").build();
    }

    private String dbSubnetGroupInnerXml(DbSubnetGroup group) {
        XmlBuilder xml = new XmlBuilder()
           .elem("DBSubnetGroupName", group.getDbSubnetGroupName())
           .elem("DBSubnetGroupDescription", group.getDbSubnetGroupDescription())
           .elem("VpcId", group.getVpcId() != null ? group.getVpcId() : "vpc-00000000")
           .elem("SubnetGroupStatus", "Complete")
           .start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            xml.start("Subnet")
               .elem("SubnetIdentifier", subnetId)
               .start("SubnetAvailabilityZone")
                 .elem("Name", config.defaultAvailabilityZone())
               .end("SubnetAvailabilityZone")
               .elem("SubnetStatus", "Active")
               .end("Subnet");
        }
        return xml.end("Subnets").build();
    }

    private DbSubnetGroup dbSubnetGroupForInstance(DbInstance instance) {
        String groupName = instance.getDbSubnetGroupName();
        if (groupName != null && !groupName.isBlank()) {
            try {
                return service.getDbSubnetGroup(groupName);
            } catch (AwsException ignored) {
                return new DbSubnetGroup(groupName, "DB subnet group " + groupName,
                        "vpc-00000000", List.of("subnet-00000000"));
            }
        }
        return new DbSubnetGroup("default", "default", "vpc-00000000", List.of("subnet-00000000"));
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
           .elem("MultiAZ", false)
           .elem("AvailabilityZone", config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", "mon:00:00-mon:03:00")
           .elem("PreferredBackupWindow", "04:00-06:00")
           .start("VpcSecurityGroups")
             .start("VpcSecurityGroupMembership")
               .elem("VpcSecurityGroupId", "sg-00000000")
               .elem("Status", "active")
             .end("VpcSecurityGroupMembership")
           .end("VpcSecurityGroups")
           .elem("DBSubnetGroup", "default")
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
                .filter(key -> key.matches(java.util.regex.Pattern.quote(baseName)
                        + "(\\.member|\\.SubnetIdentifier)?\\.\\d+"))
                .sorted(java.util.Comparator.comparingInt(RdsQueryHandler::numericSuffix))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
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
