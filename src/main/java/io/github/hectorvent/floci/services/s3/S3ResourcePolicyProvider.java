package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies S3 bucket policies to the IAM policy evaluation pipeline.
 */
@ApplicationScoped
public class S3ResourcePolicyProvider implements ResourcePolicyProvider {

    private final S3Service s3Service;

    @Inject
    public S3ResourcePolicyProvider(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!"s3".equalsIgnoreCase(credentialScope) || resourceArn == null) {
            return List.of();
        }
        String bucketName = extractBucketName(resourceArn);
        if (bucketName == null || bucketName.isEmpty() || "*".equals(bucketName)) {
            return List.of();
        }
        Optional<S3Service.BucketPolicyInfo> policyInfo = s3Service.findBucketPolicyInfo(bucketName);
        return policyInfo.map(info -> List.of(new ResourcePolicy(info.policy(), info.ownerAccountId())))
                .orElseGet(List::of);
    }

    @Override
    public String resolveResourceArn(String credentialScope, String resourceArn) {
        if (!"s3".equalsIgnoreCase(credentialScope) || resourceArn == null) {
            return resourceArn;
        }
        String bucketName = extractBucketName(resourceArn);
        if (bucketName == null || bucketName.isEmpty() || "*".equals(bucketName)) {
            return resourceArn;
        }
        String resource = AwsArnUtils.resourceIfArnFor(resourceArn, "s3").orElseThrow();
        return AwsArnUtils.Arn.global(s3Service.bucketPartition(bucketName), "s3", "", resource).toString();
    }

    /**
     * The bucket of an S3 ARN in any partition. Keyed on a literal {@code arn:aws:} this found
     * no policy for a China or GovCloud bucket, which silently changed the access decision.
     */
    static String extractBucketName(String resourceArn) {
        String tail = AwsArnUtils.resourceIfArnFor(resourceArn, "s3").orElse(null);
        if (tail == null) {
            return null;
        }
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }
}
