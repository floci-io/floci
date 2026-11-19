package io.floci.aws.services.sqs;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IAM's lookups of what SQS holds, for the {@code sqs} namespace: the queue a request names by its URL, which the
 * generated request-resource table asks SQS to find, and the tags of a queue.
 */
@ApplicationScoped
public class SqsStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "sqs";
    private static final String QUEUE = "queue";

    private final SqsService sqs;
    private final RegionResolver regionResolver;

    @Inject
    public SqsStoredResourceArns(SqsService sqs, RegionResolver regionResolver) {
        this.sqs = sqs;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /**
     * The ARN of the queue the one URL in {@code names} reaches from {@code region}, found as the handlers find it: in
     * the account the URL's path names and held in the Region. None for another type, several URLs or a URL naming no
     * queue.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!QUEUE.equals(type) || names.size() != 1) {
            return Optional.empty();
        }
        return sqs.heldQueueArn(names.getFirst(), region);
    }

    /**
     * The tags of the queue whose ARN is {@code arn}, in the request's account, where the tag handlers read: an ARN of
     * another account names none, and neither does one other than the ARN {@link #find} gives the queue. The queue is
     * read in place by the Region and the name the ARN gives. A tag without
     * a value reads as an empty one. Tag calls give the queue a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account) || parsed.region().isBlank()
                || !arn.equals(AwsArnUtils.Arn.of(NAMESPACE, parsed.region(), account, parsed.resource()).toString())) {
            return Map.of();
        }
        return StoredResourceArns.withEmptyValues(sqs.queueTagsInAccount(account, parsed.region(), parsed.resource()));
    }
}
