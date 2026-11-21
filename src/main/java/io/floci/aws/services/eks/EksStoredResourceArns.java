package io.floci.aws.services.eks;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IAM's lookups of what EKS holds, for the {@code eks} namespace: the node group, add-on or Fargate profile a request
 * names by its cluster and its name, whose ARN ends in an id EKS minted, which the generated request-resource table
 * asks EKS to find, and the tags of what EKS holds.
 */
@ApplicationScoped
public class EksStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "eks";
    private static final Map<String, String> COLLECTIONS = Map.of(
            "nodegroup", "node-groups",
            "addon", "addons",
            "fargateprofile", "fargate-profiles");

    private final EksService eks;
    private final RegionResolver regionResolver;

    @Inject
    public EksStoredResourceArns(EksService eks, RegionResolver regionResolver) {
        this.eks = eks;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /**
     * The ARN EKS holds the node group, add-on or Fargate profile under that {@code names}, its cluster's name and its
     * own, name in {@code region}, found as its handlers find it. None for another type, which EKS does not hold, or
     * when it holds no such resource.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        String collection = COLLECTIONS.get(type);
        if (collection == null || names.size() != 2 || !region.equals(regionResolver.getRegion())) {
            return Optional.empty();
        }
        return eks.findResourceArn(names.get(0), collection, names.get(1))
                .filter(arn -> region.equals(AwsArnUtils.regionOrDefault(arn, null)));
    }

    /**
     * The tags of the cluster, node group, Fargate profile, add-on, Pod Identity association or access entry whose ARN
     * is {@code arn}, in the request's account, where the tag handlers read: an ARN of another account names none. The
     * resource is read in place and matched on the whole ARN it records. A tag without a value reads as an empty one.
     * Tag calls give the resource a new map one at a time, so the read is of one whole set. An ARN with a part left
     * open ({@code *}), as the table gives an access entry or a Pod Identity association a request names by no id,
     * names no resource EKS holds, so it is not looked up: finding an access entry by its ARN walks its cluster's
     * entries.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE) || arn.indexOf('*') >= 0) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account) || parsed.region().isBlank()) {
            return Map.of();
        }
        return StoredResourceArns.withEmptyValues(eks.tagsInAccount(account, arn));
    }
}
