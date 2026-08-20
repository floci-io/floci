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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                case "DescribeGlobalClusters" -> handleDescribeGlobalClusters(params);
                case "DeleteDBCluster"    -> handleDeleteDbCluster(params);
                case "ModifyDBCluster"    -> handleModifyDbCluster(params);
                case "CreateDBInstance"   -> handleCreateDbInstance(params);
                case "DescribeDBInstances"-> handleDescribeDbInstances(params);
                case "DeleteDBInstance"   -> handleDeleteDbInstance(params);
                case "ModifyDBInstance"   -> handleModifyDbInstance(params);
                case "ListTagsForResource" -> handleListTagsForResource(params);
                case "AddTagsToResource"   -> handleAddTagsToResource(params);
                case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(params);
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
        // Tags given at create are readable back on a live account, so they must not be dropped.
        Map<String, String> tags = parseTags(params);
        if (!tags.isEmpty()) {
            service.addTagsToResource(cluster.getDbClusterArn(), tags);
        }
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

        Collection<DocDbCluster> result = service.listDbClusters(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBClusters");
        for (DocDbCluster c : result) {
            xml.start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster");
        }
        xml.end("DBClusters").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusters", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDescribeGlobalClusters(MultivaluedMap<String, String> params) {
        // Global clusters are not modeled; an empty list is what completes the provider's read.
        // Real SDKs sign DocumentDB with the "rds" scope and land on RdsQueryHandler instead;
        // this serves the "docdb" scope Floci also accepts, and must answer the same way.
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
        XmlBuilder xml = new XmlBuilder().start("GlobalClusters").end("GlobalClusters");
        return Response.ok(AwsQueryResponse.envelope("DescribeGlobalClusters", AwsNamespaces.RDS, xml.build())).build();
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

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        // Filters are accepted without validation: a live account rejects an unrecognised filter
        // name, but Floci carries no list of the names it accepts, and the tags of one named
        // resource are the same answer either way.
        Map<String, String> tags = service.listTagsForResource(params.getFirst("ResourceName"));
        XmlBuilder xml = new XmlBuilder().start("TagList");
        tags.forEach((key, value) -> xml.start("Tag")
                .elem("Key", key)
                .elem("Value", value == null ? "" : value)
                .end("Tag"));
        xml.end("TagList");
        return Response.ok(AwsQueryResponse.envelope("ListTagsForResource",
                AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params) {
        service.addTagsToResource(params.getFirst("ResourceName"), parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("AddTagsToResource", AwsNamespaces.RDS, "")).build();
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params) {
        service.removeTagsFromResource(params.getFirst("ResourceName"), tagKeys(params));
        return Response.ok(AwsQueryResponse.envelope("RemoveTagsFromResource",
                AwsNamespaces.RDS, "")).build();
    }

    /** Every spelling the SDKs and the CLI use for a tag list, as RdsQueryHandler reads them. */
    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String prefix : List.of("Tags.member", "Tags.Tag", "Tag")) {
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
}
