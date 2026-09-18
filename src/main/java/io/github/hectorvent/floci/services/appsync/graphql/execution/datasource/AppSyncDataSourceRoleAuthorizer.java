package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.execution.FieldError;
import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/** Authorizes an AppSync backing-resource operation as the data source's service role. */
@ApplicationScoped
public class AppSyncDataSourceRoleAuthorizer {

    private static final String APPSYNC_SERVICE_PRINCIPAL = "appsync.amazonaws.com";

    private final IamService iamService;
    private final IamPolicyEvaluator policyEvaluator;
    private final AssumeRolePolicyEvaluator trustPolicyEvaluator;

    @Inject
    public AppSyncDataSourceRoleAuthorizer(IamService iamService,
                                           IamPolicyEvaluator policyEvaluator,
                                           AssumeRolePolicyEvaluator trustPolicyEvaluator) {
        this.iamService = iamService;
        this.policyEvaluator = policyEvaluator;
        this.trustPolicyEvaluator = trustPolicyEvaluator;
    }

    public Optional<FieldError> authorizeDynamoDb(
            DataSource dataSource,
            String action,
            String tableName,
            String region,
            GraphQlRequestContext context
    ) {
        String roleArn = dataSource.getServiceRoleArn();
        if (roleArn == null || roleArn.isBlank()) {
            return denied("Data source " + dataSource.getName() + " has no serviceRoleArn configured");
        }

        AwsArnUtils.Arn parsedRole;
        try {
            parsedRole = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            return denied("Unable to assume role " + roleArn);
        }
        if (!"iam".equals(parsedRole.service()) || !parsedRole.resource().startsWith("role/")) {
            return denied("Unable to assume role " + roleArn);
        }

        String roleName = parsedRole.resource().substring(parsedRole.resource().lastIndexOf('/') + 1);
        String roleAccount = parsedRole.accountId().isBlank() ? context.accountId() : parsedRole.accountId();
        Optional<IamRole> role = iamService.findRole(roleAccount, roleName);
        if (role.isEmpty() || !trustPolicyEvaluator.allowsService(
                role.get().getAssumeRolePolicyDocument(), APPSYNC_SERVICE_PRINCIPAL)) {
            return denied("Unable to assume role " + roleArn);
        }

        CallerContext roleContext;
        try {
            roleContext = iamService.resolvePrincipalContext(roleArn);
        } catch (AwsException e) {
            return denied("Unable to assume role " + roleArn);
        }
        String resource = AwsArnUtils.Arn.of(
                "dynamodb", region, context.accountId(), "table/" + tableName).toString();
        if (policyEvaluator.evaluate(roleContext, null, action, resource, null)
                == IamPolicyEvaluator.Decision.DENY) {
            return denied("Role " + roleArn + " is not authorized to perform "
                    + action + " on resource " + resource);
        }
        return Optional.empty();
    }

    private static Optional<FieldError> denied(String message) {
        return Optional.of(FieldError.of("DynamoDB:AccessDeniedException", message));
    }
}
