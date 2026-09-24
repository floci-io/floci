package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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
