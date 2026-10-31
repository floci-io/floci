package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventBridgeRedshiftTargetAuthorizerTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/events-redshift";
    private static final String CLUSTER = "arn:aws:redshift:us-east-1:" + ACCOUNT_ID + ":cluster:warehouse";
    private static final String SECRET = "arn:aws:secretsmanager:us-east-1:" + ACCOUNT_ID + ":secret:db-AbCdEf";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String ALLOW_EXECUTE = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":"redshift-data:ExecuteStatement","Resource":"*"}]}""";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:" + ACCOUNT_ID + ":rule/refresh";
    private static final Permission EXECUTE = new Permission("redshift-data:ExecuteStatement", CLUSTER);

    private EmulatorConfig config;
    private IamService iamService;
    private ScpProvider scps;
    private EventBridgeRedshiftTargetAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iamService = mock(IamService.class);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        Instance<ScpProvider> scpProvider = mock(Instance.class);
        scps = mock(ScpProvider.class);
        when(scpProvider.isResolvable()).thenReturn(true);
        when(scpProvider.get()).thenReturn(scps);
        ServiceRoleAuthorizer serviceRoleAuthorizer = new ServiceRoleAuthorizer(config, iamService, evaluator,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), scpProvider);
        authorizer = new EventBridgeRedshiftTargetAuthorizer(config, serviceRoleAuthorizer);
    }

    private void role(String trust, String... policies) {
        IamRole role = new IamRole("id", "events-redshift", "/", ROLE_ARN, trust);
        when(iamService.findRole(ACCOUNT_ID, "events-redshift")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(CallerContext.of(List.of(policies)));
    }

    @Test
    void trustedRoleWithThePermissionIsAllowed() {
        role(TRUST, ALLOW_EXECUTE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isEmpty());
    }

    @Test
    void nothingIsCheckedWhileIamEnforcementIsOff() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);

        assertTrue(authorizer.refusal("not-an-arn", ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isEmpty());
        verifyNoInteractions(iamService);
    }

    @Test
    void missingRoleIsRefused() {
        when(iamService.findRole(ACCOUNT_ID, "events-redshift")).thenReturn(Optional.empty());

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).orElseThrow()
                .contains("does not exist"));
    }

    @Test
    void roleWithoutAnArnIsRefused() {
        assertTrue(authorizer.refusal(null, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).orElseThrow()
                .contains("requires an IAM role"));
    }

    @Test
    void roleOfAnotherAccountIsRefused() {
        assertTrue(authorizer.refusal("arn:aws:iam::999999999999:role/events-redshift", ACCOUNT_ID, RULE_ARN,
                List.of(EXECUTE)).orElseThrow().contains("belongs to another account"));
    }

    @Test
    void roleThatDoesNotTrustEventBridgeIsRefused() {
        role("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""", ALLOW_EXECUTE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).orElseThrow()
                .contains("does not trust events.amazonaws.com"));
    }

    @Test
    void roleWithoutTheActionIsRefusedAndTheMessageNamesIt() {
        role(TRUST, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"redshift-data:DescribeStatement","Resource":"*"}]}""");

        assertEquals("The role " + ROLE_ARN + " is not authorized to perform redshift-data:ExecuteStatement on "
                        + CLUSTER + ".",
                authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).orElseThrow());
    }

    @Test
    void everyPermissionHasToBeAllowed() {
        role(TRUST, ALLOW_EXECUTE);
        Permission secret = new Permission("secretsmanager:GetSecretValue", SECRET);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE, secret)).orElseThrow()
                .contains("secretsmanager:GetSecretValue on " + SECRET));
    }

    @Test
    void permissionsGrantedAcrossSeveralPoliciesAreAllowed() {
        role(TRUST, ALLOW_EXECUTE, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"secretsmanager:GetSecretValue","Resource":"%s"}]}""".formatted(SECRET));

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN,
                List.of(EXECUTE, new Permission("secretsmanager:GetSecretValue", SECRET))).isEmpty());
    }

    @Test
    void policyScopedToAnotherClusterIsRefused() {
        role(TRUST, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"redshift-data:ExecuteStatement",
                "Resource":"arn:aws:redshift:us-east-1:%s:cluster:other"}]}""".formatted(ACCOUNT_ID));

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isPresent());
    }

    @Test
    void explicitDenyBeatsAnAllow() {
        role(TRUST, ALLOW_EXECUTE, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "Action":"redshift-data:ExecuteStatement","Resource":"*"}]}""");

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isPresent());
    }

    @Test
    void serviceControlPolicyDenyIsRefused() {
        role(TRUST, ALLOW_EXECUTE);
        when(scps.effectiveScpLevels(ACCOUNT_ID)).thenReturn(List.of(List.of("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "Action":"redshift-data:ExecuteStatement","Resource":"*"}]}""")));

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isPresent());
    }

    @Test
    void permissionsBoundaryThatDoesNotAllowTheActionIsRefused() {
        role(TRUST, ALLOW_EXECUTE);
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(new CallerContext(
                List.of(ALLOW_EXECUTE), null, """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"s3:*","Resource":"*"}]}"""));

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isPresent());
    }

    @Test
    void trustPolicyNamingTheMatchedRuleInSourceArnIsAllowed() {
        role(sourceArnTrust(RULE_ARN), ALLOW_EXECUTE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isEmpty());
    }

    @Test
    void trustPolicyNamingAnotherRuleInSourceArnIsRefused() {
        role(sourceArnTrust("arn:aws:events:us-east-1:" + ACCOUNT_ID + ":rule/other"), ALLOW_EXECUTE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).orElseThrow()
                .contains("does not trust events.amazonaws.com"));
    }

    @Test
    void sourceArnConditionDoesNotMatchWhenTheRuleIsUnknown() {
        role(sourceArnTrust(RULE_ARN), ALLOW_EXECUTE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, null, List.of(EXECUTE)).isPresent());
    }

    private static String sourceArnTrust(String ruleArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}""".formatted(ruleArn);
    }

    @Test
    void trustPolicyWithMismatchedSourceAccountConditionIsRefused() {
        role("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"999999999999"}}}]}""", ALLOW_EXECUTE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, RULE_ARN, List.of(EXECUTE)).isPresent());
    }
}
