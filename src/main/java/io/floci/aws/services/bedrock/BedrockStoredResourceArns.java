package io.floci.aws.services.bedrock;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IAM's lookup of the tags Amazon Bedrock holds on a guardrail or an application inference profile, for the
 * {@code bedrock} namespace.
 */
@ApplicationScoped
public class BedrockStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "bedrock";

    private final BedrockService bedrock;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockStoredResourceArns(BedrockService bedrock, RegionResolver regionResolver) {
        this.bedrock = bedrock;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names what it acts on by its id or its ARN: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the guardrail or application inference profile whose ARN is {@code arn}, in the request's account,
     * where the tag handlers read: an ARN of another account names none, and neither does one of a type Floci holds
     * no tags on. The resource is read in place and matched on the whole ARN it records.
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
        return StoredResourceArns.withEmptyValues(bedrock.tagsInAccount(account, arn));
    }
}
