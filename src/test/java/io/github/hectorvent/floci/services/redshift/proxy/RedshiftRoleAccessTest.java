package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedshiftRoleAccessTest {

    @Test
    void s3ResourcesInheritTheRoleArnPartition() {
        String roleArn = "arn:aws-cn:iam::111111111111:role/spectrum-role";

        assertEquals("arn:aws-cn:s3:::bucket", RedshiftRoleAccess.bucketArn(roleArn, "bucket"));
        assertEquals("arn:aws-cn:s3:::bucket/key", RedshiftRoleAccess.objectArn(roleArn, "bucket", "key"));
    }

    @Test
    void authorizeRoleActionThrowsWhenEnforcedPolicyDeniesTheAction() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(true);
        IamService iamService = mock(IamService.class);
        String roleArn = "arn:aws:iam::111111111111:role/spectrum-role";
        // An empty identity-policy list simulates a role with no attached grants, so
        // simulatePrincipalPolicy never finds an Allow for any action.
        when(iamService.resolvePrincipalContext(roleArn)).thenReturn(CallerContext.of(List.of()));

        S3CopySimulator.S3TransferException exception = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> RedshiftRoleAccess.authorizeRoleAction(s3, iamService, roleArn, "s3:GetObject",
                        "arn:aws:s3:::bucket/key"));

        assertThat(exception.sqlState(), equalTo("42501"));
        assertThat(exception.getMessage(), containsString("S3 access denied"));
    }

    private static final String ACCOUNT = "111111111111";
    private static final String ROLE_ARN = "arn:aws:iam::111111111111:role/spectrum-role";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String EC2_TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    private static IamService iamServiceWithRole(String trustPolicy) {
        IamService iamService = mock(IamService.class);
        IamRole role = mock(IamRole.class);
        when(role.getAssumeRolePolicyDocument()).thenReturn(trustPolicy);
        when(iamService.findRole(ACCOUNT, "spectrum-role")).thenReturn(Optional.of(role));
        return iamService;
    }

    private static void assertRefused(String message, String iamRoleArn, IamService iamService,
                                      String clusterAccount, List<String> associated) {
        S3CopySimulator.S3TransferException exception = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> RedshiftRoleAccess.resolveRoleSession(iamRoleArn, iamService, clusterAccount, associated));
        assertThat(exception.sqlState(), equalTo("42501"));
        assertThat(exception.getMessage(), containsString(message));
    }

    @Test
    void resolveRoleSessionRefusesMalformedNonRoleAndCrossAccountArns() {
        IamService iamService = iamServiceWithRole(TRUST_POLICY);

        assertRefused("malformed ARN", "not-an-arn", iamService, ACCOUNT, List.of());
        assertRefused("not an IAM role ARN", "arn:aws:iam::111111111111:user/someone", iamService, ACCOUNT, List.of());
        assertRefused("cross-account", ROLE_ARN, iamService, "222222222222", List.of(ROLE_ARN));
    }

    @Test
    void resolveRoleSessionRefusesARoleTheClusterDoesNotHaveOrThatDoesNotExist() {
        assertRefused("not associated", ROLE_ARN, iamServiceWithRole(TRUST_POLICY), ACCOUNT, List.of());

        IamService missing = mock(IamService.class);
        when(missing.findRole(ACCOUNT, "spectrum-role")).thenReturn(Optional.empty());
        assertRefused("does not exist", ROLE_ARN, missing, ACCOUNT, List.of(ROLE_ARN));
    }

    @Test
    void resolveRoleSessionRefusesARoleWhoseTrustPolicyExcludesRedshift() {
        assertRefused("trust policy", ROLE_ARN, iamServiceWithRole(EC2_TRUST_POLICY), ACCOUNT, List.of(ROLE_ARN));
    }

    @Test
    void resolveRoleSessionMintsASessionThatExpiresAfterTheRequestedTtl() {
        IamService iamService = iamServiceWithRole(TRUST_POLICY);
        Instant before = Instant.now();

        RedshiftRoleAccess.RoleSession session = RedshiftRoleAccess.resolveRoleSession(
                ROLE_ARN, iamService, ACCOUNT, List.of(ROLE_ARN), RedshiftRoleAccess.STREAMING_ROLE_SESSION_TTL);

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(iamService).registerSessionForAccount(eq(ACCOUNT), eq(session.accessKeyId()), anyString(),
                eq(session.sessionToken()), eq(ROLE_ARN), expiry.capture(), isNull());
        assertThat(expiry.getValue().isAfter(before.plus(Duration.ofMinutes(29))), equalTo(true));
        assertThat(expiry.getValue().isBefore(before.plus(Duration.ofMinutes(31))), equalTo(true));
    }

    @Test
    void authorizeRoleActionAllowsWhatTheIdentityPolicyGrants() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(true);
        IamService iamService = mock(IamService.class);
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""")));

        RedshiftRoleAccess.authorizeRoleAction(s3, iamService, ROLE_ARN, "s3:GetObject", "arn:aws:s3:::bucket/key");
    }

    @Test
    void authorizeRoleListHonoursAnS3PrefixCondition() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(true);
        IamService iamService = mock(IamService.class);
        when(iamService.resolvePrincipalContext(ROLE_ARN)).thenReturn(CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:ListBucket",
                 "Resource":"arn:aws:s3:::bucket","Condition":{"StringLike":{"s3:prefix":["events/*"]}}}]}""")));
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("ASIAX", "token");

        RedshiftRoleAccess.authorizeRoleList(s3, iamService, session, ROLE_ARN, "bucket", "events/");
        S3CopySimulator.S3TransferException denied = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> RedshiftRoleAccess.authorizeRoleList(s3, iamService, session, ROLE_ARN, "bucket", "other/"));

        assertThat(denied.sqlState(), equalTo("42501"));
    }

    @Test
    void aBucketPolicyDenyIsReportedAsAccessDeniedForListAndRead() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(false);
        IamService iamService = mock(IamService.class);
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("ASIAX", "token");
        doThrow(new AwsException("AccessDenied", "Access Denied", 403)).when(s3)
                .authorizeSignedListBucket("ASIAX", "token", "bucket");
        doThrow(new AwsException("AccessDenied", "Access Denied", 403)).when(s3)
                .authorizeSignedGetObject("ASIAX", "token", "bucket", "key");

        S3CopySimulator.S3TransferException list = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> RedshiftRoleAccess.authorizeRoleList(s3, iamService, session, ROLE_ARN, "bucket", ""));
        S3CopySimulator.S3TransferException read = assertThrows(S3CopySimulator.S3TransferException.class,
                () -> RedshiftRoleAccess.authorizeRoleRead(s3, iamService, session, ROLE_ARN, "bucket", "key"));

        assertThat(list.sqlState(), equalTo("42501"));
        assertThat(read.sqlState(), equalTo("42501"));
        assertThat(read.getMessage(), containsString("s3://bucket/key"));
    }

    @Test
    void authorizeRoleActionSkipsCheckWhenEnforcementDisabled() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(false);
        IamService iamService = mock(IamService.class);

        RedshiftRoleAccess.authorizeRoleAction(s3, iamService, "arn:aws:iam::111111111111:role/x",
                "s3:GetObject", "arn:aws:s3:::bucket/key");

        // No exception, and resolvePrincipalContext is never consulted when enforcement is off.
        verifyNoInteractions(iamService);
    }
}
