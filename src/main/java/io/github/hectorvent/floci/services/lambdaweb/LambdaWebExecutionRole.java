package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Whether Lambda can assume the execution role of a revision. */
@ApplicationScoped
public class LambdaWebExecutionRole {

    private static final String LAMBDA = ServicePrincipals.of("lambda");

    private final IamService iamService;
    private final AssumeRolePolicyEvaluator trust;

    @Inject
    public LambdaWebExecutionRole(IamService iamService, AssumeRolePolicyEvaluator trust) {
        this.iamService = iamService;
        this.trust = trust;
    }

    /** AWS gives no aws:SourceAccount or aws:SourceArn here, so a trust policy that needs one refuses. */
    void requireAssumable(String region, String roleArn) {
        if (!assumable(region, roleArn, null, null)) {
            throw new AwsException("ValidationException", "The execution role " + roleArn
                    + " cannot be assumed by " + LAMBDA + ". Verify its trust policy."
                    + " If the role was just created, retry in a few seconds.", 400);
        }
    }

    /** An environment assumes the role with the function as aws:SourceArn and its account as aws:SourceAccount. */
    boolean assumableFor(String region, String functionArn, String roleArn) {
        return assumable(region, roleArn, functionArn, AwsArnUtils.parse(functionArn).accountId());
    }

    private boolean assumable(String region, String roleArn, String sourceArn, String sourceAccount) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        if (!arn.partition().equals(AwsRegions.partitionFor(region))) {
            return false;
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findRole(arn.accountId(), name)
                .filter(role -> IamService.roleArnMatches(roleArn, role))
                .filter(role -> trust.allowsService(role.getAssumeRolePolicyDocument(), LAMBDA, sourceArn,
                        sourceAccount))
                .isPresent();
    }
}
