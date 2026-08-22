package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
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
}
