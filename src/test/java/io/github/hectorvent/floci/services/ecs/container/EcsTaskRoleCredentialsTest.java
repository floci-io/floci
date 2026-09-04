package io.github.hectorvent.floci.services.ecs.container;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsTaskRoleCredentialsTest {

    private static final Instant TEST_NOW = Instant.parse("2030-01-01T00:00:00Z");

    private IamService iamService;
    private EmulatorConfig config;
    private EcsTaskRoleCredentials credentials;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentialsEnabled()).thenReturn(true);
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(3600);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(300);
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(
                new IamRole("AROAEXAMPLE", "task-role", "/", "arn:aws:iam::111122223333:role/task-role",
                        ecsTasksTrustPolicy())));
        clock = new MutableClock(TEST_NOW);
        credentials = new EcsTaskRoleCredentials(iamService, config,
                new EcsTaskRoleTrustPolicy(new ObjectMapper()), clock);
    }

    private static String ecsTasksTrustPolicy() {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """;
    }

    private static SessionCredential activeFor(EcsTaskRoleCredentials.IssuedCredentials lease) {
        SessionCredential active = mock(SessionCredential.class);
        when(active.getAccessKeyId()).thenReturn(lease.credentials().accessKeyId());
        when(active.getTaskArn()).thenReturn(lease.taskArn());
        return active;
    }

    @Test
    void issuesExactTaskScopedRelativeUriAndSession() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-a";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";

        Optional<EcsTaskRoleCredentials.IssuedCredentials> issued =
                credentials.issue(taskArn, roleArn, "us-east-1");

        assertTrue(issued.isPresent());
        EcsTaskRoleCredentials.IssuedCredentials value = issued.orElseThrow();
        assertEquals(taskArn, value.taskArn());
        assertEquals(roleArn, value.roleArn());
        assertTrue(value.relativeUri().startsWith("/v2/credentials/"));
        assertTrue(value.credentials().accessKeyId().startsWith("ASIA"));
        assertTrue(value.expiration().isAfter(Instant.now()));
        verify(iamService).registerEcsTaskRoleSession(
                eq(taskArn), eq("111122223333"), anyString(), anyString(), anyString(),
                eq(roleArn), any(Instant.class), anyString());
    }

    @Test
    void refreshRotatesCredentialsButKeepsTaskEndpoint() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-b";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();
        SessionCredential firstActive = activeFor(first);
        when(iamService.resolveEcsTaskRoleSessionByPath(first.relativeUri()))
                .thenReturn(Optional.of(firstActive), Optional.empty());

        EcsTaskRoleCredentials.IssuedCredentials refreshed = credentials.refresh(first.relativeUri())
                .orElseThrow();

        assertEquals(first.relativeUri(), refreshed.relativeUri());
        assertEquals(taskArn, refreshed.taskArn());
        assertNotEquals(first.credentials().accessKeyId(), refreshed.credentials().accessKeyId());
        verify(iamService, never()).revokeEcsTaskRoleSessionGeneration(
                taskArn, first.credentials().accessKeyId());
    }

    @Test
    void refreshTaskIfNeededLeavesHealthyLeaseUntouchedOutsideWindow() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-refresh-early";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();
        SessionCredential firstActive = activeFor(first);
        when(iamService.resolveEcsTaskRoleSessionByPath(first.relativeUri()))
                .thenReturn(Optional.of(firstActive));

        clock.advanceSeconds(60);
        EcsTaskRoleCredentials.IssuedCredentials current = credentials.refreshTaskIfNeeded(taskArn)
                .orElseThrow();

        assertSame(first, current);
        verify(iamService, never()).revokeEcsTaskRoleSession(anyString(), anyString());
    }

    @Test
    void refreshTaskIfNeededRotatesDueLeaseBeforeExpiry() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-refresh-due";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();
        SessionCredential firstActive = activeFor(first);
        when(iamService.resolveEcsTaskRoleSessionByPath(first.relativeUri()))
                .thenReturn(Optional.of(firstActive), Optional.empty());

        clock.advanceSeconds(91);
        EcsTaskRoleCredentials.IssuedCredentials refreshed = credentials.refreshTaskIfNeeded(taskArn)
                .orElseThrow();

        assertEquals(first.relativeUri(), refreshed.relativeUri());
        assertNotEquals(first.credentials().accessKeyId(), refreshed.credentials().accessKeyId());
        assertEquals(TEST_NOW.plusSeconds(211), refreshed.expiration());
        verify(iamService, never()).revokeEcsTaskRoleSessionGeneration(
                taskArn, first.credentials().accessKeyId());
    }

    @Test
    void refreshTaskIfNeededRevokesAtExactExpiryInsteadOfRevivingLease() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-refresh-expired";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();

        clock.advanceSeconds(120);
        assertTrue(credentials.refreshTaskIfNeeded(taskArn).isEmpty());
        assertTrue(credentials.current(first.relativeUri()).isEmpty());
        verify(iamService).revokeEcsTaskRoleSessionGeneration(taskArn, first.credentials().accessKeyId());
    }

    @Test
    void refreshTaskIfNeededFailsClosedWhenIamRevokedOutsideWindow() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-refresh-revoked";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();
        when(iamService.resolveEcsTaskRoleSessionByPath(first.relativeUri())).thenReturn(Optional.empty());

        clock.advanceSeconds(10);
        assertTrue(credentials.refreshTaskIfNeeded(taskArn).isEmpty());
        assertTrue(credentials.current(first.relativeUri()).isEmpty());
        verify(iamService).revokeEcsTaskRoleSession(taskArn);
    }

    @Test
    void revokeTaskWinsWhetherItPrecedesOrFollowsRefresh() {
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        String beforeTask = "arn:aws:ecs:us-east-1:111122223333:task/default/task-stop-before";
        EcsTaskRoleCredentials.IssuedCredentials before = credentials.issue(beforeTask, roleArn, "us-east-1")
                .orElseThrow();
        credentials.revokeTask(beforeTask);
        assertTrue(credentials.refreshTaskIfNeeded(beforeTask).isEmpty());
        assertTrue(credentials.current(before.relativeUri()).isEmpty());

        String afterTask = "arn:aws:ecs:us-east-1:111122223333:task/default/task-stop-after";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials after = credentials.issue(afterTask, roleArn, "us-east-1")
                .orElseThrow();
        SessionCredential afterActive = activeFor(after);
        when(iamService.resolveEcsTaskRoleSessionByPath(after.relativeUri()))
                .thenReturn(Optional.of(afterActive), Optional.empty());
        clock.advanceSeconds(91);
        EcsTaskRoleCredentials.IssuedCredentials rotated = credentials.refreshTaskIfNeeded(afterTask)
                .orElseThrow();
        credentials.revokeTask(afterTask);

        assertTrue(credentials.current(rotated.relativeUri()).isEmpty());
        verify(iamService).revokeEcsTaskRoleSession(afterTask);
    }

    @Test
    void failedTrustRotationRevokesEveryOverlappingGeneration() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-trust-rotation";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();
        SessionCredential firstActive = activeFor(first);
        when(iamService.resolveEcsTaskRoleSessionByPath(first.relativeUri()))
                .thenReturn(Optional.of(firstActive));

        clock.advanceSeconds(91);
        EcsTaskRoleCredentials.IssuedCredentials rotated = credentials.refreshTaskIfNeeded(taskArn)
                .orElseThrow();
        SessionCredential rotatedActive = activeFor(rotated);
        when(iamService.resolveEcsTaskRoleSessionByPath(rotated.relativeUri()))
                .thenReturn(Optional.of(rotatedActive));
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(
                new IamRole("AROAEXAMPLE", "task-role", "/",
                        "arn:aws:iam::111122223333:role/task-role",
                        ecsTrustPolicyFor("lambda.amazonaws.com"))));

        assertTrue(credentials.refresh(rotated.relativeUri()).isEmpty());
        assertTrue(credentials.current(rotated.relativeUri()).isEmpty());
        verify(iamService).revokeEcsTaskRoleSession(taskArn);
        verify(iamService, never()).revokeEcsTaskRoleSessionGeneration(
                taskArn, first.credentials().accessKeyId());
    }

    @Test
    void invalidTimingRevokesEveryOverlappingGeneration() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-invalid-timing";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        when(config.services().ecs().taskRoleCredentialsTtlSeconds()).thenReturn(120);
        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(30);
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(taskArn, roleArn, "us-east-1")
                .orElseThrow();
        SessionCredential firstActive = activeFor(first);
        when(iamService.resolveEcsTaskRoleSessionByPath(first.relativeUri()))
                .thenReturn(Optional.of(firstActive));
        clock.advanceSeconds(91);
        credentials.refreshTaskIfNeeded(taskArn).orElseThrow();

        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(-1);
        assertTrue(credentials.refreshTaskIfNeeded(taskArn).isEmpty());
        verify(iamService).revokeEcsTaskRoleSession(taskArn);
    }

    @Test
    void revocationRemovesEndpointAndSession() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-c";
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(
                taskArn, "arn:aws:iam::111122223333:role/task-role", "us-east-1").orElseThrow();

        credentials.revokeTask(taskArn);

        assertTrue(credentials.current(first.relativeUri()).isEmpty());
        verify(iamService).revokeEcsTaskRoleSession(taskArn);
    }

    @Test
    void revokeAllRemovesEveryTaskEndpointAndSession() {
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-d",
                "arn:aws:iam::111122223333:role/task-role", "us-east-1").orElseThrow();
        EcsTaskRoleCredentials.IssuedCredentials second = credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-e",
                "arn:aws:iam::111122223333:role/task-role", "us-east-1").orElseThrow();

        credentials.revokeAll();

        assertTrue(credentials.current(first.relativeUri()).isEmpty());
        assertTrue(credentials.current(second.relativeUri()).isEmpty());
        verify(iamService).revokeEcsTaskRoleSession(first.taskArn());
        verify(iamService).revokeEcsTaskRoleSession(second.taskArn());
    }

    @Test
    void linkLocalTaskAllocationsReserveMetadataAddressAndStartAtThree() {
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        EcsTaskRoleCredentials.IssuedCredentials first = credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-link-a", roleArn,
                "us-east-1").orElseThrow();
        EcsTaskRoleCredentials.IssuedCredentials second = credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-link-b", roleArn,
                "us-east-1").orElseThrow();

        assertEquals("169.254.170.3", credentials.linkLocalIp(first.taskArn(), "app").orElseThrow());
        assertEquals("169.254.170.4", credentials.linkLocalIp(second.taskArn(), "app").orElseThrow());
    }

    @Test
    void revokedAndExpiredLeasesKeepNetworkReservationsUntilDockerRemoval() {
        String roleArn = "arn:aws:iam::111122223333:role/task-role";
        String firstTask = "arn:aws:ecs:us-east-1:111122223333:task/default/reserved";
        var first = credentials.issue(firstTask, roleArn, "us-east-1").orElseThrow();
        assertEquals("169.254.170.3", credentials.linkLocalIp(firstTask, "app").orElseThrow());
        // Missing IAM session models expiry/revocation without a Docker stop.
        assertTrue(credentials.current(first.relativeUri()).isEmpty());
        credentials.revokeTask(firstTask);
        String nextTask = "arn:aws:ecs:us-east-1:111122223333:task/default/next";
        credentials.issue(nextTask, roleArn, "us-east-1").orElseThrow();
        for (int i = 0; i < 251; i++) {
            assertTrue(credentials.linkLocalIp(nextTask, "app-" + i).isPresent());
        }
        assertTrue(credentials.linkLocalIp(nextTask, "exhausted").isEmpty());
        credentials.releaseTaskNetwork(firstTask);
        assertEquals("169.254.170.3", credentials.linkLocalIp(nextTask, "after-removal").orElseThrow());
    }

    @Test
    void rejectsNonCanonicalTaskAndRoleArns() {
        assertTrue(credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/",
                "arn:aws:iam::111122223333:role/task-role", "us-east-1").isEmpty());
        assertTrue(credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-f",
                "arn:aws:iam::111122223333:role/other/task-role", "us-east-1").isEmpty());
    }

    @Test
    void rejectsRoleWhenTrustPolicyIsForLambdaOnly() {
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(
                new IamRole("AROAEXAMPLE", "task-role", "/", "arn:aws:iam::111122223333:role/task-role",
                        ecsTrustPolicyFor("lambda.amazonaws.com"))));

        assertTrue(credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-lambda",
                "arn:aws:iam::111122223333:role/task-role", "us-east-1").isEmpty());
        verify(iamService, never()).registerEcsTaskRoleSession(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), anyString());
    }

    @Test
    void explicitTrustDenyOverridesEcsAllow() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """;
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(
                new IamRole("AROAEXAMPLE", "task-role", "/", "arn:aws:iam::111122223333:role/task-role", policy)));

        assertTrue(credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-denied",
                "arn:aws:iam::111122223333:role/task-role", "us-east-1").isEmpty());
    }

    @Test
    void conditionedTrustStatementFailsClosed() {
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"111122223333"}}}]}
                """;
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(
                new IamRole("AROAEXAMPLE", "task-role", "/", "arn:aws:iam::111122223333:role/task-role", policy)));

        assertTrue(credentials.issue(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-conditioned",
                "arn:aws:iam::111122223333:role/task-role", "us-east-1").isEmpty());
    }

    @Test
    void unsupportedTrustStatementsFailClosedEvenWithAnEcsAllow() {
        String[] unsupportedPolicies = {
                """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole",
                   "Condition":{"StringEquals":{"aws:SourceAccount":"111122223333"}}}]}
                """,
                """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","NotPrincipal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """,
                """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}, { }]}
                """,
                """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"},
                  {"Effect":"Audit","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """
        };

        for (int i = 0; i < unsupportedPolicies.length; i++) {
            when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.of(
                    new IamRole("AROAEXAMPLE", "task-role", "/",
                            "arn:aws:iam::111122223333:role/task-role", unsupportedPolicies[i])));
            assertTrue(credentials.issue(
                    "arn:aws:ecs:us-east-1:111122223333:task/default/task-unsupported-" + i,
                    "arn:aws:iam::111122223333:role/task-role", "us-east-1").isEmpty());
        }
        verify(iamService, never()).registerEcsTaskRoleSession(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), anyString());
    }

    @Test
    void rejectsNegativeOrAtLeastTtlRefreshWindow() {
        String taskArn = "arn:aws:ecs:us-east-1:111122223333:task/default/task-invalid-window";
        String roleArn = "arn:aws:iam::111122223333:role/task-role";

        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(-1);
        assertTrue(credentials.issue(taskArn, roleArn, "us-east-1").isEmpty());

        when(config.services().ecs().taskRoleCredentialsRefreshWindowSeconds()).thenReturn(3600);
        assertTrue(credentials.issue(taskArn, roleArn, "us-east-1").isEmpty());
        verify(iamService, never()).registerEcsTaskRoleSession(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), anyString());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private static String ecsTrustPolicyFor(String servicePrincipal) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"%s"},"Action":"sts:AssumeRole"}]}
                """.formatted(servicePrincipal);
    }
}
