package io.github.hectorvent.floci.services.pipes;

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

class PipesRedshiftTargetAuthorizerTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/pipe-redshift";
    private static final String CLUSTER = "arn:aws:redshift:us-east-1:" + ACCOUNT_ID + ":cluster:warehouse";
    private static final String PIPE_ARN = "arn:aws:pipes:us-east-1:" + ACCOUNT_ID + ":pipe/load";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"pipes.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String ALLOW_BATCH = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":"redshift-data:BatchExecuteStatement","Resource":"*"}]}""";
    private static final Permission BATCH = new Permission("redshift-data:BatchExecuteStatement", CLUSTER);

    private EmulatorConfig config;
    private IamService iamService;
    private PipesRedshiftTargetAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iamService = mock(IamService.class);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        Instance<ScpProvider> scpProvider = mock(Instance.class);
        when(scpProvider.isResolvable()).thenReturn(false);
        ServiceRoleAuthorizer serviceRoleAuthorizer = new ServiceRoleAuthorizer(config, iamService, evaluator,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), scpProvider);
        authorizer = new PipesRedshiftTargetAuthorizer(config, serviceRoleAuthorizer);
    }

    private void role(String trust, String... policies) {
        IamRole role = new IamRole("id", "pipe-redshift", "/", ROLE_ARN, trust);
        when(iamService.findRole(ACCOUNT_ID, "pipe-redshift")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(CallerContext.of(List.of(policies)));
    }

    @Test
    void aRoleThatTrustsPipesAndAllowsTheBatchIsAllowed() {
        role(TRUST, ALLOW_BATCH);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).isEmpty());
    }

    @Test
    void aRoleThatTrustsOnlyEventsIsRefused() {
        role(TRUST.replace("pipes.amazonaws.com", "events.amazonaws.com"), ALLOW_BATCH);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).isPresent());
    }

    @Test
    void aRoleWithoutThePermissionIsRefused() {
        role(TRUST, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"redshift-data:ExecuteStatement","Resource":"*"}]}""");

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).isPresent());
    }

    @Test
    void theTrustPolicyMaySourceArnToThePipe() {
        String conditioned = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"pipes.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"ArnLike":{"aws:SourceArn":"%s"}}}]}""";
        role(conditioned.formatted(PIPE_ARN), ALLOW_BATCH);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).isEmpty());

        role(conditioned.formatted("arn:aws:pipes:us-east-1:" + ACCOUNT_ID + ":pipe/other"), ALLOW_BATCH);
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).isPresent());
    }

    @Test
    void aMissingRoleIsRefused() {
        assertEquals("A Redshift target requires an IAM role, and this pipe names none.",
                authorizer.refusal(null, ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).orElseThrow());
    }

    @Test
    void nothingIsCheckedWhileIamEnforcementIsOff() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);

        assertTrue(authorizer.refusal("not-an-arn", ACCOUNT_ID, PIPE_ARN, List.of(BATCH)).isEmpty());
        verifyNoInteractions(iamService);
    }
}
