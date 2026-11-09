package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessSharedSnapshotsTest {

    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String SNAPSHOT_ARN = "arn:aws:redshift-serverless:us-east-1:" + OWNER + ":snapshot/shared";

    private RedshiftServerlessService serverless;
    private RedshiftServerlessResourcePolicyService policies;
    private RedshiftServerlessSharedSnapshots shared;
    private RedshiftServerlessSnapshot snapshot;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        serverless = mock(RedshiftServerlessService.class);
        policies = mock(RedshiftServerlessResourcePolicyService.class);
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArns(any())).thenReturn(Optional.empty());
        Instance<RequestContext> requestContext = mock(Instance.class);
        shared = new RedshiftServerlessSharedSnapshots(serverless, policies,
                new IamPolicyEvaluator(new ObjectMapper()), iamService, new RegionResolver(REGION, CALLER),
                requestContext);

        snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName("shared");
        snapshot.setSnapshotArn(SNAPSHOT_ARN);
        snapshot.setSnapshotCreateTime(Instant.parse("2030-01-01T00:00:00Z"));
        when(serverless.findSnapshotOfAccount(OWNER, "shared", REGION)).thenReturn(Optional.of(snapshot));
    }

    private void policy(String document) {
        when(policies.policyOfSnapshot(eq(OWNER), eq(snapshot), eq(REGION))).thenReturn(Optional.of(document));
    }

    private static String statement(String effect, String principal) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\",\"Principal\":{\"AWS\":\""
                + principal + "\"},\"Action\":\"redshift-serverless:RestoreFromSnapshot\",\"Resource\":\"*\"}]}";
    }

    @Test
    void onlyAnotherAccountNeedsThePolicy() {
        assertTrue(shared.isOtherAccount(OWNER));
        assertFalse(shared.isOtherAccount(CALLER));
        assertFalse(shared.isOtherAccount(null));
        assertFalse(shared.isOtherAccount(" "));
    }

    @Test
    void policyNamingTheCallersAccountAllowsTheRestore() {
        policy(statement("Allow", "arn:aws:iam::" + CALLER + ":root"));

        assertSame(snapshot, shared.requireRestorable(OWNER, "shared", REGION));
    }

    @Test
    void policyNamingTheAccountIdAllowsTheRestore() {
        policy(statement("Allow", CALLER));

        assertSame(snapshot, shared.requireRestorable(OWNER, "shared", REGION));
    }

    @Test
    void snapshotWithoutAPolicyIsNotShared() {
        when(policies.policyOfSnapshot(eq(OWNER), eq(snapshot), eq(REGION))).thenReturn(Optional.empty());

        AwsException denied = assertThrows(AwsException.class, () -> shared.requireRestorable(OWNER, "shared", REGION));

        assertEquals("AccessDeniedException", denied.getErrorCode());
        assertTrue(denied.getMessage().contains(SNAPSHOT_ARN));
    }

    @Test
    void policyForAnotherAccountDoesNotAllowTheCaller() {
        policy(statement("Allow", "arn:aws:iam::333333333333:root"));

        AwsException denied = assertThrows(AwsException.class, () -> shared.requireRestorable(OWNER, "shared", REGION));

        assertEquals("AccessDeniedException", denied.getErrorCode());
    }

    @Test
    void explicitDenyWinsOverAnAllow() {
        policy("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + CALLER
                + ":root\"},\"Action\":\"redshift-serverless:RestoreFromSnapshot\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + CALLER
                + ":root\"},\"Action\":\"redshift-serverless:RestoreFromSnapshot\",\"Resource\":\"*\"}]}");

        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> shared.requireRestorable(OWNER, "shared", REGION)).getErrorCode());
    }

    @Test
    void policyForAnotherActionDoesNotAllowARestore() {
        policy(statement("Allow", "arn:aws:iam::" + CALLER + ":root")
                .replace("redshift-serverless:RestoreFromSnapshot", "redshift-serverless:GetSnapshot"));

        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> shared.requireRestorable(OWNER, "shared", REGION)).getErrorCode());
    }

    @Test
    void missingSnapshotIsNotFoundWhateverThePolicySays() {
        when(serverless.findSnapshotOfAccount(OWNER, "gone", REGION)).thenReturn(Optional.empty());

        AwsException missing = assertThrows(AwsException.class, () -> shared.requireRestorable(OWNER, "gone", REGION));

        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }
}
