package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** AWS Security Hub CSPM tagging on the shared REST /tags/{ResourceArn} path. */
@ApplicationScoped
public class SecurityHubTagHandler implements TagHandler {
    private final SecurityHubService service;

    @Inject
    public SecurityHubTagHandler(SecurityHubService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "securityhub";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.tagsForResource(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(region, arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(region, arn, tagKeys);
    }
}
