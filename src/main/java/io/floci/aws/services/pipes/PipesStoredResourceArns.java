package io.floci.aws.services.pipes;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** IAM's lookup of the tags EventBridge Pipes holds on a pipe, for the {@code pipes} namespace. */
@ApplicationScoped
public class PipesStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "pipes";
    private static final String PIPE = "pipe/";

    private final PipesService pipes;
    private final RegionResolver regionResolver;

    @Inject
    public PipesStoredResourceArns(PipesService pipes, RegionResolver regionResolver) {
        this.pipes = pipes;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names a pipe by the name in its path, or by its ARN: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the pipe whose ARN is {@code arn}, in the request's account, where the tag handlers read: an ARN of
     * another account names none. The pipe is read in place by the name its ARN ends in, in the ARN's Region, and
     * matched on the whole ARN it records. A tag without a value reads as an empty one. Tag calls give the pipe a new
     * map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account) || !parsed.resource().startsWith(PIPE)) {
            return Map.of();
        }
        return StoredResourceArns.withEmptyValues(
                pipes.pipeTagsInAccount(account, arn, parsed.resource().substring(PIPE.length())));
    }
}
