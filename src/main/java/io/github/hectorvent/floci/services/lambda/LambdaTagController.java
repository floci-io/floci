package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
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
 * Lambda tag endpoints — use the /2017-03-31 API version prefix.
 *
 * TagResource:   POST   /2017-03-31/tags/{ARN}
 * ListTags:      GET    /2017-03-31/tags/{ARN}
 * UntagResource: DELETE /2017-03-31/tags/{ARN}?tagKeys=...
 */
@Path("/2017-03-31")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaTagController {

    private final LambdaService lambdaService;
    private final io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService microvmsService;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaTagController(LambdaService lambdaService,
                               io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService microvmsService,
                               ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.microvmsService = microvmsService;
        this.objectMapper = objectMapper;
    }

    /**
     * MicroVM-family ARNs (microvm-image, microvm, network-connector) share
     * the /2017-03-31/tags route with classic Lambda resources; dispatch on
     * the ARN's resource type. The region comes from the ARN itself.
     */
    private static String arnRegion(String arn) {
        String[] parts = arn.split(":");
        return parts.length > 3 ? parts[3] : "";
    }

    @GET
    @Path("/tags/{arn}")
    public Response listTags(@PathParam("arn") String arn) {
        if (io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.ownsArn(arn)) {
            ObjectNode mvRoot = objectMapper.createObjectNode();
            ObjectNode mvTags = mvRoot.putObject("Tags");
            microvmsService.listTags(arnRegion(arn), arn).forEach(mvTags::put);
            return Response.ok(mvRoot).build();
        }
        Map<String, String> tags = lambdaService.listTags(arn);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("Tags");
        tags.forEach(tagsNode::put);
        return Response.ok(root).build();
    }

    @POST
    @Path("/tags/{arn}")
    public Response tagResource(@PathParam("arn") String arn, String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> tags = (Map<String, String>) request.get("Tags");
            if (tags == null) {
                throw new AwsException("InvalidParameterValueException", "Tags is required", 400);
            }
            if (io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.ownsArn(arn)) {
                microvmsService.tagResource(arnRegion(arn), arn, tags);
            } else {
                lambdaService.tagResource(arn, tags);
            }
            return Response.noContent().build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/tags/{arn}")
    public Response untagResource(@PathParam("arn") String arn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        if (io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.ownsArn(arn)) {
            microvmsService.untagResource(arnRegion(arn), arn, tagKeys);
        } else {
            lambdaService.untagResource(arn, tagKeys);
        }
        return Response.noContent().build();
    }
}
