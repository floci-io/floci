package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Supplies a DSQL cluster's resource-based policy, when it has one, to IAM policy evaluation, with
 * the account that owns the cluster: the cluster endpoint evaluates it on connect, as DSQL does
 * (measured).
 */
@ApplicationScoped
public class DsqlResourcePolicyProvider implements ResourcePolicyProvider {

    private final DsqlService dsqlService;

    @Inject
    public DsqlResourcePolicyProvider(DsqlService dsqlService) {
        this.dsqlService = dsqlService;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        return dsqlService.clusterOfArn(resourceArn)
                .filter(cluster -> cluster.getPolicy() != null)
                .map(cluster -> List.of(new ResourcePolicy(cluster.getPolicy(),
                        AwsArnUtils.parse(resourceArn).accountId())))
                .orElse(List.of());
    }
}
