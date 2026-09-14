package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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
public class V1TagsController extends AbstractTagsController {

    @Inject
    public V1TagsController(@V1Tags Instance<TagHandler> handlers,
                            RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        super(new TagDispatcher(handlers, regionResolver, objectMapper));
    }
}
