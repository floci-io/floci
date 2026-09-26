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

    /**
     * The ARN the resource is actually named by, which policies are matched against. A request's
     * resource ARN is built in the request's partition; a provider whose resources outlive that
     * (an S3 bucket reachable from any partition) returns it in the partition the resource lives
     * in. The default is the ARN unchanged.
     *
     * @param credentialScope the signing credential scope, e.g. "s3"
     * @param resourceArn the resource ARN built from the request
     * @return the resource ARN policies should see
     */
    default String resolveResourceArn(String credentialScope, String resourceArn) {
        return resourceArn;
    }
}
