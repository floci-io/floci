package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftQueryHandler {
    private final RedshiftService service;

    @Inject
    public RedshiftQueryHandler(RedshiftService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        switch (action) {
        case "CreateCluster" -> {
            String identifier = params.getFirst("ClusterIdentifier");
            String nodeType = params.getFirst("NodeType");
            String masterUsername = params.getFirst("MasterUsername");
            String masterUserPassword = params.getFirst("MasterUserPassword");
            String clusterSubnetGroupName = params.getFirst("ClusterSubnetGroupName");
            List<String> vpcSecurityGroupIds = memberList(params, "VpcSecurityGroupIds");

            Cluster cluster = service.createCluster(identifier, nodeType, masterUsername, masterUserPassword,
                    clusterSubnetGroupName, vpcSecurityGroupIds);
            String xml = new XmlBuilder()
                    .start("CreateClusterResponse")
                      .start("CreateClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("CreateClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusters" -> {
            String identifier = params.getFirst("ClusterIdentifier");
            List<Cluster> clusters = service.describeClusters(identifier);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClustersResponse")
                      .start("DescribeClustersResult")
                        .start("Clusters");
            for (Cluster cluster : clusters) {
                xmlBuilder.raw(buildClusterXml(cluster));
            }
            String xml = xmlBuilder
                        .end("Clusters")
                      .end("DescribeClustersResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClustersResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteCluster" -> {
            String identifier = params.getFirst("ClusterIdentifier");
            Cluster cluster = service.deleteCluster(identifier);
            String xml = new XmlBuilder()
                    .start("DeleteClusterResponse")
                      .start("DeleteClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("DeleteClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateClusterSnapshot" -> {
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            Snapshot snapshot = service.createSnapshot(snapshotIdentifier, clusterIdentifier);
            String xml = new XmlBuilder()
                    .start("CreateClusterSnapshotResponse")
                      .start("CreateClusterSnapshotResult")
                        .raw(buildSnapshotXml(snapshot))
                      .end("CreateClusterSnapshotResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterSnapshotResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterSnapshots" -> {
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            List<Snapshot> snapshots = service.describeSnapshots(snapshotIdentifier, clusterIdentifier);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterSnapshotsResponse")
                      .start("DescribeClusterSnapshotsResult")
                        .start("Snapshots");
            for (Snapshot snapshot : snapshots) {
                xmlBuilder.raw(buildSnapshotXml(snapshot));
            }
            String xml = xmlBuilder
                        .end("Snapshots")
                      .end("DescribeClusterSnapshotsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterSnapshotsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteClusterSnapshot" -> {
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            Snapshot snapshot = service.deleteSnapshot(snapshotIdentifier);
            String xml = new XmlBuilder()
                    .start("DeleteClusterSnapshotResponse")
                      .start("DeleteClusterSnapshotResult")
                        .raw(buildSnapshotXml(snapshot))
                      .end("DeleteClusterSnapshotResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterSnapshotResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "RestoreFromClusterSnapshot" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
            String nodeType = params.getFirst("NodeType");
            Cluster cluster = service.restoreFromClusterSnapshot(clusterIdentifier, snapshotIdentifier, nodeType);
            String xml = new XmlBuilder()
                    .start("RestoreFromClusterSnapshotResponse")
                      .start("RestoreFromClusterSnapshotResult")
                        .raw(buildClusterXml(cluster))
                      .end("RestoreFromClusterSnapshotResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("RestoreFromClusterSnapshotResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateClusterParameterGroup" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            String parameterGroupFamily = params.getFirst("ParameterGroupFamily");
            String description = params.getFirst("Description");
            ClusterParameterGroup group = service.createClusterParameterGroup(parameterGroupName, parameterGroupFamily, description);
            String xml = new XmlBuilder()
                    .start("CreateClusterParameterGroupResponse")
                      .start("CreateClusterParameterGroupResult")
                        .raw(buildClusterParameterGroupXml(group))
                      .end("CreateClusterParameterGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterParameterGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterParameterGroups" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            List<ClusterParameterGroup> groups = service.describeClusterParameterGroups(parameterGroupName);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterParameterGroupsResponse")
                      .start("DescribeClusterParameterGroupsResult")
                        .start("ParameterGroups");
            for (ClusterParameterGroup group : groups) {
                xmlBuilder.raw(buildClusterParameterGroupXml(group));
            }
            String xml = xmlBuilder
                        .end("ParameterGroups")
                      .end("DescribeClusterParameterGroupsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterParameterGroupsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterParameters" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            if (parameterGroupName == null || parameterGroupName.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ParameterGroupName is required", 400);
            }
            List<Parameter> parameters = service.describeClusterParameters(parameterGroupName);

            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterParametersResponse")
                      .start("DescribeClusterParametersResult")
                        .start("Parameters");
            for (Parameter param : parameters) {
                xmlBuilder.raw(buildParameterXml(param));
            }
            String xml = xmlBuilder.end("Parameters")
                      .end("DescribeClusterParametersResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterParametersResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyClusterParameterGroup" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            if (parameterGroupName == null || parameterGroupName.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ParameterGroupName is required", 400);
            }
            List<Parameter> updates = parseParameters(params);
            service.modifyClusterParameterGroup(parameterGroupName, updates);
            String xml = new XmlBuilder()
                    .start("ModifyClusterParameterGroupResponse")
                      .start("ModifyClusterParameterGroupResult")
                        .elem("ParameterGroupName", parameterGroupName)
                        .elem("ParameterGroupStatus", "pending-reboot")
                      .end("ModifyClusterParameterGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterParameterGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteClusterParameterGroup" -> {
            String parameterGroupName = params.getFirst("ParameterGroupName");
            service.deleteClusterParameterGroup(parameterGroupName);
            String xml = new XmlBuilder()
                    .start("DeleteClusterParameterGroupResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterParameterGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateTags" -> {
            String resourceName = params.getFirst("ResourceName");
            service.createTags(resourceName, parseTags(params));
            String xml = new XmlBuilder()
                    .start("CreateTagsResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateTagsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteTags" -> {
            String resourceName = params.getFirst("ResourceName");
            List<String> tagKeys = memberList(params, "TagKeys");
            service.deleteTags(resourceName, tagKeys);
            String xml = new XmlBuilder()
                    .start("DeleteTagsResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteTagsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeTags" -> {
            String resourceName = params.getFirst("ResourceName");
            String resourceType = params.getFirst("ResourceType");
            List<String> tagKeys = memberList(params, "TagKeys");
            List<RedshiftService.TaggedResource> tagged = service.describeTags(resourceName, resourceType, tagKeys);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeTagsResponse")
                      .start("DescribeTagsResult")
                        .start("TaggedResources");
            for (RedshiftService.TaggedResource t : tagged) {
                xmlBuilder.start("TaggedResource")
                        .elem("ResourceName", t.resourceName())
                        .elem("ResourceType", t.resourceType())
                        .start("Tag")
                          .elem("Key", t.tagKey())
                          .elem("Value", t.tagValue())
                        .end("Tag")
                      .end("TaggedResource");
            }
            String xml = xmlBuilder
                        .end("TaggedResources")
                      .end("DescribeTagsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeTagsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "CreateClusterSubnetGroup" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterSubnetGroupName is required", 400);
            }
            String description = params.getFirst("Description");
            List<String> subnetIds = memberList(params, "SubnetIds");
            ClusterSubnetGroup group = service.createClusterSubnetGroup(name, description, null, subnetIds);
            String xml = new XmlBuilder()
                    .start("CreateClusterSubnetGroupResponse")
                      .start("CreateClusterSubnetGroupResult")
                        .raw(buildClusterSubnetGroupXml(group))
                      .end("CreateClusterSubnetGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterSubnetGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DescribeClusterSubnetGroups" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            List<ClusterSubnetGroup> groups = service.describeClusterSubnetGroups(name);
            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterSubnetGroupsResponse")
                      .start("DescribeClusterSubnetGroupsResult")
                        .start("ClusterSubnetGroups");
            for (ClusterSubnetGroup group : groups) {
                xmlBuilder.raw(buildClusterSubnetGroupXml(group));
            }
            String xml = xmlBuilder
                        .end("ClusterSubnetGroups")
                      .end("DescribeClusterSubnetGroupsResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DescribeClusterSubnetGroupsResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyClusterSubnetGroup" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            String description = params.getFirst("Description");
            List<String> subnetIds = memberList(params, "SubnetIds");
            ClusterSubnetGroup group = service.modifyClusterSubnetGroup(name, description, subnetIds);
            String xml = new XmlBuilder()
                    .start("ModifyClusterSubnetGroupResponse")
                      .start("ModifyClusterSubnetGroupResult")
                        .raw(buildClusterSubnetGroupXml(group))
                      .end("ModifyClusterSubnetGroupResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterSubnetGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "DeleteClusterSubnetGroup" -> {
            String name = params.getFirst("ClusterSubnetGroupName");
            service.deleteClusterSubnetGroup(name);
            String xml = new XmlBuilder()
                    .start("DeleteClusterSubnetGroupResponse")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("DeleteClusterSubnetGroupResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "ModifyCluster" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required", 400);
            }
            String nodeType = params.getFirst("NodeType");
            Integer numberOfNodes = parseOptionalInteger(params, "NumberOfNodes");
            String masterUserPassword = params.getFirst("MasterUserPassword");
            String clusterParameterGroupName = params.getFirst("ClusterParameterGroupName");
            List<String> vpcSecurityGroupIds = memberList(params, "VpcSecurityGroupIds");
            Cluster cluster = service.modifyCluster(clusterIdentifier, nodeType, numberOfNodes,
                    masterUserPassword, clusterParameterGroupName, vpcSecurityGroupIds);
            String xml = new XmlBuilder()
                    .start("ModifyClusterResponse")
                      .start("ModifyClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("ModifyClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("ModifyClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        case "RebootCluster" -> {
            String clusterIdentifier = params.getFirst("ClusterIdentifier");
            if (clusterIdentifier == null || clusterIdentifier.isBlank()) {
                throw new AwsException("InvalidParameterValue", "ClusterIdentifier is required", 400);
            }
            Cluster cluster = service.rebootCluster(clusterIdentifier);
            String xml = new XmlBuilder()
                    .start("RebootClusterResponse")
                      .start("RebootClusterResult")
                        .raw(buildClusterXml(cluster))
                      .end("RebootClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("RebootClusterResponse")
                    .build();
            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        }
    }

    private String buildClusterXml(Cluster cluster) {
        XmlBuilder builder = new XmlBuilder()
            .start("Cluster")
            .elem("ClusterIdentifier", cluster.getClusterIdentifier())
            .elem("NodeType", cluster.getNodeType())
            .elem("MasterUsername", cluster.getMasterUsername())
            .elem("ClusterStatus", cluster.getClusterStatus())
            .elem("ClusterSubnetGroupName", cluster.getClusterSubnetGroupName());

        if (cluster.getVpcSecurityGroupIds() != null && !cluster.getVpcSecurityGroupIds().isEmpty()) {
            builder.start("VpcSecurityGroups");
            for (String sgId : cluster.getVpcSecurityGroupIds()) {
                builder.start("VpcSecurityGroup").elem("VpcSecurityGroupId", sgId).end("VpcSecurityGroup");
            }
            builder.end("VpcSecurityGroups");
        }

        if (cluster.getClusterParameterGroupName() != null) {
            builder.start("ClusterParameterGroups")
                .start("ClusterParameterGroup")
                  .elem("ParameterGroupName", cluster.getClusterParameterGroupName())
                  .elem("ParameterApplyStatus", "in-sync")
                .end("ClusterParameterGroup")
              .end("ClusterParameterGroups");
        }

        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            builder.start("Tags");
            for (Map.Entry<String, String> tag : cluster.getTags().entrySet()) {
                builder.start("Tag")
                    .elem("Key", tag.getKey())
                    .elem("Value", tag.getValue())
                  .end("Tag");
            }
            builder.end("Tags");
        }

        if (cluster.getEndpoint() != null) {
            builder.start("Endpoint")
                .elem("Address", cluster.getEndpoint().getAddress())
                .elem("Port", String.valueOf(cluster.getEndpoint().getPort()))
                .end("Endpoint");
        }

        return builder.end("Cluster").build();
    }

    private String buildSnapshotXml(Snapshot snapshot) {
        XmlBuilder builder = new XmlBuilder()
            .start("Snapshot")
            .elem("SnapshotIdentifier", snapshot.getSnapshotIdentifier())
            .elem("ClusterIdentifier", snapshot.getClusterIdentifier())
            .elem("Status", snapshot.getStatus())
            .elem("Port", String.valueOf(snapshot.getPort()))
            .elem("MasterUsername", snapshot.getMasterUsername());
        
        return builder.end("Snapshot").build();
    }

    private String buildClusterParameterGroupXml(ClusterParameterGroup group) {
        XmlBuilder builder = new XmlBuilder()
            .start("ClusterParameterGroup")
            .elem("ParameterGroupName", group.getParameterGroupName())
            .elem("ParameterGroupFamily", group.getParameterGroupFamily())
            .elem("Description", group.getDescription());
        
        return builder.end("ClusterParameterGroup").build();
    }

    private String buildClusterSubnetGroupXml(ClusterSubnetGroup group) {
        XmlBuilder builder = new XmlBuilder()
            .start("ClusterSubnetGroup")
            .elem("ClusterSubnetGroupName", group.getClusterSubnetGroupName())
            .elem("Description", group.getDescription())
            .elem("VpcId", group.getVpcId())
            .start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            builder.start("Subnet").elem("SubnetIdentifier", subnetId).end("Subnet");
        }
        return builder.end("Subnets").end("ClusterSubnetGroup").build();
    }

    private String buildParameterXml(Parameter param) {
        XmlBuilder builder = new XmlBuilder()
            .start("Parameter")
            .elem("ParameterName", param.getParameterName())
            .elem("ParameterValue", param.getParameterValue());

        if (param.getDescription() != null) {
            builder.elem("Description", param.getDescription());
        }
        if (param.getDataType() != null) {
            builder.elem("DataType", param.getDataType());
        }
        return builder.end("Parameter").build();
    }

    private static List<String> memberList(MultivaluedMap<String, String> params, String baseName) {
        return params.keySet().stream()
                .filter(key -> key.matches(memberKeyRegex(baseName)))
                .sorted(Comparator.comparingInt(RedshiftQueryHandler::numericSuffix))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    // The AWS Query protocol accepts both the generic ".member.N" form and each shape's own
    // locationName (e.g. the real Redshift SDK sends "SubnetIds.SubnetIdentifier.N", not "SubnetIds.member.N").
    private static String memberKeyRegex(String baseName) {
        String quoted = Pattern.quote(baseName);
        return switch (baseName) {
            case "SubnetIds" -> quoted + "(\\.member|\\.SubnetIdentifier)?\\.\\d+";
            case "VpcSecurityGroupIds" -> quoted + "(\\.member|\\.VpcSecurityGroupId)?\\.\\d+";
            case "TagKeys" -> quoted + "(\\.member|\\.TagKey)?\\.\\d+";
            default -> quoted + "(\\.member)?\\.\\d+";
        };
    }

    private static int numericSuffix(String key) {
        int lastDot = key.lastIndexOf('.');
        if (lastDot < 0 || lastDot == key.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(lastDot + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<Parameter> parseParameters(MultivaluedMap<String, String> params) {
        List<Parameter> parsed = new ArrayList<>();
        readParameters(params, "Parameters.member", parsed);
        readParameters(params, "Parameters.Parameter", parsed);
        return parsed;
    }

    private static void readParameters(MultivaluedMap<String, String> params, String prefix, List<Parameter> parsed) {
        for (int i = 1; ; i++) {
            String name = params.getFirst(prefix + "." + i + ".ParameterName");
            if (name == null) {
                break;
            }
            String value = params.getFirst(prefix + "." + i + ".ParameterValue");
            parsed.add(new Parameter(name, value));
        }
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        readTags(params, "Tags.member", tags);
        readTags(params, "Tags.Tag", tags);
        return tags;
    }

    private static void readTags(MultivaluedMap<String, String> params, String prefix, Map<String, String> tags) {
        for (int i = 1; ; i++) {
            String key = params.getFirst(prefix + "." + i + ".Key");
            if (key == null) {
                break;
            }
            String value = params.getFirst(prefix + "." + i + ".Value");
            tags.put(key, value == null ? "" : value);
        }
    }

    private static Integer parseOptionalInteger(MultivaluedMap<String, String> params, String parameterName) {
        String value = params.getFirst(parameterName);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", parameterName + " must be an integer.", 400);
        }
    }
}
