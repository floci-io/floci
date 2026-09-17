package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;

/**
 * SES V2 resource-tag endpoints ({@code /v2/email/tags}), split out of {@link SesController}.
 * Every operation keeps going through the {@link SesService} facade, which parses the resource
 * ARN and dispatches to whichever of the seven taggable domains owns it; that cross-domain
 * dispatch is the facade's job by the survival rule, so this class is a pure size split like
 * {@code LambdaTagController}.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesTagController {

    private static final Logger LOG = Logger.getLogger(SesTagController.class);

    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesTagController(SesService sesService, RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/tags")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            String arn = request.path("ResourceArn").asText(null);
            if (arn == null || arn.isBlank()) {
                throw new AwsException("BadRequestException", "ResourceArn is required.", 400);
            }
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            if (tags == null) {
                throw new AwsException("BadRequestException", "Tags must be an array.", 400);
            }
            sesService.tagResource(arn, region, tags);
            LOG.infov("SES V2 TagResource: {0}", arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/tags")
    public Response untagResource(@Context HttpHeaders headers,
                                   @QueryParam("ResourceArn") String arn,
                                   @QueryParam("TagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.untagResource(arn, region, tagKeys);
            LOG.infov("SES V2 UntagResource: {0}", arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/tags")
    public Response listTagsForResource(@Context HttpHeaders headers,
                                         @QueryParam("ResourceArn") String arn) {
        String region = regionResolver.resolveRegion(headers);
        try {
            List<Tag> tags = sesService.listResourceTags(arn, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("Tags");
            for (Tag t : tags) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                arr.add(tagNode);
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }
}
