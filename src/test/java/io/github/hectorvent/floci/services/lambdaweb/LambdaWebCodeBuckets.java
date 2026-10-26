package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.s3.S3Service;

/** Code buckets for tests that create Web Function revisions. */
public final class LambdaWebCodeBuckets {

    private static final String LAMBDA_READ = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"lambda.amazonaws.com"},"Action":["s3:GetObject","s3:GetObjectVersion"],
              "Resource":"arn:aws:s3:::%s/*"}]}""";

    private LambdaWebCodeBuckets() {
    }

    /** Creates a versioned bucket that lets Lambda read the code, as AWS requires. */
    public static void create(S3Service s3Service, String bucket) {
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putBucketVersioning(bucket, "Enabled");
        allowLambda(s3Service, bucket);
    }

    public static void allowLambda(S3Service s3Service, String bucket) {
        s3Service.putBucketPolicy(bucket, LAMBDA_READ.formatted(bucket));
    }
}
