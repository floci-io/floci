package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;

final class ElastiCacheTagResourceArn {

    record TagResourceRef(String type, String id, String resourceArn) {}

    private ElastiCacheTagResourceArn() {
    }

    static TagResourceRef parse(String resourceArn, String region, RegionResolver resolver) {
        return parse(resourceArn, region, resolver, null);
    }

    static TagResourceRef parse(String resourceArn, String region, RegionResolver resolver, String requiredType) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw invalid(resourceArn);
        }
        if (!"elasticache".equals(arn.service())
                || !resolver.partitionForRegion(region).equals(arn.partition())) {
            throw invalid(resourceArn);
        }
        int separator = arn.resource().indexOf(':');
        if (separator <= 0 || separator == arn.resource().length() - 1) {
            throw invalid(resourceArn);
        }
        String type = arn.resource().substring(0, separator);
        if (requiredType != null && !requiredType.equals(type)) {
            throw invalid(resourceArn);
        }
        if (!region.equals(arn.region()) || !resolver.getAccountId().equals(arn.accountId())) {
            throw new AwsException("InvalidParameterValue",
                    "The resource ARN does not belong to the requested Region or account: " + resourceArn, 400);
        }
        String id = arn.resource().substring(separator + 1);
        if (id.isBlank()) {
            throw invalid(resourceArn);
        }
        return new TagResourceRef(type, id, resourceArn);
    }

    static AwsException invalid(String resourceArn) {
        return new AwsException("InvalidARN", "Invalid resource ARN: " + resourceArn, 400);
    }
}
