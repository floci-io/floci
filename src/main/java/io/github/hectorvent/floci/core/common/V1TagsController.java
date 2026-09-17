package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Dispatcher for the AWS services that put their tag endpoints on {@code /v1/tags/{resourceArn}}
 * (AppSync, MSK) or, for CodeArtifact, on the bare {@code /v1/tags} path with {@code resourceArn}
 * as a query parameter.
 *
 * <p>Same problem and same resolution as {@link SharedTagsController}, one path up: AWS tells
 * these services apart by hostname, floci serves them all on one port, so the owning service is
 * resolved from the {@code service} segment of the request ARN and the request handed to the
 * matching {@link TagHandler}. Handlers opt in to this path with {@link V1Tags}.
 */
@Path("/v1/tags")
@Produces(MediaType.APPLICATION_JSON)
public class V1TagsController extends AbstractTagsController {

    @Inject
    public V1TagsController(@V1Tags Instance<TagHandler> handlers,
                            RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        super(new TagDispatcher(handlers, regionResolver, objectMapper));
    }

    /**
     * {@code ListTagsForResource} for services that address the resource with a query
     * parameter on the bare path ({@code POST /v1/tags?resourceArn=...}) rather than a path
     * segment. CodeArtifact is the only registered handler on this shape today.
     *
     * <p>This lives here rather than on {@link AbstractTagsController} because
     * {@link SharedTagsController} already declares its own bare {@code POST} ({@code
     * tagResourceByBody}), and a bare {@code POST} on the shared base would collide with it.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response listTagsForResourceByQuery(@Context HttpHeaders headers,
                                               @QueryParam("resourceArn") String resourceArn) {
        return dispatcher().listTagsForArn(headers, resourceArn);
    }
}
