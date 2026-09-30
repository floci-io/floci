package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.TaggingApiOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Lets Resource Groups Tagging API writes reach a function's own tags.
 *
 * <p>A separate {@link TaggingApiOnly} bean because Lambda tags on its own
 * {@code /2017-03-31/tags/{arn}} path: a default-qualified handler would also serve the
 * function on {@code /tags/{arn}}, which AWS does not define.
 */
@ApplicationScoped
@TaggingApiOnly
public class LambdaTagHandler implements TagHandler {

    private final LambdaService lambdaService;

    @Inject
    public LambdaTagHandler(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public String serviceKey() {
        return "lambda";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return lambdaService.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        lambdaService.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        lambdaService.untagResource(arn, tagKeys);
    }
}
