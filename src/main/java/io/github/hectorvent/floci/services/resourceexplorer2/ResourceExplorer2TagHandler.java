package io.github.hectorvent.floci.services.resourceexplorer2;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Handles tag operations on Resource Explorer 2 indexes and views.
 *
 * <p>Discovered by {@link io.github.hectorvent.floci.core.common.SharedTagsController}
 * via CDI. Routes {@code /tags/{arn}} requests where the ARN service segment is
 * {@code "resource-explorer-2"}.
 */
@ApplicationScoped
public class ResourceExplorer2TagHandler implements TagHandler {

    private final ResourceExplorer2Service service;

    @Inject
    public ResourceExplorer2TagHandler(ResourceExplorer2Service service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "resource-explorer-2";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTags(arn);
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
