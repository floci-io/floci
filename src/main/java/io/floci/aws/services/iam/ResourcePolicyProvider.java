package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsArnUtils;

import java.util.List;
import java.util.Optional;

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
     * Parses a regional, account-owned ARN for the service. Resource-name constraints remain
     * with the provider, since a queue, topic and event bus have different name shapes.
     */
    static Optional<AwsArnUtils.Arn> regionalArn(String resourceArn, String service) {
        if (!AwsArnUtils.isArnFor(resourceArn, service)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if (arn.region().isBlank() || !AccountResolver.isAccountId(arn.accountId())) {
            return Optional.empty();
        }
        return Optional.of(arn);
    }

    /**
     * The policy with {@code resourceArn} as the {@code Resource} of each statement that names none. A resource
     * policy is attached to its one resource, and AWS's own examples leave {@code Resource} out (a Redshift
     * Serverless {@code PutResourcePolicy}'s; a CodeArtifact repository policy's, "Because the resource is
     * implied"), which the evaluator would read as a statement that matches nothing. The document as it is when it
     * is no JSON the mapper reads, which a policy stored as a JSON object is not expected to be.
     */
    static String withResource(ObjectMapper objectMapper, String document, String resourceArn) {
        try {
            JsonNode root = objectMapper.readTree(document);
            JsonNode statements = root.path("Statement");
            boolean changed = false;
            for (JsonNode statement : statements.isArray() ? statements : List.of(statements)) {
                if (statement instanceof ObjectNode object && !object.has("Resource") && !object.has("NotResource")) {
                    object.put("Resource", resourceArn);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(root) : document;
        } catch (JsonProcessingException e) {
            return document;
        }
    }

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
     * Policies applying to an action. A service may exclude operations that do not act on
     * an existing resource.
     */
    default List<ResourcePolicy> getResourcePolicies(String credentialScope, String action, String resourceArn) {
        return getResourcePolicies(credentialScope, resourceArn);
    }

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
