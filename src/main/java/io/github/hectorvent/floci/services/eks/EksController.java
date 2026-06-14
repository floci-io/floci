package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * EKS REST-JSON controller.
 *
 * <p>EKS uses standard HTTP verbs with JSON bodies — not JSON 1.1 (X-Amz-Target) or Query protocol.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksController {

    private final EksService eksService;

    @Inject
    public EksController(EksService eksService) {
        this.eksService = eksService;
    }

    @POST
    @Path("/clusters")
    public Response createCluster(CreateClusterRequest request) {
        Cluster cluster = eksService.createCluster(request);
        return Response.ok(Map.of("cluster", cluster)).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(@QueryParam("nextToken") String nextToken,
                                 @QueryParam("maxResults") Integer maxResults) {
        List<String> clusterNames = eksService.listClusters();
        return Response.ok(Map.of("clusters", clusterNames)).build();
    }

    @GET
    @Path("/clusters/{name}")
    public Response describeCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.describeCluster(name);
        return Response.ok(Map.of("cluster", cluster)).build();
    }

    @DELETE
    @Path("/clusters/{name}")
    public Response deleteCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.deleteCluster(name);
        return Response.ok(Map.of("cluster", cluster)).build();
    }

    // Managed node groups. These explicit routes outrank S3's path-style catch-all
    // (@Path("/{bucket}/{key: .+}")), which previously swallowed these paths (issue #1137).

    @POST
    @Path("/clusters/{name}/node-groups")
    public Response createNodegroup(@PathParam("name") String name, Nodegroup request) {
        Nodegroup nodegroup = eksService.createNodegroup(name, request);
        return Response.ok(Map.of("nodegroup", nodegroup)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups")
    public Response listNodegroups(@PathParam("name") String name) {
        List<String> nodegroups = eksService.listNodegroups(name);
        return Response.ok(Map.of("nodegroups", nodegroups)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response describeNodegroup(@PathParam("name") String name,
                                      @PathParam("nodegroupName") String nodegroupName) {
        Nodegroup nodegroup = eksService.describeNodegroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodegroup)).build();
    }

    @DELETE
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response deleteNodegroup(@PathParam("name") String name,
                                    @PathParam("nodegroupName") String nodegroupName) {
        Nodegroup nodegroup = eksService.deleteNodegroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodegroup)).build();
    }

}
