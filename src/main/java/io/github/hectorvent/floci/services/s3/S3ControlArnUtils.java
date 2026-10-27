package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsArnUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The resource ARN an S3 Control tagging route names in its path, read the one way every reader
 * of it shares: decoded once, because the Go SDK v2, and so Terraform's provider, percent-encodes
 * the ARN's colons and slashes while the Java SDK sends them literally, then parsed with
 * {@link AwsArnUtils#parse}. Two documented forms name a bucket (the S3 Control API reference,
 * ListTagsForResource): the regional {@code arn:<partition>:s3:<region>:<account>:bucket/<name>}
 * the operation's own request pattern and the AWS CLI examples use, and the global
 * {@code arn:<partition>:s3:::<name>}. The regional form's account and region are the ones the
 * handler, the policy lookup and the IAM check all act on, so a request naming another account's
 * bucket acts on that account's bucket and never on the caller's same-named one.
 */
public final class S3ControlArnUtils {

    /** The regional form's resource prefix: {@code bucket/<name>}. */
    private static final String BUCKET_RESOURCE_PREFIX = "bucket/";

    private S3ControlArnUtils() {}

    /**
     * The tagging route's ARN, decoded as its handler decodes it and parsed once. Empty when the
     * value is null, blank or not an s3 ARN; {@link IllegalArgumentException} when its
     * percent-encoding is malformed.
     */
    public static Optional<AwsArnUtils.Arn> parse(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            return Optional.empty();
        }
        String decoded = URLDecoder.decode(resourceArn, StandardCharsets.UTF_8);
        return AwsArnUtils.isArnFor(decoded, "s3") ? Optional.of(AwsArnUtils.parse(decoded)) : Optional.empty();
    }

    /**
     * Whether {@code arn} is the regional form: the only S3 ARN that fills its region and carries
     * its bucket after {@code bucket/}. No S3 data-plane ARN fills the region, so this reading can
     * never collide with the data-plane one of a bucket actually named {@code bucket}.
     */
    public static boolean regionalBucketForm(AwsArnUtils.Arn arn) {
        return !arn.region().isEmpty() && arn.resource().startsWith(BUCKET_RESOURCE_PREFIX);
    }

    /**
     * Whether the ARN names a bucket the tagging routes act on: the regional form, or the global
     * one whose whole resource is the bucket's name. Anything else, an access point ARN included,
     * names a resource these routes do not serve.
     */
    public static boolean namesBucket(AwsArnUtils.Arn arn) {
        return regionalBucketForm(arn)
                || (arn.region().isEmpty() && arn.accountId().isEmpty()
                && !arn.resource().isEmpty() && arn.resource().indexOf('/') < 0);
    }

    /** The bucket the ARN names: after {@code bucket/} in the regional form, the whole resource in the global one. */
    public static String bucketName(AwsArnUtils.Arn arn) {
        return regionalBucketForm(arn) ? arn.resource().substring(BUCKET_RESOURCE_PREFIX.length()) : arn.resource();
    }
}
