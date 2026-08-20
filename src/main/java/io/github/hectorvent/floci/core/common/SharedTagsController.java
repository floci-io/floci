package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Dispatcher for AWS services that share the REST {@code /tags/{resourceArn}} path
 * (API Gateway, EventBridge Scheduler, EKS, ...).
 *
 * <p>The routing itself lives in {@link SharedTagsDispatcher}, which resolves the owning
 * service from the {@code service} segment of the request ARN and looks the handler up
 * under this controller's own path prefix. Services whose tag endpoints hang off a
 * different prefix have their own controller over the same dispatcher
 * ({@link SharedTagsV1Controller}).
 */
@Path("/tags")
@Produces(MediaType.APPLICATION_JSON)
public class SharedTagsController {

    /** The {@link TagHandler#tagPathPrefix()} this controller serves. */
    public static final String PREFIX = "/tags";

    private final SharedTagsDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    @Inject
    public SharedTagsController(SharedTagsDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response listTagsByQuery(@Context HttpHeaders headers,
                                    @QueryParam("resourceArn") String arn) {
        return dispatcher.listTags(PREFIX, headers, arn);
    }

    @GET
    @Path("/{arn: .+}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return dispatcher.listTags(PREFIX, headers, arn);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourceByBody(@Context HttpHeaders headers, String body) {
        return dispatcher.tagResourceByBody(PREFIX, headers, body);
    }

    @POST
    @Path("/{arn: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePost(@Context HttpHeaders headers,
                                    @PathParam("arn") String arn,
                                    String body) {
        return dispatcher.tagResourcePost(PREFIX, headers, arn, body);
    }

    @PUT
    @Path("/{arn: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePut(@Context HttpHeaders headers,
                                   @PathParam("arn") String arn,
                                   String body) {
        return dispatcher.tagResourcePut(PREFIX, headers, arn, body);
    }

    @DELETE
    public Response untagResourceByQuery(@Context HttpHeaders headers,
                                         @Context UriInfo uriInfo,
                                         @QueryParam("resourceArn") String arn) {
        return dispatcher.untagResource(PREFIX, headers, uriInfo, arn,
                Response.ok(objectMapper.createObjectNode()).build());
    }

    @DELETE
    @Path("/{arn: .+}")
    public Response untagResource(@Context HttpHeaders headers,
                                  @Context UriInfo uriInfo,
                                  @PathParam("arn") String arn) {
        return dispatcher.untagResource(PREFIX, headers, uriInfo, arn, Response.noContent().build());
    }
}
