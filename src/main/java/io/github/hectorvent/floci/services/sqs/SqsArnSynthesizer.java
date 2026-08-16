package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.ArnSynthesizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Builds queue ARNs for the estate-wide tagging scan.
 *
 * <p>{@code Queue} stores a URL rather than an ARN, and reports {@code QueueArn} as a computed
 * attribute. The queue store is keyed {@code <region>::<account>/<queueName>}, which carries every
 * segment an SQS ARN needs; the account and name are taken from the model where it has them, so a
 * queue whose stored URL was rewritten for a different hostname still yields the same ARN.
 */
@ApplicationScoped
public class SqsArnSynthesizer implements ArnSynthesizer {

    private static final String QUEUE_STORE = "sqs-queues.json";
    private static final String KEY_SEPARATOR = "::";

    @Override
    public Optional<String> synthesize(String serviceName, String storeName, String logicalKey,
                                       String accountId, JsonNode node) {
        if (!"sqs".equals(serviceName) || !QUEUE_STORE.equals(storeName) || logicalKey == null) {
            return Optional.empty();
        }
        int separator = logicalKey.indexOf(KEY_SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        String region = logicalKey.substring(0, separator);
        String queuePath = logicalKey.substring(separator + KEY_SEPARATOR.length());

        String name = text(node, "queueName");
        if (name == null) {
            int slash = queuePath.lastIndexOf('/');
            name = slash < 0 ? queuePath : queuePath.substring(slash + 1);
        }
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String account = text(node, "accountId");
        if (account == null) {
            int slash = queuePath.indexOf('/');
            account = slash < 0 ? accountId : queuePath.substring(0, slash);
        }
        return Optional.of("arn:aws:sqs:" + region + ":" + account + ":" + name);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isEmpty() ? value.asText() : null;
    }
}
