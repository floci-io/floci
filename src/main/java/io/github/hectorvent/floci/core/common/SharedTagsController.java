package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
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
 * <p>AWS distinguishes these services by hostname, but floci serves every service on a
 * single port, so the path alone is ambiguous. This controller resolves the owning
 * service from the {@code service} segment of the request ARN
 * ({@code arn:aws:<service>:<region>:<account>:<resource>}) and dispatches to the
 * matching {@link TagHandler}.
 *
 * <p>The resolution and wire-shape logic lives in {@link TagDispatcher}, shared with
 * {@link V1TagsController}, which does the same job for the services AWS puts on
 * {@code /v1/tags/{resourceArn}} instead.
 */
@Path("/tags")
@Produces(MediaType.APPLICATION_JSON)
public class SharedTagsController {

    private final TagDispatcher dispatcher;

    @Inject
    public SharedTagsController(Instance<TagHandler> handlers,
                                RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        this.dispatcher = new TagDispatcher(handlers, regionResolver, objectMapper);
    }

    @GET
    public Response listTagsByQuery(@Context HttpHeaders headers,
                                    @QueryParam("resourceArn") String arn) {
        return dispatcher.listTagsForArn(headers, arn);
    }

    @GET
    @Path("/{arn: .+}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return dispatcher.listTagsForArn(headers, arn);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourceByBody(@Context HttpHeaders headers, String body) {
        String arn = dispatcher.readResourceArn(body);
        TagHandler handler = dispatcher.resolveHandler(arn);
        return dispatcher.doTagResource(headers, handler, arn, body,
                Response.ok(dispatcher.emptyObject()).build());
    }

    @POST
    @Path("/{arn: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePost(@Context HttpHeaders headers,
                                    @PathParam("arn") String arn,
                                    String body) {
        TagHandler handler = dispatcher.resolveHandler(arn);
        return dispatcher.tagResourcePost(headers, arn, body,
                Response.status(handler.tagResourceSuccessStatus()).build());
    }

    @PUT
    @Path("/{arn: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePut(@Context HttpHeaders headers,
                                   @PathParam("arn") String arn,
                                   String body) {
        TagHandler handler = dispatcher.resolveHandler(arn);
        return dispatcher.tagResourcePut(headers, arn, body,
                Response.status(handler.tagResourceSuccessStatus()).build());
    }

    @DELETE
    public Response untagResourceByQuery(@Context HttpHeaders headers,
                                         @Context UriInfo uriInfo,
                                         @QueryParam("resourceArn") String arn) {
        return dispatcher.untagResourceForArn(headers, uriInfo, arn,
                Response.ok(dispatcher.emptyObject()).build());
    }

    @DELETE
    @Path("/{arn: .+}")
    public Response untagResource(@Context HttpHeaders headers,
                                   @Context UriInfo uriInfo,
                                   @PathParam("arn") String arn) {
        TagHandler handler = dispatcher.resolveHandler(arn);
        return dispatcher.untagResourceForArn(headers, uriInfo, arn,
                Response.status(handler.untagResourceSuccessStatus()).build(), handler);
    }
}
