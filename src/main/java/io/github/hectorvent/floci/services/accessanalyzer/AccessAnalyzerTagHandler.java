package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Registers Access Analyzer's ARN service segment ({@code access-analyzer}) with the generic
 * {@code SharedTagsController}, so {@code TagResource}/{@code UntagResource}/
 * {@code ListTagsForResource} against an analyzer ARN dispatch here with no service-specific
 * route needed in {@link AccessAnalyzerController}. Uses the default {@code "/tags"} prefix
 * and default lowercase {@code "tags"} body key — AccessAnalyzer is a {@code restJson1}
 * service and follows the common (not the PascalCase-legacy) tagging contract.
 */
@ApplicationScoped
public class AccessAnalyzerTagHandler implements TagHandler {

    private final AccessAnalyzerService service;

    @Inject
    public AccessAnalyzerTagHandler(AccessAnalyzerService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "access-analyzer";
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
