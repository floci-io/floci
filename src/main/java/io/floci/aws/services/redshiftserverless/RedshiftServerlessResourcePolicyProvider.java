package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies the resource policy of a Redshift Serverless snapshot to IAM policy evaluation, together with the
 * account that owns the snapshot: the one in its ARN, also when the snapshot does not exist or has no policy,
 * so a call on another account's snapshot is judged as cross-account.
 */
@ApplicationScoped
public class RedshiftServerlessResourcePolicyProvider implements ResourcePolicyProvider {

    private static final String SNAPSHOT_PREFIX = "snapshot/";

    private final RedshiftServerlessService serverless;
    private final RedshiftServerlessResourcePolicyService policies;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftServerlessResourcePolicyProvider(RedshiftServerlessService serverless,
                                                    RedshiftServerlessResourcePolicyService policies,
                                                    ObjectMapper objectMapper) {
        this.serverless = serverless;
        this.policies = policies;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resourceArn, "redshift-serverless");
        if (parsed.isEmpty()) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parsed.get();
        String target = arn.resource();
        // An ARN with an id open (a create, or a snapshot that does not exist) names no one snapshot.
        if (!target.startsWith(SNAPSHOT_PREFIX) || target.length() == SNAPSHOT_PREFIX.length()
                || target.contains("*")) {
            return List.of();
        }
        String policy = serverless.findSnapshotOfAccount(arn.accountId(), target.substring(SNAPSHOT_PREFIX.length()),
                        arn.region())
                .flatMap(snapshot -> policies.policyOfSnapshot(arn.accountId(), snapshot, arn.region()))
                .map(document -> withResource(document, resourceArn))
                .orElse(null);
        return List.of(new ResourcePolicy(policy, arn.accountId()));
    }

    /**
     * The policy with the snapshot's ARN as the {@code Resource} of each statement that names none. A resource
     * policy is attached to its one resource, and AWS's own {@code PutResourcePolicy} example leaves {@code Resource}
     * out, which the evaluator would read as a statement that matches nothing.
     */
    String withResource(String document, String resourceArn) {
        return ResourcePolicyProvider.withResource(objectMapper, document, resourceArn);
    }
}
