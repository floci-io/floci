package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.efs.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/2015-02-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EfsController {

    private final EfsService efsService;
    private final RegionResolver regionResolver;

    @Inject
    public EfsController(EfsService efsService, RegionResolver regionResolver) {
        this.efsService = efsService;
        this.regionResolver = regionResolver;
    }

    // --- File Systems ---

    @POST
    @Path("/file-systems")
    public Response createFileSystem(@Context HttpHeaders headers, CreateFileSystemRequest request) {
        String region = regionResolver.resolveRegion(headers);
        FileSystem fs = efsService.createFileSystem(request, region);
        return Response.status(201).entity(fs).build();
    }

    @GET
    @Path("/file-systems")
    public Response describeFileSystems(@Context HttpHeaders headers, @jakarta.ws.rs.BeanParam DescribeFileSystemsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        DescribeFileSystemsResponse response = efsService.describeFileSystems(region, request);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/file-systems/{FileSystemId}")
    public Response updateFileSystem(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, UpdateFileSystemRequest request) {
        String region = regionResolver.resolveRegion(headers);
        FileSystem fs = efsService.updateFileSystem(region, fileSystemId, request);
        return Response.status(202).entity(fs).build();
    }

    @DELETE
    @Path("/file-systems/{FileSystemId}")
    public Response deleteFileSystem(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        efsService.deleteFileSystem(region, fileSystemId);
        return Response.noContent().build();
    }

    // --- Tags ---

    @POST
    @Path("/create-tags/{FileSystemId}")
    public Response createTags(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, CreateTagsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.createTags(region, fileSystemId, request);
        return Response.noContent().build();
    }

    @GET
    @Path("/tags/{FileSystemId}")
    public Response describeTags(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, @jakarta.ws.rs.BeanParam DescribeTagsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        FileSystem fs = efsService.getFileSystem(region, fileSystemId);
        
        java.util.List<Tag> tags = fs.getTags() != null ? fs.getTags() : new java.util.ArrayList<>();
        int maxItems = request.getMaxItems() != null ? request.getMaxItems() : 100;
        int startIndex = 0;
        if (request.getMarker() != null && !request.getMarker().isEmpty()) {
            for (int i = 0; i < tags.size(); i++) {
                if (tags.get(i).getKey().equals(request.getMarker())) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        
        java.util.List<Tag> paginated = new java.util.ArrayList<>();
        String nextMarker = null;
        for (int i = startIndex; i < tags.size(); i++) {
            if (paginated.size() >= maxItems) {
                nextMarker = tags.get(i - 1).getKey();
                break;
            }
            paginated.add(tags.get(i));
        }
        
        DescribeTagsResponse res = new DescribeTagsResponse();
        res.setTags(paginated);
        res.setMarker(request.getMarker());
        res.setNextMarker(nextMarker);
        return Response.ok(res).build();
    }

    @POST
    @Path("/delete-tags/{FileSystemId}")
    public Response deleteTags(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, DeleteTagsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.deleteTags(region, fileSystemId, request);
        return Response.noContent().build();
    }

    @POST
    @Path("/resource-tags/{ResourceId}")
    public Response tagResource(@Context HttpHeaders headers, @PathParam("ResourceId") String resourceId, TagResourceRequest request) {
        String region = regionResolver.resolveRegion(headers);
        request.setResourceId(resourceId);
        efsService.tagResource(region, request);
        return Response.ok().build();
    }

    @DELETE
    @Path("/resource-tags/{ResourceId}")
    public Response untagResource(@Context HttpHeaders headers, @PathParam("ResourceId") String resourceId, @jakarta.ws.rs.BeanParam UntagResourceRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.untagResource(region, resourceId, request.getTagKeys());
        return Response.ok().build();
    }

    @GET
    @Path("/resource-tags/{ResourceId}")
    public Response listTagsForResource(@Context HttpHeaders headers, @PathParam("ResourceId") String resourceId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(efsService.listTagsForResource(region, resourceId)).build();
    }

    // --- Mount Targets ---

    @POST
    @Path("/mount-targets")
    public Response createMountTarget(@Context HttpHeaders headers, CreateMountTargetRequest request) {
        String region = regionResolver.resolveRegion(headers);
        MountTarget mt = efsService.createMountTarget(request, region);
        return Response.status(200).entity(mt).build();
    }

    @GET
    @Path("/mount-targets")
    public Response describeMountTargets(@Context HttpHeaders headers, @jakarta.ws.rs.BeanParam DescribeMountTargetsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        DescribeMountTargetsResponse response = efsService.describeMountTargets(region, request);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/mount-targets/{MountTargetId}")
    public Response deleteMountTarget(@Context HttpHeaders headers, @PathParam("MountTargetId") String mountTargetId) {
        String region = regionResolver.resolveRegion(headers);
        efsService.deleteMountTarget(region, mountTargetId);
        return Response.noContent().build();
    }

    @GET
    @Path("/mount-targets/{MountTargetId}/security-groups")
    public Response describeMountTargetSecurityGroups(@Context HttpHeaders headers, @PathParam("MountTargetId") String mountTargetId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(efsService.describeMountTargetSecurityGroups(region, mountTargetId)).build();
    }

    @PUT
    @Path("/mount-targets/{MountTargetId}/security-groups")
    public Response modifyMountTargetSecurityGroups(@Context HttpHeaders headers, @PathParam("MountTargetId") String mountTargetId, ModifyMountTargetSecurityGroupsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.modifyMountTargetSecurityGroups(region, mountTargetId, request);
        return Response.noContent().build();
    }

    // --- Access Points ---

    @POST
    @Path("/access-points")
    public Response createAccessPoint(@Context HttpHeaders headers, CreateAccessPointRequest request) {
        String region = regionResolver.resolveRegion(headers);
        AccessPointDescription ap = efsService.createAccessPoint(region, request);
        return Response.status(200).entity(ap).build();
    }

    @GET
    @Path("/access-points")
    public Response describeAccessPoints(@Context HttpHeaders headers, @jakarta.ws.rs.QueryParam("FileSystemId") String fileSystemId, @jakarta.ws.rs.QueryParam("AccessPointId") String accessPointId) {
        String region = regionResolver.resolveRegion(headers);
        DescribeAccessPointsResponse res = new DescribeAccessPointsResponse();
        res.setAccessPoints(efsService.describeAccessPoints(region, fileSystemId, accessPointId));
        return Response.ok(res).build();
    }

    @DELETE
    @Path("/access-points/{AccessPointId}")
    public Response deleteAccessPoint(@Context HttpHeaders headers, @PathParam("AccessPointId") String accessPointId) {
        String region = regionResolver.resolveRegion(headers);
        efsService.deleteAccessPoint(region, accessPointId);
        return Response.noContent().build();
    }

    // --- Policies ---

    @PUT
    @Path("/file-systems/{FileSystemId}/policy")
    public Response putFileSystemPolicy(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, PutFileSystemPolicyRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.putFileSystemPolicy(region, fileSystemId, request.getPolicy());
        PutFileSystemPolicyResponse res = new PutFileSystemPolicyResponse();
        res.setFileSystemId(fileSystemId);
        res.setPolicy(request.getPolicy());
        return Response.ok(res).build();
    }

    @GET
    @Path("/file-systems/{FileSystemId}/policy")
    public Response describeFileSystemPolicy(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        String policy = efsService.getFileSystemPolicy(region, fileSystemId);
        DescribeFileSystemPolicyResponse res = new DescribeFileSystemPolicyResponse();
        res.setFileSystemId(fileSystemId);
        res.setPolicy(policy);
        return Response.ok(res).build();
    }

    @DELETE
    @Path("/file-systems/{FileSystemId}/policy")
    public Response deleteFileSystemPolicy(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        efsService.deleteFileSystemPolicy(region, fileSystemId);
        return Response.ok().build();
    }

    @PUT
    @Path("/file-systems/{FileSystemId}/backup-policy")
    public Response putBackupPolicy(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, PutBackupPolicyRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.putBackupPolicy(region, fileSystemId, request.getBackupPolicy());
        PutBackupPolicyResponse res = new PutBackupPolicyResponse();
        res.setBackupPolicy(request.getBackupPolicy());
        return Response.ok(res).build();
    }

    @GET
    @Path("/file-systems/{FileSystemId}/backup-policy")
    public Response describeBackupPolicy(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        BackupPolicy policy = efsService.getBackupPolicy(region, fileSystemId);
        PutBackupPolicyResponse res = new PutBackupPolicyResponse();
        res.setBackupPolicy(policy);
        return Response.ok(res).build();
    }

    @PUT
    @Path("/file-systems/{FileSystemId}/lifecycle-configuration")
    public Response putLifecycleConfiguration(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId, PutLifecycleConfigurationRequest request) {
        String region = regionResolver.resolveRegion(headers);
        efsService.putLifecycleConfiguration(region, fileSystemId, request.getLifecyclePolicies());
        PutLifecycleConfigurationResponse res = new PutLifecycleConfigurationResponse();
        res.setLifecyclePolicies(request.getLifecyclePolicies());
        return Response.ok(res).build();
    }

    @GET
    @Path("/file-systems/{FileSystemId}/lifecycle-configuration")
    public Response describeLifecycleConfiguration(@Context HttpHeaders headers, @PathParam("FileSystemId") String fileSystemId) {
        String region = regionResolver.resolveRegion(headers);
        PutLifecycleConfigurationResponse res = new PutLifecycleConfigurationResponse();
        res.setLifecyclePolicies(efsService.getLifecycleConfiguration(region, fileSystemId));
        return Response.ok(res).build();
    }
}
