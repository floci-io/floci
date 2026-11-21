package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks CloudWatch Logs of the resources a request names: the caller's delivery of the id it was created
 * with, the delivery destinations the deliveries from a delivery source send to, which the Service Reference
 * checks a {@code DeleteDeliverySource} on, and the tags of a log group, a destination or a delivery source,
 * delivery destination or delivery.
 */
@ApplicationScoped
public class CloudWatchLogsStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "logs";
    private static final String LOG_GROUP = "log-group:";
    private static final String ALL_STREAMS = ":*";

    private final CloudWatchLogsService logs;
    private final CloudWatchLogsCrossAccountService destinations;
    private final CloudWatchLogsDeliveryService deliveries;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchLogsStoredResourceArns(CloudWatchLogsService logs, CloudWatchLogsCrossAccountService destinations,
                                            CloudWatchLogsDeliveryService deliveries, RegionResolver regionResolver) {
        this.logs = logs;
        this.destinations = destinations;
        this.deliveries = deliveries;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** The ARN of the caller's delivery of the id {@code names} gives, as the service stored it; empty for none. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"delivery".equals(type) || names.size() != 1) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(deliveries.getDelivery(names.getFirst(), region).getArn());
        } catch (AwsException notFound) {
            return Optional.empty();
        }
    }

    /** The delivery destinations the caller's deliveries from the delivery source a {@code name} names send to. */
    @Override
    public List<String> through(String member, String value, String region) {
        return "name".equals(member) ? deliveries.destinationArnsFrom(value, region) : List.of();
    }

    /**
     * The tags of the log group, destination, delivery source, delivery destination or delivery whose ARN is
     * {@code arn}, in the request's account, where the tag handlers read: an ARN of another account names none, and
     * neither does a log stream's, Floci holding no tags on a stream. A log group is read in place by the name its ARN
     * gives, a trailing {@code :*} dropped as its tag handlers drop it, in the ARN's Region. A resource Floci does not
     * hold has none; any other failure of the lookup is thrown, so it cannot pass for a resource without tags. A tag
     * without a value reads as an empty one.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        String region = parsed.region();
        if (!parsed.accountId().equals(account) || region.isBlank()) {
            return Map.of();
        }
        String resource = parsed.resource();
        if (resource.startsWith(LOG_GROUP)) {
            String name = resource.substring(LOG_GROUP.length());
            name = name.endsWith(ALL_STREAMS) ? name.substring(0, name.length() - ALL_STREAMS.length()) : name;
            return name.isEmpty() || name.indexOf(':') >= 0 ? Map.of()
                    : StoredResourceArns.withEmptyValues(logs.groupTagsInAccount(account, region, name));
        }
        try {
            if (CloudWatchLogsCrossAccountService.isCrossAccountDestinationArn(arn)) {
                return StoredResourceArns.withEmptyValues(destinations.listDestinationTags(arn, region));
            }
            if (deliveries.isDeliveryArn(arn, region)) {
                return StoredResourceArns.withEmptyValues(deliveries.listTags(arn, region));
            }
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
        }
        return Map.of();
    }
}
