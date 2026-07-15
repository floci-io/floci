package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * EKS REST-JSON controller.
 *
 * <p>
 * EKS uses standard HTTP verbs with JSON bodies — not JSON 1.1 (X-Amz-Target)
 * or Query protocol.
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
    public Response listClusters() {
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

    // Keep these concrete EKS resource paths declared explicitly so they outrank
    // the S3 catch-all route; see issue #1137.
    @POST
    @Path("/clusters/{name}/node-groups")
    public Response createNodeGroup(@PathParam("name") String name, CreateNodeGroupRequest request) {
        Nodegroup nodeGroup = eksService.createNodeGroup(name, request);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups")
    public Response listNodeGroups(@PathParam("name") String name) {
        List<String> nodeGroupNames = eksService.listNodeGroups(name);
        return Response.ok(Map.of("nodegroups", nodeGroupNames)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response describeNodeGroup(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName) {
        Nodegroup nodeGroup = eksService.describeNodeGroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @DELETE
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response deleteNodeGroup(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName) {
        Nodegroup nodeGroup = eksService.deleteNodeGroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @POST
    @Path("/clusters/{name}/fargate-profiles")
    public Response createFargateProfile(@PathParam("name") String name, CreateFargateProfileRequest request) {
        FargateProfile profile = eksService.createFargateProfile(name, request);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @GET
    @Path("/clusters/{name}/fargate-profiles")
    public Response listFargateProfiles(@PathParam("name") String name) {
        List<String> profileNames = eksService.listFargateProfiles(name);
        return Response.ok(Map.of("fargateProfileNames", profileNames)).build();
    }

    @GET
    @Path("/clusters/{name}/fargate-profiles/{fargateProfileName}")
    public Response describeFargateProfile(@PathParam("name") String name,
            @PathParam("fargateProfileName") String fargateProfileName) {
        FargateProfile profile = eksService.describeFargateProfile(name, fargateProfileName);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @DELETE
    @Path("/clusters/{name}/fargate-profiles/{fargateProfileName}")
    public Response deleteFargateProfile(@PathParam("name") String name,
            @PathParam("fargateProfileName") String fargateProfileName) {
        FargateProfile profile = eksService.deleteFargateProfile(name, fargateProfileName);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }
}
