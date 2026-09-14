package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Base REST controller for tag endpoints mounted at {@code /{arn: .+}}.
 *
 * <p>Shared between {@link SharedTagsController} (mounted at {@code /tags}) and
 * {@link V1TagsController} (mounted at {@code /v1/tags}).
 */
@Produces(MediaType.APPLICATION_JSON)
public abstract class AbstractTagsController {

    private final TagDispatcher dispatcher;

    protected AbstractTagsController(TagDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    protected TagDispatcher dispatcher() {
        return dispatcher;
    }

    @GET
    @Path("/{arn: .+}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return dispatcher.listTagsForArn(headers, arn);
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
    @Path("/{arn: .+}")
    public Response untagResource(@Context HttpHeaders headers,
                                  @Context UriInfo uriInfo,
                                  @PathParam("arn") String arn) {
        TagHandler handler = dispatcher.resolveHandler(arn);
        return dispatcher.untagResourceForArn(headers, uriInfo, arn,
                Response.status(handler.untagResourceSuccessStatus()).build(), handler);
    }
}
