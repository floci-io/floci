package io.floci.aws.services.sns;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** IAM's lookup of the tags SNS holds on a topic, for the {@code sns} namespace. */
@ApplicationScoped
public class SnsStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "sns";

    private final SnsService sns;
    private final RegionResolver regionResolver;

    @Inject
    public SnsStoredResourceArns(SnsService sns, RegionResolver regionResolver) {
        this.sns = sns;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names a topic by its ARN, or creates one by its name: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the topic whose ARN is {@code arn}, in the request's account, where the tag handlers read: an ARN of
     * another account names none. The topic is read in place under the whole ARN, in the ARN's Region. A tag without a
     * value reads as an empty one. Tag calls give the topic a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        String account = regionResolver.getAccountId();
        if (!AwsArnUtils.parse(arn).accountId().equals(account)) {
            return Map.of();
        }
        return StoredResourceArns.withEmptyValues(sns.topicTagsInAccount(account, arn));
    }
}
