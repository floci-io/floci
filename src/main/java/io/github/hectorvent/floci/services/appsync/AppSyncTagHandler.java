package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.core.common.SharedTagsV1Controller;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for AppSync.
 *
 * <p>ARN format: {@code arn:aws:appsync:<region>:<account>:apis/<apiId>}. The tag store is
 * the GraphQL API itself, so an unknown API surfaces as AppSync's own
 * {@code NotFoundException} exactly as it did when {@code AppSyncController} owned the path.
 */
@ApplicationScoped
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
    public String tagPathPrefix() {
        return SharedTagsV1Controller.PREFIX;
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
