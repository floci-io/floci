package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Map;

/**
 * Lambda layer endpoints — uses the /2018-10-31 API version prefix.
 *
 * PublishLayerVersion:  POST   /2018-10-31/layers/{LayerName}/versions
 * ListLayerVersions:   GET    /2018-10-31/layers/{LayerName}/versions
 * GetLayerVersion:      GET    /2018-10-31/layers/{LayerName}/versions/{VersionNumber}
 * DeleteLayerVersion:   DELETE /2018-10-31/layers/{LayerName}/versions/{VersionNumber}
 * ListLayers:           GET    /2018-10-31/layers
 */
@Path("/2018-10-31")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaLayerController {

    private final ObjectMapper objectMapper;
    private final LambdaLayerService layerService;
    private final RegionResolver regionResolver;

    @Inject
    public LambdaLayerController(ObjectMapper objectMapper,
                                 LambdaLayerService layerService,
                                 RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.layerService = layerService;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/layers/{layerName}/versions")
    public Response publishLayerVersion(@PathParam("layerName") String layerName,
                                        @Context HttpHeaders headers,
                                        @Context UriInfo uriInfo,
                                        Map<String, Object> request) {
        String region = regionResolver.resolveRegion(headers);
        LambdaLayerVersion lv = layerService.publishLayerVersion(region, layerName, request);
        return Response.status(201).entity(buildLayerVersionResponse(lv, region, uriInfo)).build();
    }

    @GET
    @Path("/layers/{layerName}/versions")
    public Response listLayerVersions(@PathParam("layerName") String layerName,
                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaLayerVersion> versions = layerService.listLayerVersions(region, layerName);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("LayerVersions");
        for (LambdaLayerVersion lv : versions) {
            arr.add(buildLayerVersionSummary(lv));
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/layers/{layerName}/versions/{versionNumber}")
    public Response getLayerVersion(@PathParam("layerName") String layerName,
                                    @PathParam("versionNumber") long versionNumber,
                                    @Context HttpHeaders headers,
                                    @Context UriInfo uriInfo) {
        String region = regionResolver.resolveRegion(headers);
        LambdaLayerVersion lv = layerService.getLayerVersion(region, layerName, versionNumber);
        return Response.ok(buildLayerVersionResponse(lv, region, uriInfo)).build();
    }

    @DELETE
    @Path("/layers/{layerName}/versions/{versionNumber}")
    public Response deleteLayerVersion(@PathParam("layerName") String layerName,
                                       @PathParam("versionNumber") long versionNumber,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        layerService.deleteLayerVersion(region, layerName, versionNumber);
        return Response.noContent().build();
    }

    @GET
    @Path("/layers")
    public Response listLayers(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaLayerVersion> layers = layerService.listLayers(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("Layers");
        for (LambdaLayerVersion lv : layers) {
            ObjectNode layerNode = objectMapper.createObjectNode();
            layerNode.put("LayerName", lv.getLayerName());
            layerNode.put("LayerArn", lv.getLayerArn());
            ObjectNode latestVersion = layerNode.putObject("LatestMatchingVersion");
            latestVersion.put("LayerVersionArn", lv.getLayerVersionArn());
            latestVersion.put("Version", lv.getVersion());
            latestVersion.put("Description", lv.getDescription() != null ? lv.getDescription() : "");
            latestVersion.put("CreatedDate", lv.getCreatedDate());
            latestVersion.put("LicenseInfo", lv.getLicenseInfo() != null ? lv.getLicenseInfo() : "");
            if (lv.getCompatibleRuntimes() != null && !lv.getCompatibleRuntimes().isEmpty()) {
                ArrayNode runtimes = latestVersion.putArray("CompatibleRuntimes");
                lv.getCompatibleRuntimes().forEach(runtimes::add);
            }
            if (lv.getCompatibleArchitectures() != null && !lv.getCompatibleArchitectures().isEmpty()) {
                ArrayNode archs = latestVersion.putArray("CompatibleArchitectures");
                lv.getCompatibleArchitectures().forEach(archs::add);
            }
            arr.add(layerNode);
        }
        return Response.ok(root).build();
    }

    private ObjectNode buildLayerVersionResponse(LambdaLayerVersion lv, String region, UriInfo uriInfo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("LayerArn", lv.getLayerArn());
        node.put("LayerVersionArn", lv.getLayerVersionArn());
        node.put("Version", lv.getVersion());
        node.put("Description", lv.getDescription() != null ? lv.getDescription() : "");
        node.put("CreatedDate", lv.getCreatedDate());
        node.put("LicenseInfo", lv.getLicenseInfo() != null ? lv.getLicenseInfo() : "");

        // Content block
        ObjectNode contentNode = node.putObject("Content");
        contentNode.put("CodeSha256", lv.getCodeSha256() != null ? lv.getCodeSha256() : "");
        contentNode.put("CodeSize", lv.getCodeSizeBytes());
        // Only advertise a fetchable Location when the archive was actually stored; AWS
        // clients treat Content.Location as a downloadable URL, so a placeholder empty
        // string is correct for pre-upgrade or failed-store layers.
        contentNode.put("Location", lv.isArchiveStored() ? tasksLocation(lv, region, uriInfo) : "");

        if (lv.getCompatibleRuntimes() != null && !lv.getCompatibleRuntimes().isEmpty()) {
            ArrayNode runtimes = node.putArray("CompatibleRuntimes");
            lv.getCompatibleRuntimes().forEach(runtimes::add);
        }
        if (lv.getCompatibleArchitectures() != null && !lv.getCompatibleArchitectures().isEmpty()) {
            ArrayNode archs = node.putArray("CompatibleArchitectures");
            lv.getCompatibleArchitectures().forEach(archs::add);
        }
        return node;
    }

    /**
     * Path-style URL to the archive in Floci's own S3 (a presigned URL in real AWS),
     * built from the request so it targets the same endpoint the client is talking to.
     * Segments are percent-encoded directly rather than through {@code UriBuilder.path},
     * whose template syntax would throw on a layer name containing braces.
     * Clients fetch this URL unsigned, so an {@code X-Amz-Credential} query steers
     * Floci's account filter to the layer's owning account; without it a layer owned
     * by a non-default account resolves the default-account bucket and 404s.
     */
    private String tasksLocation(LambdaLayerVersion lv, String region, UriInfo uriInfo) {
        var base = uriInfo.getBaseUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        var account = AwsArnUtils.accountOrDefault(lv.getLayerVersionArn(), "000000000000");
        var bucket = LambdaService.tasksBucketName(region);
        var key = LambdaService.layerObjectKey(account, lv.getLayerName(), lv.getVersion());
        return base + "/" + LambdaService.encodeObjectPath(bucket)
                + "/" + LambdaService.encodeObjectPath(key)
                + "?X-Amz-Credential=" + account + "%2F00010101%2F" + region + "%2Fs3%2Faws4_request";
    }

    private ObjectNode buildLayerVersionSummary(LambdaLayerVersion lv) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("LayerVersionArn", lv.getLayerVersionArn());
        node.put("Version", lv.getVersion());
        node.put("Description", lv.getDescription() != null ? lv.getDescription() : "");
        node.put("CreatedDate", lv.getCreatedDate());
        node.put("LicenseInfo", lv.getLicenseInfo() != null ? lv.getLicenseInfo() : "");
        if (lv.getCompatibleRuntimes() != null && !lv.getCompatibleRuntimes().isEmpty()) {
            ArrayNode runtimes = node.putArray("CompatibleRuntimes");
            lv.getCompatibleRuntimes().forEach(runtimes::add);
        }
        if (lv.getCompatibleArchitectures() != null && !lv.getCompatibleArchitectures().isEmpty()) {
            ArrayNode archs = node.putArray("CompatibleArchitectures");
            lv.getCompatibleArchitectures().forEach(archs::add);
        }
        return node;
    }
}
