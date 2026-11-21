package io.floci.aws.services.kinesis;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** IAM's lookup of the tags Kinesis Data Streams holds on a stream or a consumer, for the {@code kinesis} namespace. */
@ApplicationScoped
public class KinesisStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "kinesis";
    private static final String STREAM = "stream/";
    private static final String CONSUMER = "/consumer/";

    private final KinesisService kinesis;
    private final RegionResolver regionResolver;

    @Inject
    public KinesisStoredResourceArns(KinesisService kinesis, RegionResolver regionResolver) {
        this.kinesis = kinesis;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names a stream by its name or ARN and a consumer by its ARN: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the stream or the consumer whose ARN is {@code arn}, in the request's account, where the tag
     * handlers read: an ARN of another account names none. A consumer's ARN, a stream's with {@code /consumer/} and
     * the consumer after it, reads the consumer held under that ARN; a stream's reads the stream of its name, matched
     * on the whole ARN it records. A tag without a value reads as an empty one. Tag calls give the resource a new map,
     * so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        String resource = parsed.resource();
        if (!parsed.accountId().equals(account) || !resource.startsWith(STREAM)) {
            return Map.of();
        }
        Map<String, String> tags = resource.contains(CONSUMER)
                ? kinesis.consumerTagsInAccount(account, arn)
                : kinesis.streamTagsInAccount(account, arn, resource.substring(STREAM.length()));
        return StoredResourceArns.withEmptyValues(tags);
    }
}
