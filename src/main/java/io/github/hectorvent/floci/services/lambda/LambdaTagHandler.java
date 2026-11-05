package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.TaggingApiOnly;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Lets Resource Groups Tagging API writes reach a function's own tags, and those of a MicroVM
 * image or network connector.
 *
 * <p>Also the one place that routes MicroVM-family ARNs apart from classic Lambda resources:
 * {@link LambdaTagController} calls it too. The {@code region} argument is unused, because a
 * MicroVM ARN carries its own.
 *
 * <p>A separate {@link TaggingApiOnly} bean because Lambda tags on its own
 * {@code /2017-03-31/tags/{arn}} path: a default-qualified handler would also serve the
 * function on {@code /tags/{arn}}, which AWS does not define.
 */
@ApplicationScoped
@TaggingApiOnly
public class LambdaTagHandler implements TagHandler {

    private final LambdaService lambdaService;
    private final LambdaMicrovmsService microvmsService;

    @Inject
    public LambdaTagHandler(LambdaService lambdaService, LambdaMicrovmsService microvmsService) {
        this.lambdaService = lambdaService;
        this.microvmsService = microvmsService;
    }

    @Override
    public String serviceKey() {
        return "lambda";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        if (LambdaMicrovmsService.ownsArn(arn)) {
            return microvmsService.listTags(arnRegion(arn), arn);
        }
        return lambdaService.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (LambdaMicrovmsService.ownsArn(arn)) {
            microvmsService.tagResource(arnRegion(arn), arn, tags);
            return;
        }
        lambdaService.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (LambdaMicrovmsService.ownsArn(arn)) {
            microvmsService.untagResource(arnRegion(arn), arn, tagKeys);
            return;
        }
        lambdaService.untagResource(arn, tagKeys);
    }

    private static String arnRegion(String arn) {
        return AwsArnUtils.parse(arn).region();
    }
}
