package io.github.hectorvent.floci.services.lambdamicrovms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.Microvm;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.MicrovmBuild;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.MicrovmImage;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.MicrovmImageVersion;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Lambda MicroVMs control plane ("Lambda Microvms", apiVersion 2025-09-09,
 * rest-json, signs as {@code lambda}). Images with a converging version/build
 * lifecycle, the managed base-image catalog, and basic MicroVM CRUD.
 *
 * <p>Routes live under the {@code /2025-09-09} prefix and collide with nothing
 * the classic Lambda controllers serve. Registered under the lambda service
 * descriptor because requests sign as {@code lambda}.</p>
 */
@Path("/2025-09-09")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaMicrovmsController {

    /**
     * The memory tier an image gets when the request names none. The service
     * reports {@code resources} on the create response even though nothing in
     * the request or in the CloudFormation resource type sets it.
     */
    private static final int DEFAULT_MEMORY_MIB = 2048;

    /**
     * Snapshot sizes as recorded from a real build. An emulator cannot produce
     * true sizes, and a client that reads them wants plausible magnitudes
     * rather than zeros.
     */
    private static final long CODE_INSTALL_SIZE_BYTES = 186400768L;
    private static final long DISK_SNAPSHOT_SIZE_BYTES = 25600000L;
    private static final long MEMORY_SNAPSHOT_SIZE_BYTES = 609087488L;

    /**
     * Every image carries a managed default egress connector, attached by the
     * service rather than by the caller. The account segment is the literal
     * {@code aws} because the connector is AWS-owned, not the caller's.
     */
    private static final String MANAGED_EGRESS_CONNECTOR_FORMAT =
            "arn:aws:lambda:%s:aws:network-connector:aws-network-connector:INTERNET_EGRESS";

    private final LambdaMicrovmsService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaMicrovmsController(LambdaMicrovmsService service,
                                    RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- images

    @POST
    @Path("/microvm-images")
    public Response createMicrovmImage(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        JsonNode codeArtifact = request.path("codeArtifact");
        MicrovmImage image = service.createImage(
                region,
                regionResolver.getAccountId(),
                text(request, "name"),
                text(request, "baseImageArn"),
                text(request, "buildRoleArn"),
                codeArtifact.isMissingNode() ? null : text(codeArtifact, "uri"),
                text(request, "description"));
        return Response.status(201).entity(imageNode(image, ImageShape.CREATE)).build();
    }

    @GET
    @Path("/microvm-images")
    public Response listMicrovmImages(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (MicrovmImage image : service.listImages(region)) {
            items.add(imageNode(image, ImageShape.LIST));
        }
        root.putNull("nextToken");
        return Response.ok(root).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}")
    public Response getMicrovmImage(@Context HttpHeaders headers,
                                    @PathParam("imageIdentifier") String imageIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(imageNode(service.getImage(region, imageIdentifier), ImageShape.GET)).build();
    }

    @PUT
    @Path("/microvm-images/{imageIdentifier}")
    public Response updateMicrovmImage(@Context HttpHeaders headers,
                                       @PathParam("imageIdentifier") String imageIdentifier,
                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        JsonNode updArtifact = request.path("codeArtifact");
        // Read the active version before the update mints a new one.
        String activeBefore = service.getImage(region, imageIdentifier).latestActiveImageVersion;
        MicrovmImage image = service.updateImage(region, imageIdentifier,
                text(request, "baseImageArn"), text(request, "buildRoleArn"),
                updArtifact.isMissingNode() ? null : text(updArtifact, "uri"),
                text(request, "description"));
        return Response.ok(imageNode(image, ImageShape.UPDATE, activeBefore)).build();
    }

    @DELETE
    @Path("/microvm-images/{imageIdentifier}")
    public Response deleteMicrovmImage(@Context HttpHeaders headers,
                                       @PathParam("imageIdentifier") String imageIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        MicrovmImage deleted = service.getImage(region, imageIdentifier);
        service.deleteImage(region, imageIdentifier);
        // Recorded live: the delete response carries the ARN and DELETING state.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("imageIdentifier", deleted.imageArn);
        node.put("state", "DELETING");
        return Response.ok(node).build();
    }

    // -------------------------------------------------------------- versions

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions")
    public Response listVersions(@Context HttpHeaders headers,
                                 @PathParam("imageIdentifier") String imageIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        MicrovmImage image = service.getImage(region, imageIdentifier);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (MicrovmImageVersion version : image.versions) {
            items.add(versionNode(image, version));
        }
        root.putNull("nextToken");
        return Response.ok(root).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}")
    public Response getVersion(@Context HttpHeaders headers,
                               @PathParam("imageIdentifier") String imageIdentifier,
                               @PathParam("imageVersion") String imageVersion) {
        String region = regionResolver.resolveRegion(headers);
        MicrovmImage image = service.getImage(region, imageIdentifier);
        return Response.ok(versionNode(image, service.getVersion(region, imageIdentifier, imageVersion))).build();
    }

    @PATCH
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}")
    public Response updateVersion(@Context HttpHeaders headers,
                                  @PathParam("imageIdentifier") String imageIdentifier,
                                  @PathParam("imageVersion") String imageVersion,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        MicrovmImage image = service.getImage(region, imageIdentifier);
        MicrovmImageVersion version = service.updateVersionStatus(
                region, imageIdentifier, imageVersion, text(request, "status"));
        return Response.ok(versionNode(image, version)).build();
    }

    @DELETE
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}")
    public Response deleteVersion(@Context HttpHeaders headers,
                                  @PathParam("imageIdentifier") String imageIdentifier,
                                  @PathParam("imageVersion") String imageVersion) {
        String region = regionResolver.resolveRegion(headers);
        MicrovmImage image = service.getImage(region, imageIdentifier);
        service.deleteVersion(region, imageIdentifier, imageVersion);
        // Recorded: the delete echoes what it is deleting, in DELETING.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("imageIdentifier", image.imageArn);
        node.put("imageVersion", imageVersion);
        node.put("state", "DELETING");
        return Response.ok(node).build();
    }

    // ---------------------------------------------------------------- builds

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}/builds")
    public Response listBuilds(@Context HttpHeaders headers,
                               @PathParam("imageIdentifier") String imageIdentifier,
                               @PathParam("imageVersion") String imageVersion) {
        String region = regionResolver.resolveRegion(headers);
        MicrovmImage image = service.getImage(region, imageIdentifier);
        MicrovmImageVersion version = service.getVersion(region, imageIdentifier, imageVersion);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (MicrovmBuild build : version.builds) {
            items.add(buildNode(image, version, build));
        }
        root.putNull("nextToken");
        return Response.ok(root).build();
    }

    @GET
    @Path("/microvm-images/{imageIdentifier}/versions/{imageVersion}/builds/{buildId}")
    public Response getBuild(@Context HttpHeaders headers,
                             @PathParam("imageIdentifier") String imageIdentifier,
                             @PathParam("imageVersion") String imageVersion,
                             @PathParam("buildId") String buildId) {
        String region = regionResolver.resolveRegion(headers);
        MicrovmImage image = service.getImage(region, imageIdentifier);
        MicrovmImageVersion version = service.getVersion(region, imageIdentifier, imageVersion);
        MicrovmBuild build = version.builds.stream()
                .filter(b -> b.buildId.equals(buildId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "MicroVM image build not found: " + buildId, 404));
        return Response.ok(buildNode(image, version, build, true)).build();
    }

    // -------------------------------------------------------- managed images

    @GET
    @Path("/managed-microvm-images")
    public Response listManagedImages(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(catalogNode(service.listManagedImages(region))).build();
    }

    @GET
    @Path("/managed-microvm-images/{imageIdentifier}/versions")
    public Response listManagedImageVersions(@Context HttpHeaders headers,
                                             @PathParam("imageIdentifier") String imageIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(catalogNode(service.listManagedImageVersions(region, imageIdentifier))).build();
    }

    // ------------------------------------------------------------------- vms

    @POST
    @Path("/microvms")
    public Response runMicrovm(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        Microvm vm = service.runMicrovm(region, regionResolver.getAccountId(),
                text(request, "imageIdentifier"));
        return Response.ok(vmNode(vm)).build();
    }

    @GET
    @Path("/microvms")
    public Response listMicrovms(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (Microvm vm : service.listMicrovms(region)) {
            items.add(vmSummaryNode(vm));
        }
        root.putNull("nextToken");
        return Response.ok(root).build();
    }

    @GET
    @Path("/microvms/{microvmIdentifier}")
    public Response getMicrovm(@Context HttpHeaders headers,
                               @PathParam("microvmIdentifier") String microvmIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(vmNode(service.getMicrovm(region, microvmIdentifier))).build();
    }

    @DELETE
    @Path("/microvms/{microvmIdentifier}")
    public Response terminateMicrovm(@Context HttpHeaders headers,
                                     @PathParam("microvmIdentifier") String microvmIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        service.terminateMicrovm(region, microvmIdentifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ------------------------------------------------------------- rendering

    /**
     * The four shapes an image is returned in. The module previously modelled
     * two through a boolean; the live service has four, and they differ by
     * more than one member each.
     *
     * <p>{@code CREATE} and {@code UPDATE} return full detail and differ only
     * in the state they claim and in tags — create sends tags as null, update
     * omits the member. {@code GET} is a much smaller summary carrying tags as
     * an object, and {@code LIST} is smaller again, without tags or
     * updatedAt.</p>
     */
    private enum ImageShape { CREATE, UPDATE, GET, LIST }

    private ObjectNode imageNode(MicrovmImage image, ImageShape shape) {
        return imageNode(image, shape, null);
    }

    /**
     * @param activeBeforeUpdate the version that was active before an update
     *                           minted a new one. An update reports it rather
     *                           than the version it just created, because the
     *                           new one is not built yet at the moment the PUT
     *                           answers.
     */
    private ObjectNode imageNode(MicrovmImage image, ImageShape shape, String activeBeforeUpdate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", image.name);
        node.put("imageArn", image.imageArn);
        node.put("createdAt", epoch(image.createdAt));

        boolean detail = shape == ImageShape.CREATE || shape == ImageShape.UPDATE;
        // A mutation reports the state it is entering, not the one it left.
        switch (shape) {
            case CREATE -> node.put("state", "CREATING");
            case UPDATE -> node.put("state", "UPDATING");
            default -> node.put("state", image.state);
        }

        // Builds converge instantly here, so an image has an active version the
        // moment it exists. A mutation response must not say the version it is
        // creating is already active: it reports a transitional state, and a
        // body claiming CREATING or UPDATING while naming that version as
        // active contradicts itself. A reconciler reading it would launch a
        // MicroVM off a version that, against the live service, does not exist
        // yet. Create therefore reports null and update reports whatever was
        // active before it ran.
        String active = switch (shape) {
            case CREATE -> null;
            case UPDATE -> activeBeforeUpdate;
            default -> image.latestActiveImageVersion;
        };
        if (active == null) {
            node.putNull("latestActiveImageVersion");
        } else {
            node.put("latestActiveImageVersion", active);
        }
        node.putNull("latestFailedImageVersion");

        // updatedAt is on everything but the list item.
        if (shape != ImageShape.LIST) {
            node.put("updatedAt", epoch(image.updatedAt));
        }

        if (detail) {
            if (image.description == null) {
                node.putNull("description");
            } else {
                node.put("description", image.description);
            }
            node.put("baseImageArn", image.baseImageArn);
            node.put("baseImageVersion", image.baseImageVersion);
            node.put("buildRoleArn", image.buildRoleArn);
            node.putObject("codeArtifact").put("uri", image.codeArtifactUri);
            node.put("imageVersion", image.versions.get(image.versions.size() - 1).imageVersion);
            // Neither is settable — not by the request, not by the
            // CloudFormation resource type. The service fills them in.
            node.putArray("resources").addObject().put("minimumMemoryInMiB", DEFAULT_MEMORY_MIB);
            node.putArray("egressNetworkConnectors")
                    .add(String.format(MANAGED_EGRESS_CONNECTOR_FORMAT, regionOf(image.imageArn)));
        }

        // Create sends tags as null, Get sends an object, Update omits the
        // member and List does too. The asymmetry is the service's.
        if (shape == ImageShape.CREATE) {
            node.putNull("tags");
        } else if (shape == ImageShape.GET) {
            ObjectNode tags = node.putObject("tags");
            image.tags.forEach(tags::put);
        }
        return node;
    }

    private ObjectNode versionNode(MicrovmImage image, MicrovmImageVersion version) {
        // A version response repeats the parent image's whole build spec. The
        // data is all on the image, which this method already receives; it was
        // simply never written out.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("imageArn", image.imageArn);
        node.put("imageVersion", version.imageVersion);
        node.put("state", version.state);
        node.put("status", version.status);
        node.put("createdAt", epoch(version.createdAt));
        node.put("updatedAt", epoch(image.updatedAt));
        node.put("baseImageArn", image.baseImageArn);
        node.put("baseImageVersion", image.baseImageVersion);
        node.put("buildRoleArn", image.buildRoleArn);
        node.putObject("codeArtifact").put("uri", image.codeArtifactUri);
        // A version's description is its own and nothing sets it. It stays null
        // even once the parent image has one, which is why it cannot simply be
        // read off the image.
        node.putNull("description");
        node.putNull("stateReason");
        // A version sends tags as null, unlike the image Get which sends an
        // object. Recorded.
        node.putNull("tags");
        node.putArray("resources").addObject().put("minimumMemoryInMiB", DEFAULT_MEMORY_MIB);
        node.putArray("egressNetworkConnectors")
                .add(String.format(MANAGED_EGRESS_CONNECTOR_FORMAT, regionOf(image.imageArn)));
        return node;
    }

    private ObjectNode buildNode(MicrovmImage image, MicrovmImageVersion version, MicrovmBuild build) {
        return buildNode(image, version, build, false);
    }

    /**
     * @param withSnapshot Get carries a snapshotBuild breakdown that the list
     *                     item does not.
     */
    private ObjectNode buildNode(MicrovmImage image, MicrovmImageVersion version,
                                 MicrovmBuild build, boolean withSnapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("buildId", build.buildId);
        node.put("buildState", build.buildState);
        node.put("architecture", "ARM_64");
        node.put("chipset", "GRAVITON");
        node.put("chipsetGeneration", build.chipsetGeneration);
        node.put("imageArn", image.imageArn);
        node.put("imageVersion", version.imageVersion);
        node.put("createdAt", epoch(build.createdAt));
        node.putNull("stateReason");
        node.putNull("terminationReasonCode");
        if (withSnapshot) {
            ObjectNode snapshot = node.putObject("snapshotBuild");
            snapshot.put("codeInstallSizeInBytes", CODE_INSTALL_SIZE_BYTES);
            snapshot.put("diskSnapshotSizeInBytes", DISK_SNAPSHOT_SIZE_BYTES);
            snapshot.put("memorySnapshotSizeInBytes", MEMORY_SNAPSHOT_SIZE_BYTES);
        }
        return node;
    }

    /**
     * Region segment of an ARN: {@code arn:aws:lambda:<region>:<account>:...}.
     * Read back off the image rather than from the request, so a response
     * cannot name a different region than the resource it describes.
     */
    private static String regionOf(String arn) {
        String[] parts = arn.split(":", 5);
        return parts.length > 3 ? parts[3] : "";
    }

    /**
     * The list item is a five-member summary, not the detail node. An emulator
     * returning the full node passes a naive check and still diverges: a client
     * reading a member off a list entry that the service never sends there
     * works against the emulator and breaks against AWS.
     */
    private ObjectNode vmSummaryNode(Microvm vm) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("microvmId", vm.microvmId);
        node.put("state", vm.state);
        node.put("imageArn", vm.imageArn);
        node.put("imageVersion", vm.imageVersion);
        node.put("startedAt", epoch(vm.startedAt));
        return node;
    }

    private ObjectNode vmNode(Microvm vm) {
        // Field set and defaults mirror the recorded live responses: managed
        // default connectors, the 8h maximumDurationInSeconds, and null-valued
        // members present rather than omitted.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("microvmId", vm.microvmId);
        node.put("state", vm.state);
        node.put("imageArn", vm.imageArn);
        node.put("imageVersion", vm.imageVersion);
        node.put("endpoint", vm.endpoint);
        node.put("maximumDurationInSeconds", 28800);
        String region = vm.imageArn.split(":")[3];
        node.putArray("egressNetworkConnectors")
                .add("arn:aws:lambda:" + region + ":aws:network-connector:aws-network-connector:INTERNET_EGRESS");
        node.putArray("ingressNetworkConnectors")
                .add("arn:aws:lambda:" + region + ":aws:network-connector:aws-network-connector:HTTP_INGRESS");
        node.putNull("executionRoleArn");
        node.putNull("idlePolicy");
        node.putNull("terminationReasonCode");
        if (vm.stateReason == null) {
            node.putNull("stateReason");
        } else {
            node.put("stateReason", vm.stateReason);
        }
        node.put("startedAt", epoch(vm.startedAt));
        if (vm.terminatedAt == null) {
            node.putNull("terminatedAt");
        } else {
            node.put("terminatedAt", epoch(vm.terminatedAt));
        }
        return node;
    }

    private ObjectNode catalogNode(java.util.List<java.util.Map<String, Object>> entries) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (java.util.Map<String, Object> entry : entries) {
            ObjectNode node = items.addObject();
            entry.forEach((k, v) -> {
                if (v instanceof Double d) {
                    node.put(k, d);
                } else {
                    node.put(k, String.valueOf(v));
                }
            });
        }
        root.putNull("nextToken");
        return root;
    }

    private double epoch(java.time.Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Malformed request body", 400);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
