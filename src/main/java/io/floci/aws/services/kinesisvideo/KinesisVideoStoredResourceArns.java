package io.floci.aws.services.kinesisvideo;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IAM's lookups of what Kinesis Video Streams holds, for the {@code kinesisvideo} namespace: the ARN of the stream a
 * request names by its name, which ends with the creation time the request does not carry, and the tags of a stream.
 */
@ApplicationScoped
public class KinesisVideoStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "kinesisvideo";
    private static final String STREAM = "stream";

    private final KinesisVideoService kinesisVideo;
    private final RegionResolver regionResolver;

    @Inject
    public KinesisVideoStoredResourceArns(KinesisVideoService kinesisVideo, RegionResolver regionResolver) {
        this.kinesisVideo = kinesisVideo;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /**
     * The ARN of the caller's stream of the one name in {@code names} in the Region, as the handlers look it up, so a
     * request naming the stream by {@code StreamName} is checked on the stream's own ARN rather than on every
     * creation time of the name. None for another type, several names or a name the caller holds no stream under.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!STREAM.equals(type) || names.size() != 1) {
            return Optional.empty();
        }
        return kinesisVideo.storedStreamArn(region, regionResolver.getAccountId(), names.getFirst());
    }

    /**
     * The tags of the stream whose ARN is {@code arn}, in the request's account and the ARN's Region, where the
     * handlers read: an ARN of another account names none, and neither does the ARN of a deleted stream once another
     * is created with its name. A tag without a value reads as an empty one.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account) || parsed.region().isBlank()) {
            return Map.of();
        }
        return StoredResourceArns.withEmptyValues(kinesisVideo.storedStreamTags(parsed.region(), account, arn));
    }
}
