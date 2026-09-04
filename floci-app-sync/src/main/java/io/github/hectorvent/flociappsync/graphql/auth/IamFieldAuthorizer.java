package io.github.hectorvent.flociappsync.graphql.auth;

import io.github.hectorvent.flociappsync.iam.IamPolicyEvaluator;
import io.github.hectorvent.flociappsync.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Field-level IAM authorization for AppSync resolvers. Replaces the narrow slice of
 * Floci's {@code services.appsync.graphql.auth.IamAuthValidator} that {@code AuthorizationDataFetcher}
 * needs ({@code isFieldDenied}/{@code fieldArn}) — adapted because this service doesn't own
 * IAM state (users/roles/policies): the caller's {@link CallerContext} is resolved by Floci
 * and passed in per-request via {@link AppSyncAuthContext#callerContext()} instead of being
 * looked up here from an injected {@code IamService}.
 */
@ApplicationScoped
public class IamFieldAuthorizer {

    private final IamPolicyEvaluator iamPolicyEvaluator;

    @Inject
    public IamFieldAuthorizer(IamPolicyEvaluator iamPolicyEvaluator) {
        this.iamPolicyEvaluator = iamPolicyEvaluator;
    }

    public boolean isFieldDenied(CallerContext callerContext, String accessKeyId, String fieldArn) {
        if (accessKeyId == null || isEmulatorAllow(accessKeyId) || callerContext == null) {
            return false;
        }
        return iamPolicyEvaluator.evaluate(callerContext, null, "appsync:GraphQL", fieldArn, null)
                == IamPolicyEvaluator.Decision.DENY;
    }

    public static boolean isEmulatorAllow(String accessKeyId) {
        return "test".equals(accessKeyId);
    }

    public static String fieldArn(String region, String accountId, String apiId, String typeName, String fieldName) {
        return "arn:aws:appsync:" + nullToEmpty(region) + ":" + nullToEmpty(accountId)
                + ":apis/" + apiId + "/types/" + typeName + "/fields/" + fieldName;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
