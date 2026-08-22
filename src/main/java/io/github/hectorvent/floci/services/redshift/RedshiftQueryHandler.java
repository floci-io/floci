package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.List;

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
            service.describeClusterParameterGroups(parameterGroupName);
            
            List<io.github.hectorvent.floci.services.redshift.model.Parameter> parameters = List.of(
                new io.github.hectorvent.floci.services.redshift.model.Parameter("max_cursor_result_set_size", "0", "Maximum cursor result set size", "integer"),
                new io.github.hectorvent.floci.services.redshift.model.Parameter("wlm_json_configuration", "{}", "WLM configuration", "string")
            );

            XmlBuilder xmlBuilder = new XmlBuilder()
                    .start("DescribeClusterParametersResponse")
                      .start("DescribeClusterParametersResult")
                        .start("Parameters");
                        
            for (io.github.hectorvent.floci.services.redshift.model.Parameter param : parameters) {
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

    private String buildParameterXml(io.github.hectorvent.floci.services.redshift.model.Parameter param) {
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
}
