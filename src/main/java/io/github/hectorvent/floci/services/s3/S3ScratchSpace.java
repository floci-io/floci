package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.Bucket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Internal S3 transfers retain separate object and prefix permissions. */
public enum S3ScratchSpace {
    SPECTRUM(S3Service.REDSHIFT_SPECTRUM_SCRATCH_BUCKET, S3Service.REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE,
            "spectrum-%s\\.csv", Set.of("s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload")),
    UNLOAD(S3Service.REDSHIFT_UNLOAD_STAGING_BUCKET, S3Service.REDSHIFT_UNLOAD_STAGING_TAG_VALUE,
            "unload/%s/", Set.of("s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListBucket"));

    private final String bucketName;
    private final String tagValue;
    private final Pattern resourcePattern;
    private final Set<String> actions;

    S3ScratchSpace(String bucketName, String tagValue, String keyPattern, Set<String> actions) {
        this.bucketName = bucketName;
        this.tagValue = tagValue;
        this.actions = actions;
        resourcePattern = Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX + ":s3:::" + Pattern.quote(bucketName)
                + "/" + keyPattern.formatted("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
    }

    public String bucketName() {
        return bucketName;
    }

    boolean matchesBucket(Bucket bucket) {
        return bucketName.equals(bucket.getName()) && bucket.getTags() != null
                && tagValue.equals(bucket.getTags().get(S3Service.INTERNAL_BUCKET_TAG_KEY));
    }

    /** Creation and tagging must finish before another transfer checks ownership. */
    public synchronized void ensureBucket(S3Service s3, String region) throws IOException {
        try {
            s3.createInternalBucket(bucketName, region);
        } catch (AwsException exception) {
            if (!"BucketAlreadyOwnedByYou".equals(exception.getErrorCode())) {
                throw exception;
            }
            if (!tagValue.equals(s3.getBucketTagging(bucketName).get(S3Service.INTERNAL_BUCKET_TAG_KEY))) {
                throw new IOException("Bucket \"" + bucketName + "\" already exists and is not Floci scratch space");
            }
            return;
        }
        s3.putBucketTagging(bucketName, Map.of(S3Service.INTERNAL_BUCKET_TAG_KEY, tagValue));
    }

    public boolean permits(String action, String registeredArn, List<String> resources, String listPrefix) {
        if (!actions.contains(action) || resources.isEmpty() || !resourcePattern.matcher(registeredArn).matches()) {
            return false;
        }
        if ("s3:ListBucket".equals(action)) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(registeredArn);
            String bucketArn = AwsArnUtils.Arn.global(arn.partition(), "s3", "", bucketName).toString();
            String allowedPrefix = arn.resource().substring(bucketName.length() + 1);
            return listPrefix != null && listPrefix.startsWith(allowedPrefix)
                    && resources.stream().allMatch(bucketArn::equals);
        }
        return resources.stream().allMatch(resource -> this == UNLOAD
                ? resource.startsWith(registeredArn) : resource.equals(registeredArn));
    }
}
