package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TaggingApiOnly;
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
 *
 * MicroVM-family ARNs share this route with classic Lambda resources; {@link LambdaTagHandler}
 * dispatches between them.
 */
@Path("/2017-03-31")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaTagController {

    private final LambdaTagHandler tagHandler;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaTagController(@TaggingApiOnly LambdaTagHandler tagHandler, ObjectMapper objectMapper) {
        this.tagHandler = tagHandler;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/tags/{arn}")
    public Response listTags(@PathParam("arn") String arn) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("Tags");
        tagHandler.listTags(null, arn).forEach(tagsNode::put);
        return Response.ok(root).build();
    }

    @POST
    @Path("/tags/{arn}")
    public Response tagResource(@PathParam("arn") String arn, String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            Map<String, String> tags = LambdaService.requestTags(request);
            if (tags == null) {
                throw new AwsException("InvalidParameterValueException", "Tags is required", 400);
            }
            tagHandler.tagResource(null, arn, tags);
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
        tagHandler.untagResource(null, arn, tagKeys);
        return Response.noContent().build();
    }
}
