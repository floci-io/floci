package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessScheduledActionAuthorizerTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/scheduler";
    private static final String TARGET = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":namespace/ns-id";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"scheduler.redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":"redshift-serverless:CreateSnapshot","Resource":"*"}]}""";

    private EmulatorConfig config;
    private IamService iamService;
    private Instance<ScpProvider> scpProvider;
    private ScpProvider scps;
    private RedshiftServerlessScheduledActionAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iamService = mock(IamService.class);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        scpProvider = mock(Instance.class);
        scps = mock(ScpProvider.class);
        when(scpProvider.isResolvable()).thenReturn(true);
        when(scpProvider.get()).thenReturn(scps);
        authorizer = new RedshiftServerlessScheduledActionAuthorizer(config, iamService, evaluator,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), scpProvider);
    }

    private void role(String arn, String trust, String policy) {
        IamRole role = new IamRole("id", "scheduler", "/", arn, trust);
        when(iamService.findRole(ACCOUNT_ID, "scheduler")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(arn)).thenReturn(CallerContext.of(List.of(policy)));
    }

    @Test
    void trustedRoleWithThePermissionIsAllowed() {
        role(ROLE_ARN, TRUST, ALLOW);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isEmpty());
    }

    @Test
    void roleInAnotherPartitionOfTheSameAccountIsEvaluatedAsItsOwn() {
        String chinaRole = "arn:aws-cn:iam::" + ACCOUNT_ID + ":role/scheduler";
        role(chinaRole, TRUST, ALLOW);
        assertTrue(authorizer.refusal(chinaRole, ACCOUNT_ID,
                "arn:aws-cn:redshift-serverless:cn-north-1:" + ACCOUNT_ID + ":namespace/ns-id").isEmpty());
    }


    @Test
    void serviceControlPolicyDenyIsRefused() {
        role(ROLE_ARN, TRUST, ALLOW);
        when(scps.effectiveScpLevels(ACCOUNT_ID)).thenReturn(List.of(List.of("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "Action":"redshift-serverless:CreateSnapshot","Resource":"*"}]}""")));
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void serviceControlPolicyThatAllowsLeavesTheRoleAllowed() {
        role(ROLE_ARN, TRUST, ALLOW);
        when(scps.effectiveScpLevels(ACCOUNT_ID)).thenReturn(List.of(List.of("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""")));
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isEmpty());
    }
    @Test
    void trustPolicyWithMatchingSourceAccountConditionIsAllowed() {
        String conditionalTrust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"scheduler.redshift.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"%s"}}}]}""".formatted(ACCOUNT_ID);
        role(ROLE_ARN, conditionalTrust, ALLOW);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isEmpty());
    }

    @Test
    void trustPolicyWithMismatchedSourceAccountConditionIsRefused() {
        String conditionalTrust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"scheduler.redshift.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"999999999999"}}}]}""";
        role(ROLE_ARN, conditionalTrust, ALLOW);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void regionalConditionMatchingTargetRegionIsAllowed() {
        String regionalPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"redshift-serverless:CreateSnapshot","Resource":"*",
                "Condition":{"StringEquals":{"aws:RequestedRegion":"us-east-1"}}}]}""";
        role(ROLE_ARN, TRUST, regionalPolicy);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isEmpty());
    }

    @Test
    void regionalConditionForDifferentRegionIsRefused() {
        String regionalPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"redshift-serverless:CreateSnapshot","Resource":"*",
                "Condition":{"StringEquals":{"aws:RequestedRegion":"us-west-2"}}}]}""";
        role(ROLE_ARN, TRUST, regionalPolicy);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void missingPermissionIsRefused() {
        role(ROLE_ARN, TRUST, ALLOW.replace("CreateSnapshot", "GetSnapshot"));
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void explicitDenyIsRefused() {
        role(ROLE_ARN, TRUST, ALLOW.replace("\"Allow\"", "\"Deny\""));
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void roleThatDoesNotTrustTheSchedulerIsRefused() {
        role(ROLE_ARN, TRUST.replace("scheduler.redshift", "lambda"), ALLOW);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void missingRoleIsRefused() {
        when(iamService.findRole(ACCOUNT_ID, "scheduler")).thenReturn(Optional.empty());
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void roleOfAnotherAccountIsRefusedWithoutLookingItUp() {
        Optional<String> refusal = authorizer.refusal("arn:aws:iam::210987654321:role/scheduler", ACCOUNT_ID, TARGET);
        assertTrue(refusal.isPresent());
        verify(iamService, never()).findRole(anyString(), anyString());
    }

    @Test
    void nonRoleArnIsRefused() {
        assertTrue(authorizer.refusal("arn:aws:iam::" + ACCOUNT_ID + ":user/someone", ACCOUNT_ID, TARGET).isPresent());
        assertTrue(authorizer.refusal("not-an-arn", ACCOUNT_ID, TARGET).isPresent());
    }

    @Test
    void enforcementOffAllowsEverything() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertTrue(authorizer.refusal("not-an-arn", ACCOUNT_ID, TARGET).isEmpty());
    }
}
