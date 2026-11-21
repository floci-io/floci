package io.floci.aws.services.resourcegroups;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** IAM's lookup of the tags Resource Groups holds on a group, for the {@code resource-groups} namespace. */
@ApplicationScoped
public class ResourceGroupsStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "resource-groups";

    private final ResourceGroupsService groups;
    private final RegionResolver regionResolver;

    @Inject
    public ResourceGroupsStoredResourceArns(ResourceGroupsService groups, RegionResolver regionResolver) {
        this.groups = groups;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names a group by its name or its ARN: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the group whose ARN is {@code arn}, in the request's account, where the handlers read: an ARN of
     * another account names none. The group is read in place by the name its ARN gives, in the ARN's Region, and
     * matched on the whole ARN it records. A tag without a value reads as an empty one.
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
        return StoredResourceArns.withEmptyValues(groups.groupTagsInAccount(account, arn));
    }
}
