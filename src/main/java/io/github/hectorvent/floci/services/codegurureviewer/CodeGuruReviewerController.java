package io.github.hectorvent.floci.services.codegurureviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codegurureviewer.model.RepositoryAssociation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon CodeGuru Reviewer REST-JSON controller.
 *
 * <p>Tagging goes through the shared {@code /tags/{resourceArn}} path, which
 * {@link CodeGuruReviewerService} serves as a {@code TagHandler}.
 *
 * <p>Only the operations declared below are served; anything else falls through to the
 * emulator's not-found handling rather than a stub success.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CodeGuruReviewerController {

    private final CodeGuruReviewerService codeGuruReviewerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeGuruReviewerController(CodeGuruReviewerService codeGuruReviewerService,
                                      RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.codeGuruReviewerService = codeGuruReviewerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/associations")
    public Response associateRepository(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        RepositoryAssociation association = codeGuruReviewerService.associateRepository(
                request.get("Repository"), request.get("KMSKeyDetails"),
                parseTags(request.get("Tags")), region);
        return Response.ok(associationResponse(association)).build();
    }

    @GET
    @Path("/associations/{associationArn}")
    public Response describeRepositoryAssociation(@PathParam("associationArn") String associationArn,
                                                  @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(associationResponse(
                codeGuruReviewerService.describeRepositoryAssociation(associationArn, region))).build();
    }

    @DELETE
    @Path("/associations/{associationArn}")
    public Response disassociateRepository(@PathParam("associationArn") String associationArn,
                                           @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(associationResponse(
                codeGuruReviewerService.disassociateRepository(associationArn, region))).build();
    }

    /**
     * The filter query keys are singular ({@code ProviderType=...&State=...}) because that is
     * the wire mapping the API reference documents for the plural request fields
     * ({@code ProviderTypes}, {@code States}, ...): the SDK serializes each list member as a
     * repeated singular query parameter.
     */
    @GET
    @Path("/associations")
    public Response listRepositoryAssociations(@QueryParam("ProviderType") List<String> providerTypes,
                                               @QueryParam("State") List<String> states,
                                               @QueryParam("Name") List<String> names,
                                               @QueryParam("Owner") List<String> owners,
                                               @QueryParam("MaxResults") String maxResults,
                                               @QueryParam("NextToken") String nextToken,
                                               @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<RepositoryAssociation> page = codeGuruReviewerService.listRepositoryAssociations(
                providerTypes, states, names, owners, parseMaxResults(maxResults), nextToken, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("RepositoryAssociationSummaries");
        for (RepositoryAssociation association : page.items()) {
            ObjectNode summary = summaries.addObject();
            summary.put("AssociationArn", association.getAssociationArn());
            if (association.getConnectionArn() != null) {
                summary.put("ConnectionArn", association.getConnectionArn());
            }
            summary.put("LastUpdatedTimeStamp", epochSeconds(association.getLastUpdatedTimeStamp()));
            summary.put("AssociationId", association.getAssociationId());
            summary.put("Name", association.getName());
            summary.put("Owner", association.getOwner());
            summary.put("ProviderType", association.getProviderType());
            summary.put("State", association.getState());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode associationResponse(RepositoryAssociation association) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RepositoryAssociation", associationNode(association));
        response.set("Tags", objectMapper.valueToTree(
                association.getTags() != null ? association.getTags() : Map.of()));
        return response;
    }

    private ObjectNode associationNode(RepositoryAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AssociationId", association.getAssociationId());
        node.put("AssociationArn", association.getAssociationArn());
        if (association.getConnectionArn() != null) {
            node.put("ConnectionArn", association.getConnectionArn());
        }
        node.put("Name", association.getName());
        node.put("Owner", association.getOwner());
        node.put("ProviderType", association.getProviderType());
        node.put("State", association.getState());
        node.put("StateReason", association.getStateReason());
        node.put("CreatedTimeStamp", epochSeconds(association.getCreatedTimeStamp()));
        node.put("LastUpdatedTimeStamp", epochSeconds(association.getLastUpdatedTimeStamp()));

        ObjectNode kmsKeyDetails = node.putObject("KMSKeyDetails");
        kmsKeyDetails.put("EncryptionOption", association.getEncryptionOption());
        if (association.getKmsKeyId() != null) {
            kmsKeyDetails.put("KMSKeyId", association.getKmsKeyId());
        }

        if (association.getS3BucketName() != null) {
            ObjectNode s3Details = node.putObject("S3RepositoryDetails");
            s3Details.put("BucketName", association.getS3BucketName());
            if (association.getSourceCodeArtifactsObjectKey() != null) {
                ObjectNode codeArtifacts = s3Details.putObject("CodeArtifacts");
                codeArtifacts.put("SourceCodeArtifactsObjectKey", association.getSourceCodeArtifactsObjectKey());
                if (association.getBuildArtifactsObjectKey() != null) {
                    codeArtifacts.put("BuildArtifactsObjectKey", association.getBuildArtifactsObjectKey());
                }
            }
        }
        return node;
    }

    private long epochSeconds(Instant instant) {
        return instant != null ? instant.getEpochSecond() : 0L;
    }

    // Bound as String so a malformed value reaches this method: a JAX-RS Integer binding
    // fails during parameter conversion and surfaces as 404, where AWS models 400.
    private Integer parseMaxResults(String maxResults) {
        if (maxResults == null || maxResults.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(maxResults);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + maxResults
                            + "' at 'maxResults' failed to satisfy constraint: Member must be an integer.", 400);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }
}
