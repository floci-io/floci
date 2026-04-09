package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashMap;
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
                case "CreateDBCluster" -> handleCreateDbCluster(params);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params);
                case "DeleteDBCluster" -> handleDeleteDbCluster(params);
                case "ModifyDBCluster" -> handleModifyDbCluster(params);
                case "CreateDBParameterGroup" -> handleCreateDbParameterGroup(params);
                case "DescribeDBParameterGroups" -> handleDescribeDbParameterGroups(params);
                case "DeleteDBParameterGroup" -> handleDeleteDbParameterGroup(params);
                case "ModifyDBParameterGroup" -> handleModifyDbParameterGroup(params);
                case "DescribeDBParameters" -> handleDescribeDbParameters(params);
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
        String dbClusterIdentifier = params.getFirst("DBClusterIdentifier");

        if (dbInstanceClass == null) {
            dbInstanceClass = "db.t3.micro";
        }
        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            DbInstance instance = service.createDbInstance(id, engine, engineVersion, masterUsername,
                    masterPassword, dbName, dbInstanceClass, allocatedStorage, iamEnabled,
                    paramGroupName, dbClusterIdentifier);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbInstances(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("DBInstanceIdentifier");
        try {
            Collection<DbInstance> result = service.listDbInstances(filterId);
            XmlBuilder xml = new XmlBuilder().start("DBInstances");
            for (DbInstance i : result) {
                xml.start("member").raw(dbInstanceInnerXml(i)).end("member");
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
        try {
            DbInstance instance = service.modifyDbInstance(id, newPassword, iamEnabled);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS, result)).build();
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
        try {
            Collection<DbCluster> result = service.listDbClusters(filterId);
            XmlBuilder xml = new XmlBuilder().start("DBClusters");
            for (DbCluster c : result) {
                xml.start("member").raw(dbClusterInnerXml(c)).end("member");
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
                xml.start("member").raw(paramGroupInnerXml(g)).end("member");
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
           .start("DBSubnetGroup")
             .elem("DBSubnetGroupName", "default")
             .elem("VpcId", "vpc-00000000")
             .elem("SubnetGroupStatus", "Complete")
             .start("Subnets")
               .start("member")
                 .elem("SubnetIdentifier", "subnet-00000000")
                 .start("SubnetAvailabilityZone")
                   .elem("Name", config.defaultAvailabilityZone())
                 .end("SubnetAvailabilityZone")
                 .elem("SubnetStatus", "Active")
               .end("member")
             .end("Subnets")
           .end("DBSubnetGroup")
           .elem("DbiResourceId", i.getDbiResourceId())
           .elem("DBInstanceArn", i.getDbInstanceArn());
        if (i.getDbClusterIdentifier() != null && !i.getDbClusterIdentifier().isBlank()) {
            xml.elem("DBClusterIdentifier", i.getDbClusterIdentifier());
        }
        return xml.build();
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
           .start("DBSubnetGroup")
             .elem("DBSubnetGroupName", "default")
             .elem("VpcId", "vpc-00000000")
             .elem("SubnetGroupStatus", "Complete")
             .start("Subnets")
               .start("member")
                 .elem("SubnetIdentifier", "subnet-00000000")
                 .start("SubnetAvailabilityZone")
                   .elem("Name", config.defaultAvailabilityZone())
                 .end("SubnetAvailabilityZone")
                 .elem("SubnetStatus", "Active")
               .end("member")
             .end("Subnets")
           .end("DBSubnetGroup")
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

    private String statusLabel(DbInstanceStatus status) {
        return switch (status) {
            case CREATING -> "creating";
            case AVAILABLE -> "available";
            case DELETING -> "deleting";
            case REBOOTING -> "rebooting";
            case MODIFYING -> "modifying";
        };
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
