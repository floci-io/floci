package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * AppSync's tag endpoints, which AWS puts on {@code /v1/tags/{resourceArn}}.
 *
 * <p>These used to be three handlers on {@code AppSyncController}. MSK's tag API lives on the
 * same path, and floci serves both services on one port, so the path had to move behind the
 * ARN-dispatching {@code V1TagsController} - two JAX-RS resource methods cannot claim the same
 * path and method. The wire shape is unchanged: a lowercase {@code tags} map, POST to tag,
 * {@code tagKeys} to untag, 204 on both.
 */
@ApplicationScoped
@V1Tags
public class AppSyncTagHandler implements TagHandler {

    private final AppSyncService service;

    @Inject
    public AppSyncTagHandler(AppSyncService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "appsync";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.getTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys);
    }
}
