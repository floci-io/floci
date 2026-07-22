package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Mints and revokes the IAM sessions used by Lambda execution environments. */
@ApplicationScoped
public class LambdaExecutionRoleCredentials {

    private static final String UPPER_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String SECRET_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private final IamService iamService;

    @Inject
    public LambdaExecutionRoleCredentials(IamService iamService) {
        this.iamService = iamService;
    }

    /**
     * Mints a non-expiring session for a known execution role. Unknown or malformed roles retain
     * the existing container credential fallback rather than becoming an implicit deny-all caller.
     */
    public Optional<SessionCreds> forFunction(LambdaFunction function) {
        String roleArn = function.getRole();
        String functionAccountId = function.getAccountId();
        if (roleArn == null || roleArn.isBlank()
                || functionAccountId == null || functionAccountId.isBlank()) {
            return Optional.empty();
        }

        String roleName;
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
            if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
                return Optional.empty();
            }
            roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (roleName.isBlank()) {
            return Optional.empty();
        }

        String roleAccountId = AwsArnUtils.accountOrDefault(roleArn, functionAccountId);
        if (iamService.findRole(roleAccountId, roleName).isEmpty()) {
            return Optional.empty();
        }

        SessionCreds credentials = new SessionCreds(
                "ASIA" + random(UPPER_ALPHANUMERIC, 16),
                random(SECRET_CHARACTERS, 40),
                random(SECRET_CHARACTERS, 200));
        iamService.registerLambdaExecutionRoleSession(
                functionAccountId, credentials.accessKeyId(), credentials.secretAccessKey(), roleArn);
        return Optional.of(credentials);
    }

    public void unregister(String accountId, String accessKeyId) {
        iamService.unregisterSession(accountId, accessKeyId);
    }

    private static String random(String characters, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(characters.charAt(ThreadLocalRandom.current().nextInt(characters.length())));
        }
        return value.toString();
    }
}
