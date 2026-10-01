package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.TaggingApiOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Lets Resource Groups Tagging API writes reach a queue's own tags.
 *
 * <p>A separate {@link TaggingApiOnly} bean because SQS tags through its own {@code TagQueue}
 * action: a default-qualified handler would also serve the queue on {@code /tags/{arn}}, which
 * AWS does not define.
 */
@ApplicationScoped
@TaggingApiOnly
public class SqsTagHandler implements TagHandler {

    private final SqsService sqsService;

    @Inject
    public SqsTagHandler(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    @Override
    public String serviceKey() {
        return "sqs";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return sqsService.listQueueTags(queueUrl(region, arn), region);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        sqsService.tagQueue(queueUrl(region, arn), tags, region);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        sqsService.untagQueue(queueUrl(region, arn), tagKeys, region);
    }

    private String queueUrl(String region, String arn) {
        return sqsService.getQueueUrl(AwsArnUtils.parse(arn).resource(), region);
    }
}
