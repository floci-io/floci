package io.github.hectorvent.floci.core.common;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Dispatcher for AWS services that share the REST {@code /v1/tags/{resourceArn}} path
 * (AppSync, Batch, ...).
 *
 * <p>Same mechanism as {@link SharedTagsController}, one prefix over. Before this existed,
 * {@code AppSyncController} claimed {@code /v1/tags/{resourceArn: .+}} for itself, so every
 * other service's {@code TagResource} on that path was answered by AppSync's own handler -
 * AWS Batch's came back {@code 404 NotFoundException: GraphQL API not found}
 * (lex00/floci#72). Registering a {@link TagHandler} whose {@link TagHandler#tagPathPrefix()}
 * is {@code /v1/tags} is now all a service needs to be served here.
 */
@Path("/v1/tags")
@Produces(MediaType.APPLICATION_JSON)
public class SharedTagsV1Controller {

    /** The {@link TagHandler#tagPathPrefix()} this controller serves. */
    public static final String PREFIX = "/v1/tags";

    private final SharedTagsDispatcher dispatcher;

    @Inject
    public SharedTagsV1Controller(SharedTagsDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GET
    @Path("/{arn: .+}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return dispatcher.listTags(PREFIX, headers, arn);
    }

    @POST
    @Path("/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response tagResource(@Context HttpHeaders headers,
                                @PathParam("arn") String arn,
                                String body) {
        return dispatcher.tagResourcePost(PREFIX, headers, arn, body);
    }

    @DELETE
    @Path("/{arn: .+}")
    public Response untagResource(@Context HttpHeaders headers,
                                  @Context UriInfo uriInfo,
                                  @PathParam("arn") String arn) {
        return dispatcher.untagResource(PREFIX, headers, uriInfo, arn, Response.noContent().build());
    }
}
