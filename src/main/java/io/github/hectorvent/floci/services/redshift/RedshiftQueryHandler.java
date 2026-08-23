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

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class RedshiftQueryHandler {
    private final RedshiftService service;

    @Inject
    public RedshiftQueryHandler(RedshiftService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        if ("CreateCluster".equals(action)) {
            String identifier = params.getFirst("ClusterIdentifier");
            String nodeType = params.getFirst("NodeType");
            String masterUsername = params.getFirst("MasterUsername");
            String masterUserPassword = params.getFirst("MasterUserPassword");

            Cluster cluster = service.createCluster(identifier, nodeType, masterUsername, masterUserPassword);
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
        } else if ("DescribeClusters".equals(action)) {
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
        } else if ("DeleteCluster".equals(action)) {
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
        } else if ("CreateClusterSnapshot".equals(action)) {
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
        } else if ("DescribeClusterSnapshots".equals(action)) {
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
        } else if ("DeleteClusterSnapshot".equals(action)) {
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
        } else if ("RestoreFromClusterSnapshot".equals(action)) {
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
        } else if ("CreateClusterParameterGroup".equals(action)) {
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
        } else if ("DescribeClusterParameterGroups".equals(action)) {
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
        } else if ("DescribeClusterParameters".equals(action)) {
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
        } else if ("ModifyClusterParameterGroup".equals(action)) {
            String parameterGroupName = params.getFirst("ParameterGroupName");
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
        } else if ("DeleteClusterParameterGroup".equals(action)) {
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
        } else if ("CreateTags".equals(action)) {
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
        } else if ("DeleteTags".equals(action)) {
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
        } else if ("DescribeTags".equals(action)) {
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
        } else if ("CreateClusterSubnetGroup".equals(action)) {
            String name = params.getFirst("ClusterSubnetGroupName");
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
        } else if ("DescribeClusterSubnetGroups".equals(action)) {
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
        } else if ("ModifyClusterSubnetGroup".equals(action)) {
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
        } else if ("DeleteClusterSubnetGroup".equals(action)) {
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

        throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
    }

    private String buildClusterXml(Cluster cluster) {
        XmlBuilder builder = new XmlBuilder()
            .start("Cluster")
            .elem("ClusterIdentifier", cluster.getClusterIdentifier())
            .elem("NodeType", cluster.getNodeType())
            .elem("MasterUsername", cluster.getMasterUsername())
            .elem("ClusterStatus", cluster.getClusterStatus());
        
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
                .filter(key -> key.matches(java.util.regex.Pattern.quote(baseName) + "(\\.member)?\\.\\d+"))
                .sorted(java.util.Comparator.comparingInt(RedshiftQueryHandler::numericSuffix))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static int numericSuffix(String key) {
        int lastDot = key.lastIndexOf('.');
        return Integer.parseInt(key.substring(lastDot + 1));
    }

    private static List<Parameter> parseParameters(MultivaluedMap<String, String> params) {
        List<Parameter> parsed = new java.util.ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("Parameters.member." + i + ".ParameterName");
            if (name == null) {
                break;
            }
            String value = params.getFirst("Parameters.member." + i + ".ParameterValue");
            parsed.add(new Parameter(name, value));
        }
        return parsed;
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            if (key == null) {
                break;
            }
            String value = params.getFirst("Tags.member." + i + ".Value");
            tags.put(key, value == null ? "" : value);
        }
        return tags;
    }
}
