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
        AwsArnUtils.Arn arn = s3Arn(resourceArn);
        String bucketName = arn == null ? null : extractBucketName(arn);
        if (bucketName == null || bucketName.isEmpty() || "*".equals(bucketName)) {
            return List.of();
        }
        Optional<S3Service.BucketPolicyInfo> policyInfo = S3ControlArnUtils.regionalBucketForm(arn)
                ? s3Service.findBucketPolicyInfoForAccount(arn.accountId(), bucketName)
                : s3Service.findBucketPolicyInfo(bucketName);
        return policyInfo.map(info -> List.of(new ResourcePolicy(info.policy(), info.ownerAccountId())))
                .orElseGet(List::of);
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String action, String resourceArn) {
        List<ResourcePolicy> policies = getResourcePolicies(credentialScope, resourceArn);
        if ("s3:CreateBucket".equals(action) && !policies.isEmpty()
                && !s3Service.ownsBucket(extractBucketName(resourceArn))) {
            return List.of();
        }
        return policies;
    }

    @Override
    public String resolveResourceArn(String credentialScope, String resourceArn) {
        if (!"s3".equalsIgnoreCase(credentialScope) || resourceArn == null) {
            return resourceArn;
        }
        AwsArnUtils.Arn arn = s3Arn(resourceArn);
        if (arn == null || S3ControlArnUtils.regionalBucketForm(arn)) {
            return resourceArn;
        }
        String bucketName = extractBucketName(arn);
        if (bucketName.isEmpty() || "*".equals(bucketName)) {
            return resourceArn;
        }
        return AwsArnUtils.Arn.global(s3Service.bucketPartition(bucketName), "s3", "", arn.resource()).toString();
    }

    /**
     * The request's resource, parsed without decoding: every ARN reaching here is already in its
     * decoded form, the data plane's from the request path and a control tagging one re-minted
     * by the rule that read it off the wire, so decoding again would rewrite an object key like
     * {@code a+b.txt} before IAM checks it. Null when it is not an s3 ARN, which leaves the
     * request its identity policy alone: no bucket policy applies, as when the bucket has none.
     */
    private static AwsArnUtils.Arn s3Arn(String resourceArn) {
        return AwsArnUtils.isArnFor(resourceArn, "s3") ? AwsArnUtils.parse(resourceArn) : null;
    }

    /**
     * The bucket of an S3 ARN in any partition. Keyed on a literal {@code arn:aws:} this found
     * no policy for a China or GovCloud bucket, which silently changed the access decision.
     */
    static String extractBucketName(String resourceArn) {
        AwsArnUtils.Arn arn = s3Arn(resourceArn);
        return arn == null ? null : extractBucketName(arn);
    }

    /**
     * The bucket of a parsed S3 ARN: the one after {@code bucket/} in the regional form a
     * control tagging route names, so a policy is looked up from the bucket the handler acts on
     * and not from a bucket named {@code bucket} with the real name as its key, and the bucket
     * before the first slash in every data-plane form.
     */
    static String extractBucketName(AwsArnUtils.Arn arn) {
        if (S3ControlArnUtils.regionalBucketForm(arn)) {
            return S3ControlArnUtils.bucketName(arn);
        }
        int slash = arn.resource().indexOf('/');
        return slash < 0 ? arn.resource() : arn.resource().substring(0, slash);
    }
}
