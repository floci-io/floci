package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.ArnSynthesizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Builds log-group ARNs for the estate-wide tagging scan.
 *
 * <p>{@link io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup} carries a
 * {@code tags} map but no {@code arn} field of its own — the ARN is computed on demand by
 * {@link CloudWatchLogsService#buildArn} wherever a handler needs to hand one to a caller. Without
 * a synthesizer, {@code TaggedResourceScanner} finds the tags on a scanned {@code LogGroup} but
 * cannot tie them to an ARN, so the group never surfaces from {@code GetResources} even though its
 * own {@code ListTagsForResource}/{@code ListTagsLogGroup} reads the same tags back correctly
 * (issue #98).
 *
 * <p>The group store ({@code cwlogs-groups.json}) is keyed {@code <region>::<logGroupName>},
 * which carries every segment the ARN needs; the account comes from the current request context
 * via {@code accountId}, matching every other synthesizer here.
 */
@ApplicationScoped
public class CloudWatchLogsArnSynthesizer implements ArnSynthesizer {

    private static final String GROUP_STORE = "cwlogs-groups.json";
    private static final String KEY_SEPARATOR = "::";

    @Override
    public Optional<String> synthesize(String serviceName, String storeName, String logicalKey,
                                       String accountId, JsonNode node) {
        if (!"cloudwatchlogs".equals(serviceName) || !GROUP_STORE.equals(storeName) || logicalKey == null) {
            return Optional.empty();
        }
        int separator = logicalKey.indexOf(KEY_SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        String region = logicalKey.substring(0, separator);
        String groupName = logicalKey.substring(separator + KEY_SEPARATOR.length());
        if (groupName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("arn:aws:logs:" + region + ":" + accountId + ":log-group:" + groupName);
    }
}
