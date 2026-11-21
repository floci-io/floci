package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsArnUtils;

/**
 * The ARNs an IAM policy names an S3 bucket and an object by: {@code arn:<partition>:s3:::<bucket>} and
 * {@code arn:<partition>:s3:::<bucket>/<key>}, with no Region and no account. A direct request, a presigned
 * URL, a Redshift COPY and a Step Functions task all check an S3 action on these, so they are minted here once.
 * The key is the object's key as stored, slashes and all; nothing is encoded.
 */
public final class S3ResourceArns {

    private S3ResourceArns() {}

    public static String bucket(String partition, String bucketName) {
        return AwsArnUtils.Arn.global(partition, "s3", "", bucketName).toString();
    }

    public static String object(String partition, String bucketName, String key) {
        return bucket(partition, bucketName) + "/" + key;
    }
}
