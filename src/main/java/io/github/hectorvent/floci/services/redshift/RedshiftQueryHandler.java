package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

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
                        .start("Cluster")
                          .elem("ClusterIdentifier", cluster.getClusterIdentifier())
                          .elem("ClusterStatus", cluster.getClusterStatus())
                        .end("Cluster")
                      .end("CreateClusterResult")
                      .start("ResponseMetadata")
                        .elem("RequestId", "test-req-id")
                      .end("ResponseMetadata")
                    .end("CreateClusterResponse")
                    .build();

            return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
        }
        return Response.status(400).build();
    }
}
