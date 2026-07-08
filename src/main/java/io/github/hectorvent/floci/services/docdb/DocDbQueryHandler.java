package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collection;

@ApplicationScoped
public class DocDbQueryHandler {

    private static final Logger LOG = Logger.getLogger(DocDbQueryHandler.class);

    private final DocDbService service;
    private final EmulatorConfig config;

    @Inject
    public DocDbQueryHandler(DocDbService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.infov("DocDB action: {0}", action);
        try {
            return switch (action) {
                case "CreateDBCluster"    -> handleCreateDbCluster(params);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params);
                case "DescribeDBClusterSnapshots" -> handleDescribeDbClusterSnapshots(params);
                case "DeleteDBCluster"    -> handleDeleteDbCluster(params);
                case "ModifyDBCluster"    -> handleModifyDbCluster(params);
                case "CreateDBInstance"   -> handleCreateDbInstance(params);
                case "DescribeDBInstances"-> handleDescribeDbInstances(params);
                case "DeleteDBInstance"   -> handleDeleteDbInstance(params);
                case "ModifyDBInstance"   -> handleModifyDbInstance(params);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported by DocDB.", AwsNamespaces.RDS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in DocDB {0}", action);
            return AwsQueryResponse.error("InternalFailure",
                    "An internal error occurred while processing the request.",
                    AwsNamespaces.RDS, 500);
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
        String masterUsername = params.getFirst("MasterUsername");
        String masterPassword = params.getFirst("MasterUserPassword");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));

        DocDbCluster cluster = service.createDbCluster(id, engineVersion,
                masterUsername, masterPassword, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    private Response handleDescribeDbClusters(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("DBClusterIdentifier");
        if (filterId == null || filterId.isBlank()) {
            filterId = extractFilterValue(params, "db-cluster-id");
        }

        // When a specific cluster ID is requested, AWS returns 404 if not found
        if (filterId != null && !filterId.isBlank()) {
            service.getDbCluster(filterId); // throws DBClusterNotFoundFault if absent
        }

        Collection<DocDbCluster> result = service.listDbClusters(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBClusters");
        for (DocDbCluster c : result) {
            xml.start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster");
        }
        xml.end("DBClusters").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusters", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDescribeDbClusterSnapshots(MultivaluedMap<String, String> params) {
        // Snapshots are not modeled by the emulator; return the wire-accurate empty result
        // the DocDB/RDS Query API uses (empty <DBClusterSnapshots/>, no <Marker>) so SDK
        // clients complete the read instead of failing on an unsupported action.
        XmlBuilder xml = new XmlBuilder().start("DBClusterSnapshots").end("DBClusterSnapshots");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterSnapshots", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDeleteDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        DocDbCluster cluster = service.getDbCluster(id);
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

        DocDbCluster cluster = service.modifyDbCluster(id, engineVersion, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("ModifyDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
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
                    "DBClusterIdentifier is required for DocDB instances.", AwsNamespaces.RDS, 400);
        }
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String engineVersion = params.getFirst("EngineVersion");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));

        DocDbInstance instance = service.createDbInstance(id, dbClusterIdentifier,
                dbInstanceClass, engineVersion, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    private Response handleDescribeDbInstances(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("DBInstanceIdentifier");
        if (filterId == null || filterId.isBlank()) {
            filterId = extractFilterValue(params, "db-instance-id");
        }

        if (filterId != null && !filterId.isBlank()) {
            service.getDbInstance(filterId); // throws DBInstanceNotFound if absent
        }

        Collection<DocDbInstance> result = service.listDbInstances(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBInstances");
        for (DocDbInstance i : result) {
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
        DocDbInstance instance = service.getDbInstance(id);
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

        DocDbInstance instance = service.modifyDbInstance(id, dbInstanceClass, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String clusterXml(DocDbCluster c) {
        return new XmlBuilder().start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster").build();
    }

    private String clusterInnerXml(DocDbCluster c) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterIdentifier", c.getDbClusterIdentifier())
                .elem("Status", c.getStatus())
                .elem("Engine", "docdb")
                .elem("EngineVersion", c.getEngineVersion())
                .elem("Endpoint", c.getEndpoint())
                .elem("ReaderEndpoint", c.getReaderEndpoint())
                .elem("Port", c.getPort())
                .elem("MasterUsername", c.getMasterUsername())
                .elem("IAMDatabaseAuthenticationEnabled", c.isIamDatabaseAuthenticationEnabled())
                .elem("MultiAZ", false)
                .elem("StorageEncrypted", true)
                .elem("AvailabilityZone", config.defaultAvailabilityZone())
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

    private String instanceXml(DocDbInstance i) {
        return new XmlBuilder().start("DBInstance").raw(instanceInnerXml(i)).end("DBInstance").build();
    }

    private String instanceInnerXml(DocDbInstance i) {
        return new XmlBuilder()
                .elem("DBInstanceIdentifier", i.getDbInstanceIdentifier())
                .elem("DBClusterIdentifier", i.getDbClusterIdentifier())
                .elem("DBInstanceClass", i.getDbInstanceClass())
                .elem("DBInstanceStatus", i.getStatus())
                .elem("Engine", "docdb")
                .elem("EngineVersion", i.getEngineVersion())
                .start("Endpoint")
                  .elem("Address", i.getEndpoint())
                  .elem("Port", i.getPort())
                .end("Endpoint")
                .elem("IAMDatabaseAuthenticationEnabled", i.isIamDatabaseAuthenticationEnabled())
                .elem("MultiAZ", false)
                .elem("StorageEncrypted", true)
                .elem("AvailabilityZone", config.defaultAvailabilityZone())
                .elem("DbiResourceId", i.getDbiResourceId())
                .elem("DBInstanceArn", i.getDbInstanceArn())
                .build();
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