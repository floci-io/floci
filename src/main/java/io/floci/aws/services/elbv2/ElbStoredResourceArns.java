package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.elb.ElbClassicService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IAM's lookup of the tags Elastic Load Balancing holds, for the {@code elasticloadbalancing} namespace its two
 * versions share: a load balancer, target group, listener or rule of version 2 by the ARN version 2 keeps its tags
 * under, and a Classic Load Balancer, {@code loadbalancer/<name>}, by its name.
 */
@ApplicationScoped
public class ElbStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "elasticloadbalancing";
    private static final String CLASSIC = "loadbalancer/";

    private final ElbV2Service elbV2;
    private final ElbClassicService classic;
    private final RegionResolver regionResolver;

    @Inject
    public ElbStoredResourceArns(ElbV2Service elbV2, ElbClassicService classic, RegionResolver regionResolver) {
        this.elbV2 = elbV2;
        this.classic = classic;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names these resources by their ARNs, or a Classic Load Balancer by its name: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the resource whose ARN is {@code arn}, in the request's account, where the tag handlers read: an
     * ARN of another account names none. A Classic Load Balancer's ARN, the only one whose resource is
     * {@code loadbalancer/} and a name alone, reads the load balancer of that name in the ARN's Region when the ARN is
     * the one Floci gives it, so one without a Region reads none; any other reads the tags version 2 keeps under that
     * ARN. A tag without a value reads as
     * an empty one. Tag calls give the resource a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account)) {
            return Map.of();
        }
        String resource = parsed.resource();
        Map<String, String> tags;
        if (resource.startsWith(CLASSIC) && resource.indexOf('/', CLASSIC.length()) < 0) {
            tags = !parsed.region().isBlank()
                    && arn.equals(AwsArnUtils.Arn.of(NAMESPACE, parsed.region(), account, resource).toString())
                    ? classic.tagsInAccount(account, parsed.region(), resource.substring(CLASSIC.length()))
                    : Map.of();
        } else {
            tags = elbV2.tagsInAccount(account, arn);
        }
        Map<String, String> copy = new LinkedHashMap<>();
        tags.forEach((key, value) -> copy.put(key, value == null ? "" : value));
        return copy;
    }
}
