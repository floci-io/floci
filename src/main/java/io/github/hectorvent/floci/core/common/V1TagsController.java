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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Dispatcher for the AWS services that put their tag endpoints on {@code /v1/tags/{resourceArn}}
 * (AppSync, MSK).
 *
 * <p>Same problem and same resolution as {@link SharedTagsController}, one path up: AWS tells
 * these services apart by hostname, floci serves them all on one port, so the owning service is
 * resolved from the {@code service} segment of the request ARN and the request handed to the
 * matching {@link TagHandler}. Handlers opt in to this path with {@link V1Tags}.
 */
@Path("/v1/tags")
@Produces(MediaType.APPLICATION_JSON)
public class V1TagsController {

    private final TagDispatcher dispatcher;

    @Inject
    public V1TagsController(@V1Tags Instance<TagHandler> handlers,
                            RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        this.dispatcher = new TagDispatcher(handlers, regionResolver, objectMapper);
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
