package io.floci.aws.services.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supplies Lambda function policies to the IAM policy evaluation pipeline, together with the account
 * that owns the function.
 *
 * <p>The owner is the account in the function ARN, and it is returned even when the function has no
 * policy or does not exist: a call to another account's function is then judged as cross-account,
 * which needs the function policy to allow the caller as well as the caller's own policy (Lambda
 * Developer Guide, function URLs: "If the principal making the request is in a different account,
 * then the principal must have both an identity-based policy ... and permissions granted to them in
 * a resource-based policy").
 *
 * <p>All of a function's statements are returned, its own and those of its versions and aliases. Each
 * statement's {@code Resource} names the function, version or alias it was added to, so matching it
 * against the ARN the call names is what scopes it: a grant on an alias covers calls to that alias
 * only, and one on the function does not cover a qualified call (Lambda Developer Guide, "Referencing
 * functions in the Resource section of policies").
 *
 * <p>Keyed on the ARN rather than the request's credential scope, because a function is also the
 * second resource of calls signed for another service: {@code RotateSecret} checks
 * {@code lambda:InvokeFunction} on its rotation function under the {@code secretsmanager} scope.
 */
@ApplicationScoped
public class LambdaResourcePolicyProvider implements ResourcePolicyProvider {

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    /** A function, version or alias, or a durable execution under one: the name is group 1. */
    private static final Pattern FUNCTION_RESOURCE = Pattern.compile("function:([^:/]+)(?:[:/].*)?");

    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaResourcePolicyProvider(LambdaService lambdaService, ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "lambda")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        Matcher function = FUNCTION_RESOURCE.matcher(arn.resource());
        if (!function.matches() || !ACCOUNT_ID.matcher(arn.accountId()).matches()) {
            return List.of();
        }
        List<Map<String, Object>> statements =
                lambdaService.policyStatements(arn.accountId(), arn.region(), function.group(1));
        return List.of(new ResourcePolicy(policyDocument(statements), arn.accountId()));
    }

    private String policyDocument(List<Map<String, Object>> statements) {
        if (statements.isEmpty()) {
            return null;
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("Version", "2012-10-17");
        policy.put("Statement", statements);
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the function policy", e);
        }
    }
}
