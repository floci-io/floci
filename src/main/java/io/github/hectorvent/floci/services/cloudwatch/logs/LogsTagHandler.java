package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.TaggingApiOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Lets Resource Groups Tagging API writes reach a log group's own tags.
 *
 * <p>A separate {@link TaggingApiOnly} bean because CloudWatch Logs tags through its own JSON
 * {@code TagResource} action: a default-qualified handler would also serve the log group on
 * {@code /tags/{arn}}, which AWS does not define.
 */
@ApplicationScoped
@TaggingApiOnly
public class LogsTagHandler implements TagHandler {

    private final CloudWatchLogsService logsService;

    @Inject
    public LogsTagHandler(CloudWatchLogsService logsService) {
        this.logsService = logsService;
    }

    @Override
    public String serviceKey() {
        return "logs";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return logsService.listTagsLogGroup(logGroupName(arn), region);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        logsService.tagLogGroup(logGroupName(arn), tags, region);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        logsService.untagLogGroup(logGroupName(arn), tagKeys, region);
    }

    // AWS rejects the :* form of a log group ARN here, as Logs' own ListTagsForResource does.
    private static String logGroupName(String arn) {
        if (arn.endsWith(":*")) {
            throw new AwsException("ValidationException", "Invalid resourceArn", 400);
        }
        return CloudWatchLogsHandler.extractLogGroupNameFromArn(arn);
    }
}
