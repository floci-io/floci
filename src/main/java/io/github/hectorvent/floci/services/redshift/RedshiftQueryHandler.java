package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.RedshiftCluster;
import io.github.hectorvent.floci.services.redshift.model.RedshiftParameter;
import io.github.hectorvent.floci.services.redshift.model.RedshiftParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.RedshiftSubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon Redshift Query-protocol handler (form-encoded POST, XML response).
 *
 * <p>Redshift signs with its own credential scope; before it was catalogued in
 * {@code ResolvedServiceCatalog} the requests fell through {@link
 * io.github.hectorvent.floci.core.common.AwsQueryController}'s action-name inference to
 * the SQS handler. Unimplemented actions return a clean {@code UnknownOperationException}
 * rather than a stub success.
 */
@ApplicationScoped
public class RedshiftQueryHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftQueryHandler.class);
    private static final String NS = AwsNamespaces.REDSHIFT;
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final RedshiftService service;

    @Inject
    RedshiftQueryHandler(RedshiftService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("Redshift action: {0}", action);
        return switch (action) {
            case "CreateCluster" -> createCluster(params, region);
            case "DescribeClusters" -> describeClusters(params, region);
            case "ModifyCluster" -> modifyCluster(params, region);
            case "DeleteCluster" -> deleteCluster(params, region);
            case "RebootCluster" -> rebootCluster(params, region);
            case "CreateClusterSubnetGroup" -> createSubnetGroup(params, region);
            case "DescribeClusterSubnetGroups" -> describeSubnetGroups(params, region);
            case "ModifyClusterSubnetGroup" -> modifySubnetGroup(params, region);
            case "DeleteClusterSubnetGroup" -> deleteSubnetGroup(params, region);
            case "CreateClusterParameterGroup" -> createParameterGroup(params, region);
            case "DescribeClusterParameterGroups" -> describeParameterGroups(params, region);
            case "ModifyClusterParameterGroup" -> modifyParameterGroup(params, region);
            case "DeleteClusterParameterGroup" -> deleteParameterGroup(params, region);
            case "DescribeClusterParameters" -> describeClusterParameters(params, region);
            case "CreateTags" -> createTags(params, region);
            case "DeleteTags" -> deleteTags(params, region);
            case "DescribeTags" -> describeTags(params, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    // ── clusters ──────────────────────────────────────────────────────────────

    private Response createCluster(MultivaluedMap<String, String> p, String region) {
        RedshiftCluster cluster = service.createCluster(region, scalars(p),
                stringList(p, "ClusterSecurityGroups", "ClusterSecurityGroupName"),
                stringList(p, "VpcSecurityGroupIds", "VpcSecurityGroupId"),
                stringList(p, "IamRoles", "IamRoleArn"),
                tags(p));
        return ok(AwsQueryResponse.envelope("CreateCluster", NS, clusterXml(cluster)));
    }

    private Response describeClusters(MultivaluedMap<String, String> p, String region) {
        List<RedshiftCluster> found = service.describeClusters(region, p.getFirst("ClusterIdentifier"),
                stringList(p, "TagKeys", "TagKey"), stringList(p, "TagValues", "TagValue"));
        XmlBuilder xml = new XmlBuilder().start("Clusters");
        for (RedshiftCluster cluster : found) {
            xml.raw(clusterXml(cluster));
        }
        xml.end("Clusters");
        return ok(AwsQueryResponse.envelope("DescribeClusters", NS, xml.build()));
    }

    private Response modifyCluster(MultivaluedMap<String, String> p, String region) {
        RedshiftCluster cluster = service.modifyCluster(region, scalars(p),
                stringList(p, "ClusterSecurityGroups", "ClusterSecurityGroupName"),
                stringList(p, "VpcSecurityGroupIds", "VpcSecurityGroupId"));
        return ok(AwsQueryResponse.envelope("ModifyCluster", NS, clusterXml(cluster)));
    }

    private Response deleteCluster(MultivaluedMap<String, String> p, String region) {
        RedshiftCluster cluster = service.deleteCluster(region, p.getFirst("ClusterIdentifier"));
        return ok(AwsQueryResponse.envelope("DeleteCluster", NS, clusterXml(cluster)));
    }

    private Response rebootCluster(MultivaluedMap<String, String> p, String region) {
        RedshiftCluster cluster = service.rebootCluster(region, p.getFirst("ClusterIdentifier"));
        return ok(AwsQueryResponse.envelope("RebootCluster", NS, clusterXml(cluster)));
    }

    // ── cluster subnet groups ─────────────────────────────────────────────────

    private Response createSubnetGroup(MultivaluedMap<String, String> p, String region) {
        RedshiftSubnetGroup group = service.createSubnetGroup(region,
                p.getFirst("ClusterSubnetGroupName"),
                p.getFirst("Description"),
                stringList(p, "SubnetIds", "SubnetIdentifier"),
                tags(p));
        return ok(AwsQueryResponse.envelope("CreateClusterSubnetGroup", NS, subnetGroupXml(group)));
    }

    private Response describeSubnetGroups(MultivaluedMap<String, String> p, String region) {
        List<RedshiftSubnetGroup> found = service.describeSubnetGroups(region,
                p.getFirst("ClusterSubnetGroupName"),
                stringList(p, "TagKeys", "TagKey"), stringList(p, "TagValues", "TagValue"));
        XmlBuilder xml = new XmlBuilder().start("ClusterSubnetGroups");
        for (RedshiftSubnetGroup group : found) {
            xml.raw(subnetGroupXml(group));
        }
        xml.end("ClusterSubnetGroups");
        return ok(AwsQueryResponse.envelope("DescribeClusterSubnetGroups", NS, xml.build()));
    }

    private Response modifySubnetGroup(MultivaluedMap<String, String> p, String region) {
        RedshiftSubnetGroup group = service.modifySubnetGroup(region,
                p.getFirst("ClusterSubnetGroupName"),
                p.getFirst("Description"),
                stringList(p, "SubnetIds", "SubnetIdentifier"));
        return ok(AwsQueryResponse.envelope("ModifyClusterSubnetGroup", NS, subnetGroupXml(group)));
    }

    private Response deleteSubnetGroup(MultivaluedMap<String, String> p, String region) {
        service.deleteSubnetGroup(region, p.getFirst("ClusterSubnetGroupName"));
        return ok(AwsQueryResponse.envelopeNoResult("DeleteClusterSubnetGroup", NS));
    }

    // ── cluster parameter groups ──────────────────────────────────────────────

    private Response createParameterGroup(MultivaluedMap<String, String> p, String region) {
        RedshiftParameterGroup group = service.createParameterGroup(region,
                p.getFirst("ParameterGroupName"),
                p.getFirst("ParameterGroupFamily"),
                p.getFirst("Description"),
                tags(p));
        return ok(AwsQueryResponse.envelope("CreateClusterParameterGroup", NS, parameterGroupXml(group)));
    }

    private Response describeParameterGroups(MultivaluedMap<String, String> p, String region) {
        List<RedshiftParameterGroup> found = service.describeParameterGroups(region,
                p.getFirst("ParameterGroupName"),
                stringList(p, "TagKeys", "TagKey"), stringList(p, "TagValues", "TagValue"));
        XmlBuilder xml = new XmlBuilder().start("ParameterGroups");
        for (RedshiftParameterGroup group : found) {
            xml.raw(parameterGroupXml(group));
        }
        xml.end("ParameterGroups");
        return ok(AwsQueryResponse.envelope("DescribeClusterParameterGroups", NS, xml.build()));
    }

    private Response modifyParameterGroup(MultivaluedMap<String, String> p, String region) {
        RedshiftParameterGroup group = service.modifyParameterGroup(region,
                p.getFirst("ParameterGroupName"), parameters(p));
        String result = new XmlBuilder()
                .elem("ParameterGroupName", group.getParameterGroupName())
                .elem("ParameterGroupStatus", "Your parameter group has been updated but changes won't get "
                        + "applied until you reboot the associated Clusters.")
                .build();
        return ok(AwsQueryResponse.envelope("ModifyClusterParameterGroup", NS, result));
    }

    private Response deleteParameterGroup(MultivaluedMap<String, String> p, String region) {
        service.deleteParameterGroup(region, p.getFirst("ParameterGroupName"));
        return ok(AwsQueryResponse.envelopeNoResult("DeleteClusterParameterGroup", NS));
    }

    private Response describeClusterParameters(MultivaluedMap<String, String> p, String region) {
        List<RedshiftParameter> found = service.describeParameters(region,
                p.getFirst("ParameterGroupName"), p.getFirst("Source"));
        XmlBuilder xml = new XmlBuilder().start("Parameters");
        for (RedshiftParameter parameter : found) {
            xml.start("Parameter")
                    .elem("ParameterName", parameter.getParameterName())
                    .elem("ParameterValue", parameter.getParameterValue())
                    .elem("Description", parameter.getDescription())
                    .elem("Source", parameter.getSource())
                    .elem("DataType", parameter.getDataType())
                    .elem("AllowedValues", parameter.getAllowedValues())
                    .elem("ApplyType", parameter.getApplyType())
                    .elem("IsModifiable", parameter.isModifiable())
                    .elem("MinimumEngineVersion", parameter.getMinimumEngineVersion())
               .end("Parameter");
        }
        xml.end("Parameters");
        return ok(AwsQueryResponse.envelope("DescribeClusterParameters", NS, xml.build()));
    }

    // ── tags ──────────────────────────────────────────────────────────────────

    private Response createTags(MultivaluedMap<String, String> p, String region) {
        service.createTags(region, p.getFirst("ResourceName"), tags(p));
        return ok(AwsQueryResponse.envelopeNoResult("CreateTags", NS));
    }

    private Response deleteTags(MultivaluedMap<String, String> p, String region) {
        service.deleteTags(region, p.getFirst("ResourceName"), stringList(p, "TagKeys", "TagKey"));
        return ok(AwsQueryResponse.envelopeNoResult("DeleteTags", NS));
    }

    private Response describeTags(MultivaluedMap<String, String> p, String region) {
        List<RedshiftService.TaggedResourceEntry> entries = service.describeTags(region,
                p.getFirst("ResourceName"), p.getFirst("ResourceType"),
                stringList(p, "TagKeys", "TagKey"), stringList(p, "TagValues", "TagValue"));
        XmlBuilder xml = new XmlBuilder().start("TaggedResources");
        for (RedshiftService.TaggedResourceEntry entry : entries) {
            xml.start("TaggedResource")
                    .start("Tag")
                      .elem("Key", entry.key())
                      .elem("Value", entry.value())
                    .end("Tag")
                    .elem("ResourceName", entry.resourceName())
                    .elem("ResourceType", entry.resourceType())
               .end("TaggedResource");
        }
        xml.end("TaggedResources");
        return ok(AwsQueryResponse.envelope("DescribeTags", NS, xml.build()));
    }

    // ── XML fragments ─────────────────────────────────────────────────────────

    private String clusterXml(RedshiftCluster cluster) {
        XmlBuilder xml = new XmlBuilder()
                .start("Cluster")
                  .elem("ClusterIdentifier", cluster.getClusterIdentifier())
                  .elem("NodeType", cluster.getNodeType())
                  .elem("ClusterStatus", cluster.getClusterStatus())
                  .elem("ClusterAvailabilityStatus", cluster.getClusterAvailabilityStatus())
                  .elem("ModifyStatus", cluster.getModifyStatus())
                  .elem("MasterUsername", cluster.getMasterUsername())
                  .elem("DBName", cluster.getDbName())
                  .start("Endpoint")
                    .elem("Address", cluster.getEndpointAddress())
                    .elem("Port", cluster.getEndpointPort())
                    .start("VpcEndpoints").end("VpcEndpoints")
                  .end("Endpoint")
                  .elem("ClusterCreateTime", ISO_FMT.format(cluster.getClusterCreateTime()))
                  .elem("AutomatedSnapshotRetentionPeriod", cluster.getAutomatedSnapshotRetentionPeriod())
                  .elem("ManualSnapshotRetentionPeriod", cluster.getManualSnapshotRetentionPeriod())
                  .start("ClusterSecurityGroups");
        for (String name : cluster.getClusterSecurityGroups()) {
            xml.start("ClusterSecurityGroup")
                    .elem("ClusterSecurityGroupName", name)
                    .elem("Status", "active")
               .end("ClusterSecurityGroup");
        }
        xml.end("ClusterSecurityGroups").start("VpcSecurityGroups");
        for (String id : cluster.getVpcSecurityGroupIds()) {
            xml.start("VpcSecurityGroup")
                    .elem("VpcSecurityGroupId", id)
                    .elem("Status", "active")
               .end("VpcSecurityGroup");
        }
        xml.end("VpcSecurityGroups").start("ClusterParameterGroups");
        for (String name : cluster.getClusterParameterGroups()) {
            xml.start("ClusterParameterGroup")
                    .elem("ParameterGroupName", name)
                    .elem("ParameterApplyStatus", "in-sync")
                    .start("ClusterParameterStatusList").end("ClusterParameterStatusList")
               .end("ClusterParameterGroup");
        }
        xml.end("ClusterParameterGroups")
                  .elem("ClusterSubnetGroupName", cluster.getClusterSubnetGroupName())
                  .elem("VpcId", cluster.getVpcId())
                  .elem("AvailabilityZone", cluster.getAvailabilityZone())
                  .elem("PreferredMaintenanceWindow", cluster.getPreferredMaintenanceWindow())
                  .start("PendingModifiedValues").end("PendingModifiedValues")
                  .elem("ClusterVersion", cluster.getClusterVersion())
                  .elem("AllowVersionUpgrade", cluster.isAllowVersionUpgrade())
                  .elem("NumberOfNodes", cluster.getNumberOfNodes())
                  .elem("PubliclyAccessible", cluster.isPubliclyAccessible())
                  .elem("Encrypted", cluster.isEncrypted())
                  .elem("ClusterRevisionNumber", cluster.getClusterRevisionNumber())
                  .elem("KmsKeyId", cluster.getKmsKeyId())
                  .elem("EnhancedVpcRouting", cluster.isEnhancedVpcRouting())
                  .elem("ElasticIp", cluster.getElasticIp())
                  .elem("DefaultIamRoleArn", cluster.getDefaultIamRoleArn())
                  .elem("MaintenanceTrackName", cluster.getMaintenanceTrackName())
                  .elem("ClusterNamespaceArn", cluster.getClusterNamespaceArn())
                  .elem("IpAddressType", cluster.getIpAddressType())
                  .elem("MultiAZ", cluster.getMultiAz())
                  .elem("AvailabilityZoneRelocationStatus", cluster.getAvailabilityZoneRelocationStatus())
                  .start("IamRoles");
        for (String roleArn : cluster.getIamRoles()) {
            xml.start("ClusterIamRole")
                    .elem("IamRoleArn", roleArn)
                    .elem("ApplyStatus", "in-sync")
               .end("ClusterIamRole");
        }
        xml.end("IamRoles")
                  .start("PendingActions").end("PendingActions")
                  .start("DeferredMaintenanceWindows").end("DeferredMaintenanceWindows")
                  .start("ClusterNodes");
        appendClusterNodes(xml, cluster);
        xml.end("ClusterNodes")
           .raw(tagsXml(cluster.getTags()))
           .end("Cluster");
        return xml.build();
    }

    /**
     * Node roles follow directly from the cluster type and node count. The per-node
     * addresses are not modelled — floci provisions no Redshift compute — so they are
     * left out rather than fabricated.
     */
    private static void appendClusterNodes(XmlBuilder xml, RedshiftCluster cluster) {
        if ("single-node".equals(cluster.getClusterType())) {
            xml.start("member").elem("NodeRole", "SHARED").end("member");
            return;
        }
        xml.start("member").elem("NodeRole", "LEADER").end("member");
        for (int i = 0; i < cluster.getNumberOfNodes(); i++) {
            xml.start("member").elem("NodeRole", "COMPUTE").end("member");
        }
    }

    private String subnetGroupXml(RedshiftSubnetGroup group) {
        XmlBuilder xml = new XmlBuilder()
                .start("ClusterSubnetGroup")
                  .elem("ClusterSubnetGroupName", group.getClusterSubnetGroupName())
                  .elem("Description", group.getDescription())
                  .elem("VpcId", group.getVpcId())
                  .elem("SubnetGroupStatus", group.getSubnetGroupStatus())
                  .start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            xml.start("Subnet")
                    .elem("SubnetIdentifier", subnetId)
                    .start("SubnetAvailabilityZone")
                      .elem("Name", group.getSubnetAvailabilityZones().getOrDefault(subnetId, ""))
                    .end("SubnetAvailabilityZone")
                    .elem("SubnetStatus", "Active")
               .end("Subnet");
        }
        xml.end("Subnets").start("SupportedClusterIpAddressTypes");
        for (String type : group.getSupportedClusterIpAddressTypes()) {
            xml.elem("item", type);
        }
        xml.end("SupportedClusterIpAddressTypes")
           .raw(tagsXml(group.getTags()))
           .end("ClusterSubnetGroup");
        return xml.build();
    }

    private String parameterGroupXml(RedshiftParameterGroup group) {
        return new XmlBuilder()
                .start("ClusterParameterGroup")
                  .elem("ParameterGroupName", group.getParameterGroupName())
                  .elem("ParameterGroupFamily", group.getParameterGroupFamily())
                  .elem("Description", group.getDescription())
                  .raw(tagsXml(group.getTags()))
                .end("ClusterParameterGroup")
                .build();
    }

    private static String tagsXml(Map<String, String> tags) {
        XmlBuilder xml = new XmlBuilder().start("Tags");
        tags.forEach((key, value) -> xml.start("Tag")
                .elem("Key", key)
                .elem("Value", value == null ? "" : value)
                .end("Tag"));
        return xml.end("Tags").build();
    }

    // ── form-encoded request parsing ──────────────────────────────────────────

    private static Map<String, String> scalars(MultivaluedMap<String, String> p) {
        Map<String, String> flat = new LinkedHashMap<>();
        p.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                flat.put(key, values.getFirst());
            }
        });
        return flat;
    }

    /**
     * Reads a Query-protocol list. Redshift's model names every list member
     * ({@code SubnetIds.SubnetIdentifier.1}); the {@code .member.} spelling is accepted as
     * well because hand-rolled clients and older SDKs emit it.
     */
    private static List<String> stringList(MultivaluedMap<String, String> p, String field, String memberName) {
        List<String> values = readIndexed(p, field, memberName);
        return values.isEmpty() ? readIndexed(p, field, "member") : values;
    }

    private static List<String> readIndexed(MultivaluedMap<String, String> p, String field, String memberName) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = p.getFirst(field + "." + memberName + "." + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static Map<String, String> tags(MultivaluedMap<String, String> p) {
        Map<String, String> tags = readTags(p, "Tag");
        return tags.isEmpty() ? readTags(p, "member") : tags;
    }

    private static Map<String, String> readTags(MultivaluedMap<String, String> p, String memberName) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String prefix = "Tags." + memberName + "." + i + ".";
            String key = p.getFirst(prefix + "Key");
            String value = p.getFirst(prefix + "Value");
            if (key == null && value == null) {
                break;
            }
            if (key != null) {
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private static List<RedshiftParameter> parameters(MultivaluedMap<String, String> p) {
        List<RedshiftParameter> parameters = readParameters(p, "Parameter");
        return parameters.isEmpty() ? readParameters(p, "member") : parameters;
    }

    private static List<RedshiftParameter> readParameters(MultivaluedMap<String, String> p, String memberName) {
        List<RedshiftParameter> parameters = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "Parameters." + memberName + "." + i + ".";
            String name = p.getFirst(prefix + "ParameterName");
            String value = p.getFirst(prefix + "ParameterValue");
            if (name == null && value == null) {
                break;
            }
            RedshiftParameter parameter = new RedshiftParameter();
            parameter.setParameterName(name);
            parameter.setParameterValue(value);
            parameter.setApplyType(p.getFirst(prefix + "ApplyType"));
            parameter.setDataType(p.getFirst(prefix + "DataType"));
            parameters.add(parameter);
        }
        return parameters;
    }

    private static Response ok(String xml) {
        return Response.ok(xml).build();
    }
}
