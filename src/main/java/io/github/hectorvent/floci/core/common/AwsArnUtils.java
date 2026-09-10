package io.github.hectorvent.floci.core.common;

public final class AwsArnUtils {

    private AwsArnUtils() {}

    /**
     * Parsed representation of an AWS ARN.
     *
     * Fields map directly to the six colon-delimited segments:
     * {@code arn:<partition>:<service>:<region>:<accountId>:<resource>}
     *
     * {@code region} and {@code accountId} are empty strings (not null) when the ARN
     * omits them (e.g. {@code arn:aws:s3:::my-bucket}).
     *
     * The {@code resource} field is left unparsed — its internal structure is
     * service-specific and callers are responsible for splitting it as needed.
     */
    public record Arn(String partition, String service, String region, String accountId, String resource) {

        /**
         * Factory for AWS ARNs, deriving the partition from the region.
         * Produces: {@code arn:<partition>:<service>:<region>:<accountId>:<resource>}
         *
         * <p>A resource in {@code cn-north-1} gets {@code aws-cn}, one in {@code us-gov-west-1}
         * gets {@code aws-us-gov}, and everything else gets {@code aws}. See
         * {@link AwsRegions#partitionFor}.
         *
         * <p>Global services pass an empty region and therefore keep {@code aws}. That is a known
         * gap rather than a decision: an IAM ARN in a GovCloud deployment really is
         * {@code arn:aws-us-gov:iam::…}, but nothing in the region argument can say so. Those call
         * sites need a partition from the deployment, not from the resource, and they are left
         * alone until there is one.
         */
        public static Arn of(String service, String region, String accountId, String resource) {
            return new Arn(AwsRegions.partitionFor(region), service, region, accountId, resource);
        }

        @Override
        public String toString() {
            return "arn:" + partition + ":" + service + ":" + region + ":" + accountId + ":" + resource;
        }
    }

    /**
     * Parses an ARN string into an {@link Arn} record.
     *
     * @throws IllegalArgumentException if the string is null, blank, does not start with {@code arn:},
     *                                  or has fewer than six colon-delimited segments
     */
    public static Arn parse(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new IllegalArgumentException("ARN must not be null or blank");
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 6 || !"arn".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid ARN: " + arn);
        }
        return new Arn(parts[1], parts[2], parts[3], parts[4], parts[5]);
    }

    /**
     * Returns the region from an ARN, or {@code defaultRegion} when the ARN is null,
     * unparseable, or has an empty region field.
     */
    public static String regionOrDefault(String arn, String defaultRegion) {
        if (arn == null) {
            return defaultRegion;
        }
        try {
            String region = parse(arn).region();
            return region.isEmpty() ? defaultRegion : region;
        } catch (IllegalArgumentException e) {
            return defaultRegion;
        }
    }

    /**
     * Returns the account ID from an ARN, or {@code defaultAccount} when the ARN is null,
     * unparseable, or has an empty account field.
     */
    public static String accountOrDefault(String arn, String defaultAccount) {
        if (arn == null) {
            return defaultAccount;
        }
        try {
            String account = parse(arn).accountId();
            return account.isEmpty() ? defaultAccount : account;
        } catch (IllegalArgumentException e) {
            return defaultAccount;
        }
    }

    /**
     * Converts an SQS ARN to a queue URL using the given base URL.
     * Example: arn:aws:sqs:us-east-1:000000000000:my-queue → http://localhost:4566/000000000000/my-queue
     */
    public static String arnToQueueUrl(String arn, String baseUrl) {
        Arn parsed = parse(arn);
        return baseUrl + "/" + parsed.accountId() + "/" + parsed.resource();
    }
}
