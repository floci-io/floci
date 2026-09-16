package io.github.hectorvent.floci.services.iam;

import java.util.List;

/**
 * Supplies the resource-based policy documents that apply to a given (credentialScope, resourceArn).
 *
 * <p>Implemented by services that support resource-based policies (such as S3 for bucket policies);
 * consumed lazily by {@code IamEnforcementFilter} via {@code Instance<ResourcePolicyProvider>}
 * so IAM never depends on resource-owning services directly.</p>
 */
public interface ResourcePolicyProvider {

    record ResourcePolicy(String policyDocument, String ownerAccountId) {}

    /**
     * Returns the resource-based policies that apply to the specified resource,
     * or an empty list if none apply.
     *
     * @param credentialScope the signing credential scope, e.g. "s3"
     * @param resourceArn the target resource ARN, e.g. "arn:aws:s3:::my-bucket/key"
     * @return policy documents and owning account applying to the resource, or an empty list
     */
    List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn);
}
