package io.floci.aws.services.eks;

import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.eks.model.Addon;
import io.floci.aws.services.eks.model.AssociateAccessPolicyRequest;
import io.floci.aws.services.eks.model.AssociatedAccessPolicy;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.CreateAccessEntryRequest;
import io.floci.aws.services.eks.model.CreateAddonRequest;
import io.floci.aws.services.eks.model.CreateClusterRequest;
import io.floci.aws.services.eks.model.CreateFargateProfileRequest;
import io.floci.aws.services.eks.model.CreateNodeGroupRequest;
import io.floci.aws.services.eks.model.CreatePodIdentityAssociationRequest;
import io.floci.aws.services.eks.model.FargateProfile;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.PodIdentityAssociation;
import io.floci.aws.services.eks.model.Update;
import io.floci.aws.services.eks.model.UpdateAddonRequest;
import io.floci.aws.services.eks.model.UpdatePodIdentityAssociationRequest;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EKS REST-JSON controller.
 *
 * <p>
 * EKS uses standard HTTP verbs with JSON bodies - not JSON 1.1 (X-Amz-Target)
 * or Query protocol.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksController {

    /**
     * A cluster access policy ARN as a path carries it, its {@code :} and {@code /} encoded or not. Only such an
     * ARN after {@code access-policies} makes a DELETE a disassociate, so an entry whose IAM path holds an
     * {@code access-policies} segment can still be deleted.
     */
    public static final String ACCESS_POLICY_ARN = "arn(?::|%3[aA])[a-z-]+(?::|%3[aA])eks(?::|%3[aA])(?::|%3[aA])"
            + "aws(?::|%3[aA])cluster-access-policy(?:/|%2[fF])[^/]+";


    private final EksService eksService;
    private final EksAccessEntryService accessEntries;
    private final EksPodIdentityAssociationService podIdentityAssociations;
    private final EksAddonService addons;

    @Inject
    public EksController(EksService eksService, EksAccessEntryService accessEntries,
                         EksPodIdentityAssociationService podIdentityAssociations,
                         EksAddonService addons) {
        this.eksService = eksService;
        this.accessEntries = accessEntries;
        this.podIdentityAssociations = podIdentityAssociations;
        this.addons = addons;
    }

    @POST
    @Path("/clusters")
    public Response createCluster(CreateClusterRequest request) {
        Cluster cluster = eksService.createCluster(request);
        return Response.ok(Map.of("cluster", toClusterResponse(cluster))).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(@QueryParam("maxResults") String maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        PaginatedResult<String> page = eksService.listClusters(
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null ? Map.of("clusters", page.items())
                : Map.of("clusters", page.items(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}")
    public Response describeCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        return Response.ok(Map.of("cluster", toClusterResponse(cluster))).build();
    }

    @DELETE
    @Path("/clusters/{name}")
    public Response deleteCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.deleteClusterInRequestRegion(name);
        return Response.ok(Map.of("cluster", toClusterResponse(cluster))).build();
    }

    /**
     * Sanitizes the cluster model before emitting it as an AWS API response.
     * explicitVersion is internal Floci metadata used for container image resolution
     * across restarts and must not be included on the wire. Clearing it on a copy
     * causes Jackson to omit the property due to @JsonInclude(NON_DEFAULT).
     */
    private Cluster toClusterResponse(Cluster cluster) {
        if (cluster == null || (!cluster.isExplicitVersion() && cluster.getNodeInstanceType() == null
                && cluster.getNodePrivateIp() == null
                && cluster.getClusterArgs() == null && cluster.getDefaultStorageClass() == null
                && cluster.getJoinedInstanceIds() == null && cluster.getDockerNameRegion() == null)) {
            return cluster;
        }
        Cluster response = cluster.copy();
        response.setDockerNameRegion(null);
        response.setExplicitVersion(false);
        response.setNodeInstanceType(null);
        response.setNodePrivateIp(null);
        response.setClusterArgs(null);
        response.setDefaultStorageClass(null);
        response.setJoinedInstanceIds(null);
        return response;
    }

    // Keep these concrete EKS resource paths declared explicitly so they outrank
    // the S3 catch-all route; see issue #1137.
    @POST
    @Path("/clusters/{name}/node-groups")
    public Response createNodeGroup(@PathParam("name") String name, CreateNodeGroupRequest request) {
        eksService.requireClusterInRequestRegion(name);
        Nodegroup nodeGroup = eksService.createNodeGroup(name, request);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups")
    public Response listNodeGroups(@PathParam("name") String name) {
        eksService.requireClusterInRequestRegion(name);
        List<String> nodeGroupNames = eksService.listNodeGroups(name);
        return Response.ok(Map.of("nodegroups", nodeGroupNames)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response describeNodeGroup(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName) {
        eksService.requireClusterInRequestRegion(name);
        Nodegroup nodeGroup = eksService.describeNodeGroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @DELETE
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response deleteNodeGroup(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName) {
        eksService.requireClusterInRequestRegion(name);
        Nodegroup nodeGroup = eksService.deleteNodeGroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @POST
    @Path("/clusters/{name}/fargate-profiles")
    public Response createFargateProfile(@PathParam("name") String name, CreateFargateProfileRequest request) {
        eksService.requireClusterInRequestRegion(name);
        FargateProfile profile = eksService.createFargateProfile(name, request);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @GET
    @Path("/clusters/{name}/fargate-profiles")
    public Response listFargateProfiles(@PathParam("name") String name) {
        eksService.requireClusterInRequestRegion(name);
        List<String> profileNames = eksService.listFargateProfiles(name);
        return Response.ok(Map.of("fargateProfileNames", profileNames)).build();
    }

    @GET
    @Path("/clusters/{name}/fargate-profiles/{fargateProfileName}")
    public Response describeFargateProfile(@PathParam("name") String name,
            @PathParam("fargateProfileName") String fargateProfileName) {
        eksService.requireClusterInRequestRegion(name);
        FargateProfile profile = eksService.describeFargateProfile(name, fargateProfileName);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @DELETE
    @Path("/clusters/{name}/fargate-profiles/{fargateProfileName}")
    public Response deleteFargateProfile(@PathParam("name") String name,
            @PathParam("fargateProfileName") String fargateProfileName) {
        eksService.requireClusterInRequestRegion(name);
        FargateProfile profile = eksService.deleteFargateProfile(name, fargateProfileName);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    // Read-only sub-resource lists for resources the emulator does not model. Explicit routes
    // so S3's path-style catch-all (@Path("/{bucket}/{key: .+}")) cannot swallow them
    // (issue #1754, same family as #1137): validate the cluster, then return the documented
    // empty list under each operation's model-exact result key.

    @POST
    @Path("/clusters/{name}/access-entries")
    public Response createAccessEntry(@PathParam("name") String name, CreateAccessEntryRequest request) {
        return Response.ok(Map.of("accessEntry", accessEntries.create(eksService.requireClusterInRequestRegion(name), request))).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries")
    public Response listAccessEntries(@PathParam("name") String name,
                                     @QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken,
                                     @QueryParam("associatedPolicyArn") String associatedPolicyArn) {
        EksAccessEntryService.Page page = accessEntries.list(eksService.requireClusterInRequestRegion(name),
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken, associatedPolicyArn);
        return Response.ok(page.nextToken() == null ? Map.of("accessEntries", page.accessEntries())
                : Map.of("accessEntries", page.accessEntries(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries/{principalArn: .+}")
    public Response describeAccessEntry(@PathParam("name") String name, @PathParam("principalArn") String principalArn) {
        return Response.ok(Map.of("accessEntry", accessEntries.describe(eksService.requireClusterInRequestRegion(name), principalArn))).build();
    }

    @DELETE
    @Path("/clusters/{name}/access-entries/{principalArn: .+}")
    public Response deleteAccessEntry(@PathParam("name") String name, @PathParam("principalArn") String principalArn) {
        accessEntries.delete(eksService.requireClusterInRequestRegion(name), principalArn);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/clusters/{name}/access-entries/{principalArn: .+}/access-policies")
    public Response associateAccessPolicy(@PathParam("name") String name, @PathParam("principalArn") String principalArn,
                                          AssociateAccessPolicyRequest request) {
        AssociatedAccessPolicy policy = accessEntries.associate(eksService.requireClusterInRequestRegion(name), principalArn, request);
        return Response.ok(Map.of("clusterName", name, "principalArn", principalArn,
                "associatedAccessPolicy", policy)).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries/{principalArn: .+}/access-policies")
    public Response listAssociatedAccessPolicies(@PathParam("name") String name,
                                                 @PathParam("principalArn") String principalArn,
                                                 @QueryParam("maxResults") String maxResults,
                                                 @QueryParam("nextToken") String nextToken) {
        EksAccessEntryService.PolicyPage page = accessEntries.listAccessPolicies(eksService.requireClusterInRequestRegion(name),
                principalArn, Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterName", name);
        body.put("principalArn", principalArn);
        body.put("associatedAccessPolicies", page.associatedAccessPolicies());
        if (page.nextToken() != null) {
            body.put("nextToken", page.nextToken());
        }
        return Response.ok(body).build();
    }

    @DELETE
    @Path("/clusters/{name}/access-entries/{principalArn: .+}/access-policies/{policyArn: " + ACCESS_POLICY_ARN + "}")
    public Response disassociateAccessPolicy(@PathParam("name") String name,
                                             @PathParam("principalArn") String principalArn,
                                             @PathParam("policyArn") String policyArn) {
        accessEntries.disassociate(eksService.requireClusterInRequestRegion(name), principalArn, policyArn);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/clusters/{name}/addons")
    public Response createAddon(@PathParam("name") String name, CreateAddonRequest request) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        Addon addon = addons.create(cluster, request);
        return Response.ok(Map.of("addon", addon)).build();
    }

    @GET
    @Path("/clusters/{name}/addons")
    public Response listAddons(@PathParam("name") String name,
                               @QueryParam("maxResults") String maxResults,
                               @QueryParam("nextToken") String nextToken) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        EksAddonService.AddonNamesPage page = addons.list(cluster,
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null
                ? Map.of("addons", page.addons())
                : Map.of("addons", page.addons(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/addons/{addonName}")
    public Response describeAddon(@PathParam("name") String name,
                                  @PathParam("addonName") String addonName) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        Addon addon = addons.describe(cluster, addonName);
        return Response.ok(Map.of("addon", addon)).build();
    }

    @POST
    @Path("/clusters/{name}/addons/{addonName}/update")
    public Response updateAddon(@PathParam("name") String name,
                                @PathParam("addonName") String addonName,
                                UpdateAddonRequest request) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        Update update = addons.update(cluster, addonName, request);
        return Response.ok(Map.of("update", update)).build();
    }

    @GET
    @Path("/clusters/{name}/updates/{updateId}")
    public Response describeUpdate(@PathParam("name") String name,
                                   @PathParam("updateId") String updateId,
                                   @QueryParam("addonName") String addonName,
                                   @QueryParam("nodegroupName") String nodegroupName) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        Update update = addons.describeUpdate(cluster, updateId, addonName);
        return Response.ok(Map.of("update", update)).build();
    }

    @DELETE
    @Path("/clusters/{name}/addons/{addonName}")
    public Response deleteAddon(@PathParam("name") String name,
                                @PathParam("addonName") String addonName,
                                @QueryParam("preserve") Boolean preserve) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        Addon addon = addons.delete(cluster, addonName, Boolean.TRUE.equals(preserve));
        return Response.ok(Map.of("addon", addon)).build();
    }

    @GET
    @Path("/addons/supported-versions")
    public Response describeAddonVersions(@QueryParam("addonName") String addonName,
                                          @QueryParam("kubernetesVersion") String kubernetesVersion,
                                          @QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken,
                                          @QueryParam("publishers") List<String> publishers,
                                          @QueryParam("types") List<String> types,
                                          @QueryParam("owners") List<String> owners) {
        EksAddonService.AddonVersionsPage page = addons.describeAddonVersions(
                addonName,
                kubernetesVersion,
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"),
                nextToken,
                publishers,
                types,
                owners
        );
        return Response.ok(page.nextToken() == null
                ? Map.of("addons", page.addons())
                : Map.of("addons", page.addons(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/identity-provider-configs")
    public Response listIdentityProviderConfigs(@PathParam("name") String name) {
        eksService.requireClusterInRequestRegion(name);
        return Response.ok(Map.of("identityProviderConfigs", List.of())).build();
    }

    @POST
    @Path("/clusters/{name}/pod-identity-associations")
    public Response createPodIdentityAssociation(@PathParam("name") String name,
                                                 CreatePodIdentityAssociationRequest request) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        PodIdentityAssociation association = podIdentityAssociations.create(cluster, request);
        return Response.ok(Map.of("association", association)).build();
    }

    @GET
    @Path("/clusters/{name}/pod-identity-associations")
    public Response listPodIdentityAssociations(@PathParam("name") String name,
                                                @QueryParam("namespace") String namespace,
                                                @QueryParam("serviceAccount") String serviceAccount,
                                                @QueryParam("maxResults") String maxResults,
                                                @QueryParam("nextToken") String nextToken) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        EksPodIdentityAssociationService.Page page = podIdentityAssociations.list(cluster, namespace, serviceAccount,
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null
                ? Map.of("associations", page.associations())
                : Map.of("associations", page.associations(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response describePodIdentityAssociation(@PathParam("name") String name,
                                                   @PathParam("associationId") String associationId) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        PodIdentityAssociation association = podIdentityAssociations.describe(cluster, associationId);
        return Response.ok(Map.of("association", association)).build();
    }

    @POST
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response updatePodIdentityAssociation(@PathParam("name") String name,
                                                 @PathParam("associationId") String associationId,
                                                 UpdatePodIdentityAssociationRequest request) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        PodIdentityAssociation association = podIdentityAssociations.update(cluster, associationId, request);
        return Response.ok(Map.of("association", association)).build();
    }

    @DELETE
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response deletePodIdentityAssociation(@PathParam("name") String name,
                                                 @PathParam("associationId") String associationId) {
        Cluster cluster = eksService.requireClusterInRequestRegion(name);
        PodIdentityAssociation association = podIdentityAssociations.delete(cluster, associationId);
        return Response.ok(Map.of("association", association)).build();
    }
}
