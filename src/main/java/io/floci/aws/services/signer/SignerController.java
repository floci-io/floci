package io.floci.aws.services.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.signer.model.SigningJob;
import io.floci.aws.services.signer.model.SigningProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * The REST JSON routes AWS Signer publishes, mounted under {@link SignerRouteFilter#INTERNAL_PREFIX}.
 *
 * <p>Signer's public paths are literal ({@code /signing-profiles}, {@code /signing-jobs},
 * {@code /signing-platforms}, {@code /revocations}) and S3's path-style routes would otherwise claim
 * them as buckets, so {@link SignerRouteFilter} rewrites a request signed for {@code signer} onto the
 * internal prefix before route matching. The shared {@code /tags/{resourceArn}} routes are served by
 * {@code SharedTagsController} through {@link SignerService} as a {@code TagHandler}.
 */
@Path(SignerRouteFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SignerController {

    private final SignerService signerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SignerController(SignerService signerService, RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        this.signerService = signerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/signing-profiles/{profileName}")
    public Response putSigningProfile(@PathParam("profileName") String profileName,
                                      @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        SigningProfile profile = signerService.putSigningProfile(profileName, readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", profile.getArn());
        response.put("profileVersion", profile.getProfileVersion());
        response.put("profileVersionArn", profile.getProfileVersionArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/signing-profiles/{profileName}")
    public Response getSigningProfile(@PathParam("profileName") String profileName,
                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(profileNode(signerService.getSigningProfile(profileName, region), true))
                .build();
    }

    @GET
    @Path("/signing-profiles")
    public Response listSigningProfiles(@Context HttpHeaders headers,
                                        @QueryParam("platformId") String platformId,
                                        @QueryParam("statuses") List<String> statuses,
                                        @QueryParam("includeCanceled") Boolean includeCanceled,
                                        @QueryParam("maxResults") Integer maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        SignerService.Page<SigningProfile> page = signerService.listSigningProfiles(
                region, platformId, statuses, includeCanceled, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode profiles = response.putArray("profiles");
        for (SigningProfile profile : page.items()) {
            profiles.add(profileNode(profile, false));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/signing-profiles/{profileName}")
    public Response cancelSigningProfile(@PathParam("profileName") String profileName,
                                         @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        signerService.cancelSigningProfile(profileName, region);
        return Response.ok().build();
    }

    @PUT
    @Path("/signing-profiles/{profileName}/revoke")
    public Response revokeSigningProfile(@PathParam("profileName") String profileName,
                                         @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        signerService.revokeSigningProfile(profileName, readTree(body), region);
        return Response.ok().build();
    }

    @POST
    @Path("/signing-profiles/{profileName}/permissions")
    public Response addProfilePermission(@PathParam("profileName") String profileName,
                                         @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        String revisionId = signerService.addProfilePermission(profileName, readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("revisionId", revisionId);
        return Response.ok(response).build();
    }

    @GET
    @Path("/signing-profiles/{profileName}/permissions")
    public Response listProfilePermissions(@PathParam("profileName") String profileName,
                                           @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<SigningProfile.ProfilePermission> permissions =
                signerService.listProfilePermissions(profileName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("revisionId", signerService.getSigningProfile(profileName, region)
                .getRevisionId());
        response.put("policySizeBytes", permissions.size() * 128);
        ArrayNode entries = response.putArray("permissions");
        for (SigningProfile.ProfilePermission permission : permissions) {
            ObjectNode entry = entries.addObject();
            entry.put("action", permission.getAction());
            entry.put("principal", permission.getPrincipal());
            entry.put("statementId", permission.getStatementId());
            entry.put("profileVersion", permission.getProfileVersion());
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/signing-profiles/{profileName}/permissions/{statementId}")
    public Response removeProfilePermission(@PathParam("profileName") String profileName,
                                            @PathParam("statementId") String statementId,
                                            @QueryParam("revisionId") String revisionId,
                                            @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        String newRevisionId = signerService.removeProfilePermission(
                profileName, statementId, revisionId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("revisionId", newRevisionId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/signing-jobs")
    public Response startSigningJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        SigningJob job = signerService.startSigningJob(readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.getJobId());
        response.put("jobOwner", job.getJobOwner());
        return Response.ok(response).build();
    }

    @GET
    @Path("/signing-jobs")
    public Response listSigningJobs(@Context HttpHeaders headers,
                                    @QueryParam("status") String status,
                                    @QueryParam("platformId") String platformId,
                                    @QueryParam("isRevoked") Boolean isRevoked,
                                    @QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        SignerService.Page<SigningJob> page = signerService.listSigningJobs(
                region, status, platformId, isRevoked, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobs");
        for (SigningJob job : page.items()) {
            jobs.add(jobNode(job, false));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/signing-jobs/{jobId}")
    public Response describeSigningJob(@PathParam("jobId") String jobId,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(jobNode(signerService.describeSigningJob(jobId, region), true)).build();
    }

    @PUT
    @Path("/signing-jobs/{jobId}/revoke")
    public Response revokeSignature(@PathParam("jobId") String jobId,
                                    @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        signerService.revokeSignature(jobId, readTree(body), region);
        return Response.ok().build();
    }

    @GET
    @Path("/revocations")
    public Response getRevocationStatus(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode revokedEntities = response.putArray("revokedEntities");
        for (String entity : signerService.getRevocationStatus()) {
            revokedEntities.add(entity);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/signing-platforms")
    public Response listSigningPlatforms(@Context HttpHeaders headers,
                                         @QueryParam("category") String category,
                                         @QueryParam("partner") String partner,
                                         @QueryParam("target") String target) {
        List<SignerPlatforms.Platform> platforms = signerService.listSigningPlatforms(category);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("platforms");
        for (SignerPlatforms.Platform platform : platforms) {
            if (partner != null && !partner.equals(platform.partner())) {
                continue;
            }
            if (target != null && !target.equals(platform.target())) {
                continue;
            }
            entries.add(platformNode(platform));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/signing-platforms/{platformId}")
    public Response getSigningPlatform(@PathParam("platformId") String platformId,
                                       @Context HttpHeaders headers) {
        return Response.ok(platformNode(signerService.getSigningPlatform(platformId))).build();
    }

    private ObjectNode profileNode(SigningProfile profile, boolean detail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("profileName", profile.getProfileName());
        node.put("profileVersion", profile.getProfileVersion());
        node.put("profileVersionArn", profile.getProfileVersionArn());
        node.put("arn", profile.getArn());
        node.put("platformId", profile.getPlatformId());
        node.put("platformDisplayName", profile.getPlatformDisplayName());
        node.put("status", profile.getStatus());
        if (profile.getSigningMaterialCertificateArn() != null) {
            node.putObject("signingMaterial")
                    .put("certificateArn", profile.getSigningMaterialCertificateArn());
        }
        if (profile.getSignatureValidityPeriodType() != null) {
            ObjectNode period = node.putObject("signatureValidityPeriod");
            period.put("type", profile.getSignatureValidityPeriodType());
            period.put("value", profile.getSignatureValidityPeriodValue());
        }
        if (profile.getOverrides() != null) {
            node.set("overrides", profile.getOverrides());
        }
        node.set("signingParameters", objectMapper.valueToTree(profile.getSigningParameters()));
        node.set("tags", objectMapper.valueToTree(profile.getTags()));
        if (detail) {
            if (profile.getStatusReason() != null) {
                node.put("statusReason", profile.getStatusReason());
            }
            if (profile.getRevokedAt() != null) {
                ObjectNode record = node.putObject("revocationRecord");
                record.put("revocationEffectiveFrom", profile.getRevocationEffectiveFrom());
                record.put("revokedAt", profile.getRevokedAt());
                record.put("revokedBy", profile.getRevokedBy());
            }
        }
        return node;
    }

    private ObjectNode jobNode(SigningJob job, boolean detail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.getJobId());
        node.put("jobArn", job.getJobArn());
        ObjectNode sourceS3 = node.putObject("source").putObject("s3");
        sourceS3.put("bucketName", job.getSourceBucketName());
        sourceS3.put("key", job.getSourceKey());
        sourceS3.put("version", job.getSourceVersion());
        if (job.getSignedObjectBucketName() != null) {
            ObjectNode signed = node.putObject("signedObject").putObject("s3");
            signed.put("bucketName", job.getSignedObjectBucketName());
            signed.put("key", job.getSignedObjectKey());
        }
        node.put("profileName", job.getProfileName());
        node.put("profileVersion", job.getProfileVersion());
        node.put("platformId", job.getPlatformId());
        node.put("platformDisplayName", job.getPlatformDisplayName());
        node.put("status", job.getStatus());
        node.put("isRevoked", job.isRevoked());
        node.put("createdAt", job.getCreatedAt());
        node.put("jobOwner", job.getJobOwner());
        node.put("jobInvoker", job.getJobInvoker());
        if (job.getSignatureExpiresAt() != null) {
            node.put("signatureExpiresAt", job.getSignatureExpiresAt());
        }
        if (detail) {
            if (job.getCompletedAt() != null) {
                node.put("completedAt", job.getCompletedAt());
            }
            if (job.getStatusReason() != null) {
                node.put("statusReason", job.getStatusReason());
            }
            if (job.getRequestedBy() != null) {
                node.put("requestedBy", job.getRequestedBy());
            }
            if (job.isRevoked()) {
                ObjectNode record = node.putObject("revocationRecord");
                record.put("reason", job.getRevocationReason());
                record.put("revokedAt", job.getRevokedAt());
                record.put("revokedBy", job.getRevokedBy());
            }
        }
        return node;
    }

    private ObjectNode platformNode(SignerPlatforms.Platform platform) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("platformId", platform.platformId());
        node.put("displayName", platform.displayName());
        node.put("partner", platform.partner());
        node.put("target", platform.target());
        node.put("category", platform.category());
        ObjectNode configuration = node.putObject("signingConfiguration");
        ObjectNode encryption = configuration.putObject("encryptionAlgorithmOptions");
        encryption.set("allowedValues", objectMapper.valueToTree(platform.encryptionAlgorithms()));
        encryption.put("defaultValue", platform.defaultEncryptionAlgorithm());
        ObjectNode hash = configuration.putObject("hashAlgorithmOptions");
        hash.set("allowedValues", objectMapper.valueToTree(platform.hashAlgorithms()));
        hash.put("defaultValue", platform.defaultHashAlgorithm());
        ObjectNode imageFormat = node.putObject("signingImageFormat");
        imageFormat.set("supportedFormats", objectMapper.valueToTree(platform.imageFormats()));
        imageFormat.put("defaultFormat", platform.defaultImageFormat());
        node.put("maxSizeInMB", platform.maxSizeInMB());
        node.put("revocationSupported", platform.revocationSupported());
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
