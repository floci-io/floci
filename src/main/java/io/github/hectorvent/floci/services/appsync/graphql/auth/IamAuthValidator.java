package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class IamAuthValidator {

    private final AccountResolver accountResolver;
    private final IamService iamService;
    private final IamPolicyEvaluator iamPolicyEvaluator;

    @Inject
    public IamAuthValidator(AccountResolver accountResolver,
                            IamService iamService,
                            IamPolicyEvaluator iamPolicyEvaluator) {
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.iamPolicyEvaluator = iamPolicyEvaluator;
    }

    public Map<String, Object> validateRequest(String authorization, String apiId, AuthRequestInfo info) {
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        if (accessKeyId == null || accessKeyId.isBlank()) {
            throw AppSyncAuth.unauthorized();
        }
        if (!isEmulatorAllow(accessKeyId)) {
            CallerContext caller = iamService.resolveCallerContext(accessKeyId);
            if (caller != null) {
                String resource = requestArn(info.region(), info.accountId(), apiId);
                IamPolicyEvaluator.Decision decision = iamPolicyEvaluator.evaluate(
                        caller, null, "appsync:GraphQL", resource, null);
                if (decision == IamPolicyEvaluator.Decision.DENY) {
                    throw AppSyncAuth.unauthorized();
                }
            }
        }
        String userArn = iamService.resolveCallerArn(accessKeyId).orElseGet(
                () -> "arn:aws:iam::" + nullToEmpty(info.accountId()) + ":root");
        String username = usernameFromArn(userArn, accessKeyId);
        return IdentityBuilder.iam(info.accountId(), accessKeyId, username, userArn, info.sourceIp());
    }

    public boolean isFieldDenied(String accessKeyId, String fieldArn) {
        if (accessKeyId == null || isEmulatorAllow(accessKeyId)) {
            return false;
        }
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        if (caller == null) {
            return false;
        }
        return iamPolicyEvaluator.evaluate(caller, null, "appsync:GraphQL", fieldArn, null)
                == IamPolicyEvaluator.Decision.DENY;
    }

    static boolean isEmulatorAllow(String accessKeyId) {
        return "test".equals(accessKeyId);
    }

    static String requestArn(String region, String accountId, String apiId) {
        return "arn:aws:appsync:" + nullToEmpty(region) + ":" + nullToEmpty(accountId)
                + ":apis/" + apiId + "/*";
    }

    static String fieldArn(String region, String accountId, String apiId, String typeName, String fieldName) {
        return "arn:aws:appsync:" + nullToEmpty(region) + ":" + nullToEmpty(accountId)
                + ":apis/" + apiId + "/types/" + typeName + "/fields/" + fieldName;
    }

    static String usernameFromArn(String userArn, String accessKeyId) {
        if (userArn == null) {
            return accessKeyId;
        }
        int slash = userArn.lastIndexOf('/');
        if (slash >= 0 && slash < userArn.length() - 1) {
            return userArn.substring(slash + 1);
        }
        return accessKeyId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
