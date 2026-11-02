package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ServiceRoleAuthorizerTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/service-role";
    private static final String RESOURCE = "arn:aws:s3:::my-bucket";
    private static final String SERVICE_PRINCIPAL = "events.amazonaws.com";
    private static final String SOURCE_ARN = "arn:aws:events:us-east-1:" + ACCOUNT_ID + ":rule/trigger";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":"s3:PutObject","Resource":"*"}]}""";
    private static final Permission PERMISSION = new Permission("s3:PutObject", RESOURCE);

    private EmulatorConfig config;
    private IamService iamService;
    private ScpProvider scps;
    private ServiceRoleAuthorizer authorizer;

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
        authorizer = new ServiceRoleAuthorizer(config, iamService, evaluator,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), scpProvider);
    }

    private void role(String trust, String... policies) {
        IamRole role = new IamRole("id", "service-role", "/", ROLE_ARN, trust);
        when(iamService.findRole(ACCOUNT_ID, "service-role")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(CallerContext.of(List.of(policies)));
    }

    @Test
    void trustedRoleWithPermissionsIsAllowed() {
        role(TRUST, ALLOW);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isEmpty());
    }

    @Test
    void enforcementOffAllowsEverything() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);

        assertTrue(authorizer.refusal("not-an-arn", ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isEmpty());
        verifyNoInteractions(iamService);
    }

    @Test
    void missingRoleIsRefused() {
        when(iamService.findRole(ACCOUNT_ID, "service-role")).thenReturn(Optional.empty());

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).orElseThrow().contains("does not exist"));
    }

    @Test
    void roleOfAnotherAccountIsRefusedWithoutLookup() {
        Optional<String> refusal = authorizer.refusal("arn:aws:iam::999999999999:role/service-role",
                ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN, List.of(PERMISSION));

        assertTrue(refusal.orElseThrow().contains("belongs to another account"));
        verify(iamService, never()).findRole(anyString(), anyString());
    }

    @Test
    void nonRoleArnIsRefused() {
        assertTrue(authorizer.refusal("arn:aws:iam::" + ACCOUNT_ID + ":user/someone",
                ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN, List.of(PERMISSION)).orElseThrow()
                .contains("is not an IAM role"));
        assertTrue(authorizer.refusal("invalid-arn",
                ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN, List.of(PERMISSION)).orElseThrow()
                .contains("is not a valid ARN"));
    }

    @Test
    void untrustedServicePrincipalIsRefused() {
        role(TRUST.replace("events.amazonaws.com", "lambda.amazonaws.com"), ALLOW);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).orElseThrow().contains("does not trust events.amazonaws.com"));
    }

    @Test
    void missingPermissionIsRefusedAndNamesActionAndResource() {
        role(TRUST, ALLOW);
        Permission other = new Permission("s3:GetObject", RESOURCE);

        assertEquals("The role " + ROLE_ARN + " is not authorized to perform s3:GetObject on " + RESOURCE + ".",
                authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                        List.of(other)).orElseThrow());
    }

    @Test
    void everyPermissionMustBeAllowed() {
        role(TRUST, ALLOW);
        Permission denied = new Permission("s3:DeleteObject", RESOURCE);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION, denied)).orElseThrow().contains("s3:DeleteObject"));
    }

    @Test
    void scpDenyIsRefused() {
        role(TRUST, ALLOW);
        when(scps.effectiveScpLevels(ACCOUNT_ID)).thenReturn(List.of(List.of("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "Action":"s3:PutObject","Resource":"*"}]}""")));

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isPresent());
    }

    @Test
    void permissionsBoundaryDenyIsRefused() {
        role(TRUST, ALLOW);
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(new CallerContext(
                List.of(ALLOW), null, """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"sqs:*","Resource":"*"}]}"""));

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isPresent());
    }

    @Test
    void sourceArnTrustConditionIsEvaluated() {
        String conditionalTrust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}""".formatted(SOURCE_ARN);
        role(conditionalTrust, ALLOW);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isEmpty());
        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, "arn:aws:events:us-east-1:123456789012:rule/other",
                List.of(PERMISSION)).isPresent());
    }

    @Test
    void sourceAccountTrustConditionIsEvaluated() {
        String conditionalTrust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"%s"}}}]}""".formatted(ACCOUNT_ID);
        role(conditionalTrust, ALLOW);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isEmpty());

        String mismatchTrust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"999999999999"}}}]}""";
        role(mismatchTrust, ALLOW);

        assertTrue(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, SERVICE_PRINCIPAL, SOURCE_ARN,
                List.of(PERMISSION)).isPresent());
    }
}
