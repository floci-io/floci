package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LogGroupArnPolicyCompatibilityTest {

    private static final String GROUP = "arn:aws:logs:us-east-1:111111111111:log-group:/app/selected";
    private static final String OTHER = "arn:aws:logs:us-east-1:111111111111:log-group:/app/other";
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy", "TagLogGroup"})
    void documentedGroupArnsAndLegacySuffixesSelectTheSameGroup(String operation) {
        String action = "logs:" + operation;
        for (String location : List.of("aws:logs:us-east-1", "aws-cn:logs:cn-north-1", "aws-us-gov:logs:us-gov-west-1")) {
            String selected = "arn:" + location + ":111111111111:log-group:/app/selected";
            for (String policySuffix : List.of("", ":*")) {
                String policy = document(statement("Allow", action, "Resource", selected + policySuffix));
                for (String requestSuffix : List.of("", ":*")) {
                    assertEquals(Decision.ALLOW, decide(policy, action, selected + requestSuffix));
                    assertEquals(Decision.DENY, decide(policy, action,
                            selected.replace("/selected", "/other") + requestSuffix));
                    assertEquals(Decision.DENY, decide(policy, action,
                            selected.replace("111111111111", "222222222222") + requestSuffix));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogGroup", "DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy", "TagLogGroup"})
    void explicitDenyWinsEvenWhenItsArnUsesTheOtherForm(String operation) {
        String action = "logs:" + operation;
        for (String denySuffix : List.of("", ":*")) {
            String allowSuffix = denySuffix.isEmpty() ? ":*" : "";
            String policy = document(statement("Allow", action, "Resource", GROUP + allowSuffix),
                    statement("Deny", action, "Resource", GROUP + denySuffix));
            for (String requestSuffix : List.of("", ":*")) {
                assertEquals(Decision.DENY, decide(policy, action, GROUP + requestSuffix));
            }
            String control = document(statement("Allow", action, "Resource", "*"),
                    statement("Deny", action, "Resource", GROUP + denySuffix));
            assertEquals(Decision.ALLOW, decide(control, action, OTHER));
        }
    }

    @Test
    void notResourceExcludesTheGroupInEitherForm() {
        String action = "logs:DeleteLogGroup";
        for (String excludedSuffix : List.of("", ":*")) {
            String exclude = statement("Deny", action, "NotResource", GROUP + excludedSuffix);
            String policy = document(statement("Allow", action, "Resource", "*"), exclude);
            assertEquals(Decision.DENY, decide(policy, action, OTHER));
            assertEquals(Decision.ALLOW, decide(policy, action, GROUP));
            assertEquals(Decision.ALLOW, decide(policy, action, GROUP + ":*"));
            String allowOutside = document(statement("Allow", action, "NotResource", GROUP + excludedSuffix));
            assertEquals(Decision.ALLOW, decide(allowOutside, action, OTHER));
            assertEquals(Decision.DENY, decide(allowOutside, action, GROUP));
            assertEquals(Decision.DENY, decide(allowOutside, action, GROUP + ":*"));
        }
    }

    @Test
    void variablesResolveBeforeGroupMatchingAndMissingVariablesGrantNothing() {
        String action = "logs:PutRetentionPolicy";
        String pattern = GROUP.replace("selected", "${aws:username}");
        Map<String, List<String>> context = Map.of("aws:username", List.of("selected"));
        String allow = document(statement("Allow", action, "Resource", pattern));
        CallerContext caller = CallerContext.of(List.of(allow));
        assertEquals(Decision.ALLOW, evaluator.evaluate(caller, null, action, GROUP + ":*", context));
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, action, GROUP + ":*", Map.of()));
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, action, OTHER, context));
        String deny = document(statement("Allow", action, "Resource", "*"),
                statement("Deny", action, "Resource", pattern));
        assertEquals(Decision.DENY, evaluator.evaluate(CallerContext.of(List.of(deny)), null,
                action, GROUP + ":*", context));
        assertEquals(Decision.ALLOW, evaluator.evaluate(CallerContext.of(List.of(deny)), null,
                action, GROUP + ":*", Map.of()));
        String legacy = allow.replace("2012-10-17", "2008-10-17");
        assertEquals(Decision.DENY, evaluator.evaluate(CallerContext.of(List.of(legacy)), null,
                action, GROUP + ":*", context));
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "boundary", "scp"})
    void callerCeilingsMustAllowAndMustNotDenyEitherForm(String ceiling) {
        String action = "logs:CreateLogGroup";
        String identity = document(statement("Allow", action, "Resource", GROUP + ":*"));
        String grant = document(statement("Allow", action, "Resource", GROUP));
        String missingGrant = document(statement("Allow", action, "Resource", OTHER));
        String denial = document(statement("Allow", action, "Resource", "*"),
                statement("Deny", action, "Resource", GROUP));
        assertEquals(Decision.ALLOW, evaluator.evaluate(caller(ceiling, identity, grant), null,
                action, GROUP + ":*", Map.of()));
        assertEquals(Decision.DENY, evaluator.evaluate(caller(ceiling, identity, missingGrant), null,
                action, GROUP + ":*", Map.of()));
        assertEquals(Decision.DENY, evaluator.evaluate(caller(ceiling, identity, denial), null,
                action, GROUP + ":*", Map.of()));
        String reverseIdentity = document(statement("Allow", action, "Resource", GROUP));
        String reverseDeny = document(statement("Allow", action, "Resource", "*"),
                statement("Deny", action, "Resource", GROUP + ":*"));
        assertEquals(Decision.DENY, evaluator.evaluate(caller(ceiling, reverseIdentity, reverseDeny), null,
                action, GROUP, Map.of()));
    }

    @Test
    void resourcePolicyGrantAndDenyUseTheSameGroupIdentity() {
        String action = "logs:DeleteLogGroup";
        String userArn = "arn:aws:iam::111111111111:user/alice";
        CallerContext caller = CallerContext.of(List.of()).withPrincipalArn(userArn);
        String grant = document(resourceStatement("Allow", action, GROUP, userArn));
        assertEquals(Decision.ALLOW, evaluator.evaluate(caller, List.of(grant), action, GROUP + ":*", Map.of()));
        String deny = document(resourceStatement("Deny", action, GROUP + ":*", userArn));
        CallerContext allowed = CallerContext.of(List.of(document(statement("Allow", action, "Resource", GROUP))))
                .withPrincipalArn(userArn);
        assertEquals(Decision.DENY, evaluator.evaluate(allowed, List.of(deny), action, GROUP, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLogStream", "DeleteLogStream", "TagResource", "UntagResource", "ListTagsForResource"})
    void otherLogsActionsDoNotAcquireGroupArnAliases(String operation) {
        String action = "logs:" + operation;
        String policy = document(statement("Allow", action, "Resource", GROUP));
        assertEquals(Decision.ALLOW, decide(policy, action, GROUP));
        assertEquals(Decision.DENY, decide(policy, action, GROUP + ":*"));
        assertEquals(Decision.DENY, decide(policy, action, GROUP + ":log-stream:s"));
    }

    @Test
    void otherServicesCaseAndMalformedGroupIdentifiersDoNotBecomeAliases() {
        String action = "logs:DeleteLogGroup";
        for (String resource : List.of(GROUP.replace(":logs:", ":s3:"), GROUP + ":child",
                GROUP.substring(0, GROUP.lastIndexOf(':') + 1), "log-group:/app/selected")) {
            String policy = document(statement("Allow", action, "Resource", resource));
            assertEquals(Decision.DENY, decide(policy, action, resource + ":*"), resource);
        }
        String policy = document(statement("Allow", action, "Resource", GROUP));
        assertEquals(Decision.DENY, decide(policy, action, GROUP.replace("/selected", "/Selected") + ":*"));
        assertEquals(Decision.DENY, decide(policy, "s3:DeleteBucket", GROUP + ":*"));
        String streamPattern = document(statement("Allow", action, "Resource", GROUP + ":log-stream:*"));
        assertEquals(Decision.DENY, decide(streamPattern, action, GROUP + ":*"));
    }

    private Decision decide(String policy, String action, String resource) {
        return evaluator.evaluate(CallerContext.of(List.of(policy)), null, action, resource, Map.of());
    }

    private static CallerContext caller(String ceiling, String identity, String policy) {
        return switch (ceiling) {
            case "session" -> new CallerContext(List.of(identity), policy, null, null);
            case "boundary" -> new CallerContext(List.of(identity), null, policy, null);
            case "scp" -> new CallerContext(List.of(identity), null, null, List.of(List.of(policy)));
            default -> throw new IllegalArgumentException(ceiling);
        };
    }

    private static String document(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    private static String statement(String effect, String action, String resourceElement, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"" + resourceElement
                + "\":\"" + resource + "\"}";
    }

    private static String resourceStatement(String effect, String action, String resource, String principal) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource
                + "\",\"Principal\":{\"AWS\":\"" + principal + "\"}}";
    }
}
