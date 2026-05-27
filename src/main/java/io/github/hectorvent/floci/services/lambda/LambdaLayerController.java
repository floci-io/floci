package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                                        Map<String, Object> request) {
        String region = regionResolver.resolveRegion(headers);
        LambdaLayerVersion lv = layerService.publishLayerVersion(region, layerName, request);
        return Response.status(201).entity(buildLayerVersionResponse(lv)).build();
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
                                    @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        LambdaLayerVersion lv = layerService.getLayerVersion(region, layerName, versionNumber);
        return Response.ok(buildLayerVersionResponse(lv)).build();
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

    private ObjectNode buildLayerVersionResponse(LambdaLayerVersion lv) {
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
        // Location is a presigned URL in real AWS; we provide a placeholder
        contentNode.put("Location", "");

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
