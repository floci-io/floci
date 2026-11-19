package io.floci.aws.services.dsql;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** IAM's lookup of the tags Aurora DSQL holds on a cluster, for the {@code dsql} namespace. */
@ApplicationScoped
public class DsqlStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "dsql";
    private static final String CLUSTER = "cluster/";

    private final DsqlService dsql;
    private final RegionResolver regionResolver;

    @Inject
    public DsqlStoredResourceArns(DsqlService dsql, RegionResolver regionResolver) {
        this.dsql = dsql;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names a cluster by the identifier in its path, or by its ARN: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the cluster whose ARN is {@code arn}, in the request's account, where the tag handlers read: an ARN
     * of another account names none, and neither does a stream's, Floci holding no stream of its own. The cluster is
     * read in place by its identifier, in the ARN's Region, and matched on the whole ARN it records. A tag without a
     * value reads as an empty one. Tag calls give the cluster a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        String resource = parsed.resource();
        if (!parsed.accountId().equals(account) || !resource.startsWith(CLUSTER)
                || resource.indexOf('/', CLUSTER.length()) >= 0) {
            return Map.of();
        }
        return StoredResourceArns.withEmptyValues(
                dsql.clusterTagsInAccount(account, arn, resource.substring(CLUSTER.length())));
    }
}
