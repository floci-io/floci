package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Uses a real {@link IamService} backed by in-memory storage, the same way
 * {@code IamServiceTest} and {@code Ec2InstanceCredentialsTest} do: the behaviour under test is
 * the interaction between the two, not either one in isolation.
 */
class EcsTaskRoleCredentialsTest {

    private static final String TASK_ARN = "arn:aws:ecs:us-east-1:000000000000:task/cluster/abc123";
    private static final String OTHER_TASK_ARN = "arn:aws:ecs:us-east-1:000000000000:task/cluster/def456";

    private IamService iamService;
    private EcsTaskRoleCredentials credentials;
    private String roleArn;
    private final Clock clock = mock(Clock.class);
    private InMemoryStorage<String, SessionCredential> sessions;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        sessions = new InMemoryStorage<>();
        iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                sessions, new RegionResolver("us-east-1", "000000000000"), false, clock);
        roleArn = iamService.createRole("ecsTaskRole", "/", "{}", null, 3600, null).getArn();

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        credentials = new EcsTaskRoleCredentials(iamService, config);
    }

    @Test
    void issueMintsAResolvableSessionScopedToTheTask() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();

        assertTrue(path.matches("^/v2/credentials/[0-9a-f-]{36}$"), path);
        SessionCredential session = credentials.resolveByPath(path, now).orElseThrow();
        assertEquals(roleArn, session.getRoleArn());
        assertEquals(TASK_ARN, session.getEcsTaskArn());
        assertEquals("000000000000", session.getOriginAccountId());
        assertTrue(session.getAccessKeyId().startsWith("ASIA"));
        assertEquals(now.plusSeconds(21600), session.getExpiration());
        // Findable through the normal SigV4 path too, the same way ECS SDKs authenticate with it.
        assertTrue(iamService.findSecretKey(session.getAccessKeyId(), session.getSessionToken()).isPresent());
    }

    @Test
    void issueForUnknownRoleMintsNothing() {
        assertTrue(credentials.issue(TASK_ARN, "arn:aws:iam::000000000000:role/does-not-exist",
                "000000000000", clock.instant()).isEmpty());
    }

    @Test
    void reissuingWellBeforeExpiryReturnsTheSamePath() {
        Instant now = clock.instant();
        String first = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        String second = credentials.issue(TASK_ARN, roleArn, "000000000000", now.plusSeconds(10)).orElseThrow();

        assertEquals(first, second);
    }

    @Test
    void reissuingInsideTheRotationWindowKeepsThePathAndUnexpiredCachedSession() {
        Instant now = clock.instant();
        String first = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential firstSession = credentials.resolveByPath(first, now).orElseThrow();

        // Inside the 300-second rotation window, so a fresh generation is minted.
        Instant nearExpiry = firstSession.getExpiration().minusSeconds(60);
        at(nearExpiry);
        String second = credentials.issue(TASK_ARN, roleArn, "000000000000", nearExpiry).orElseThrow();

        assertEquals(first, second);
        SessionCredential refreshed = credentials.resolveByPath(first, nearExpiry).orElseThrow();
        assertFalse(firstSession.getAccessKeyId().equals(refreshed.getAccessKeyId()));
        assertTrue(iamService.findSecretKey(firstSession.getAccessKeyId(), firstSession.getSessionToken()).isPresent());
        assertEquals(now.plusSeconds(21600), firstSession.getExpiration());
    }

    @Test
    void separateTasksGetIndependentSessions() {
        Instant now = clock.instant();
        String taskAPath = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        String taskBPath = credentials.issue(OTHER_TASK_ARN, roleArn, "000000000000", now).orElseThrow();

        assertNotEquals(taskAPath, taskBPath);
        assertEquals(TASK_ARN, credentials.resolveByPath(taskAPath, now).orElseThrow().getEcsTaskArn());
        assertEquals(OTHER_TASK_ARN, credentials.resolveByPath(taskBPath, now).orElseThrow().getEcsTaskArn());

        credentials.revoke(TASK_ARN);
        assertTrue(credentials.resolveByPath(taskAPath, now).isEmpty());
        assertTrue(credentials.resolveByPath(taskBPath, now).isPresent(), "revoking one task must not touch another");
    }

    @Test
    void revokeStopsTheAccessKeyFromAuthenticating() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential session = credentials.resolveByPath(path, now).orElseThrow();

        credentials.revoke(TASK_ARN);

        assertTrue(credentials.resolveByPath(path, now).isEmpty());
        assertTrue(iamService.findSecretKey(session.getAccessKeyId(), session.getSessionToken()).isEmpty());
    }

    @Test
    void revokingAnUnknownTaskIsANoOp() {
        credentials.revoke("arn:aws:ecs:us-east-1:000000000000:task/cluster/never-issued");
    }

    @Test
    void expiredCredentialsAreRenewedAtThePublishedPath() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential original = credentials.resolveByPath(path, now).orElseThrow();
        Instant later = at(now.plusSeconds(21601));

        Optional<SessionCredential> pastExpiry = credentials.resolveByPath(path, later);

        assertTrue(pastExpiry.isPresent());
        assertEquals(later.plusSeconds(21600), pastExpiry.orElseThrow().getExpiration());
        assertTrue(iamService.findSecretKey(original.getAccessKeyId(), original.getSessionToken()).isEmpty());
        assertTrue(sessions.get(original.getAccessKeyId()).isEmpty());
    }

    @Test
    void resolveByPathForUnknownPathIsEmpty() {
        assertTrue(credentials.resolveByPath("/v2/credentials/does-not-exist", clock.instant()).isEmpty());
        assertTrue(credentials.resolveByPath(null, clock.instant()).isEmpty());
    }

    @Test
    void endpointReadsRenewBeforeExpiryAndStopRevokesAllLiveGenerations() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential first = credentials.resolveByPath(path, now).orElseThrow();
        Instant nearExpiry = at(first.getExpiration().minusSeconds(60));
        SessionCredential second = credentials.resolveByPath(path, nearExpiry).orElseThrow();
        assertFalse(first.getAccessKeyId().equals(second.getAccessKeyId()));
        assertTrue(iamService.findSecretKey(first.getAccessKeyId(), first.getSessionToken()).isPresent());
        assertTrue(iamService.findSecretKey(second.getAccessKeyId(), second.getSessionToken()).isPresent());
        credentials.revoke(TASK_ARN);
        assertTrue(credentials.resolveByPath(path, nearExpiry).isEmpty());
        assertTrue(iamService.findSecretKey(first.getAccessKeyId(), first.getSessionToken()).isEmpty());
        assertTrue(iamService.findSecretKey(second.getAccessKeyId(), second.getSessionToken()).isEmpty());
    }

    @Test
    void successiveRenewalsDiscardExpiredGenerationsWithoutExtendingTheirLifetime() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential current = credentials.resolveByPath(path, now).orElseThrow();
        for (int i = 0; i < 3; i++) {
            SessionCredential previous = current;
            current = credentials.resolveByPath(path, at(previous.getExpiration().minusSeconds(60))).orElseThrow();
            assertFalse(previous.getAccessKeyId().equals(current.getAccessKeyId()));
            assertEquals(2, sessions.scan(key -> true).size());
            credentials.resolveByPath(path, at(previous.getExpiration().plusSeconds(1))).orElseThrow();
            assertTrue(iamService.findSecretKey(previous.getAccessKeyId(), previous.getSessionToken()).isEmpty());
            assertTrue(iamService.findSecretKey(current.getAccessKeyId(), current.getSessionToken()).isPresent());
            assertEquals(1, sessions.scan(key -> true).size());
        }
        credentials.revoke(TASK_ARN);
        assertTrue(sessions.scan(key -> true).isEmpty());
    }

    @Test
    void shortTtlDoesNotRotateEveryRead() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(60L);
        credentials = new EcsTaskRoleCredentials(iamService, config);
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential first = credentials.resolveByPath(path, now).orElseThrow();
        assertEquals(path, credentials.issue(TASK_ARN, roleArn, "000000000000", at(now.plusSeconds(10))).orElseThrow());
        assertTrue(first.getAccessKeyId().equals(
                credentials.resolveByPath(path, clock.instant()).orElseThrow().getAccessKeyId()));
        SessionCredential second = credentials.resolveByPath(path, at(now.plusSeconds(59))).orElseThrow();
        assertFalse(first.getAccessKeyId().equals(second.getAccessKeyId()));
        assertTrue(iamService.findSecretKey(first.getAccessKeyId(), first.getSessionToken()).isPresent());
        assertTrue(second.getAccessKeyId().equals(
                credentials.resolveByPath(path, at(now.plusSeconds(61))).orElseThrow().getAccessKeyId()));
        assertTrue(iamService.findSecretKey(first.getAccessKeyId(), first.getSessionToken()).isEmpty());
    }

    @Test
    void oneSecondTtlReusesTheSessionUntilItExpires() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(1L);
        credentials = new EcsTaskRoleCredentials(iamService, config);
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential first = sessions.scan(key -> true).getFirst();
        for (int i = 0; i < 10; i++) {
            SessionCredential current = credentials.resolveByPath(path, at(now.plusMillis(i * 90))).orElseThrow();
            assertTrue(first.getAccessKeyId().equals(current.getAccessKeyId()));
            assertEquals(1, sessions.scan(key -> true).size());
        }
        SessionCredential renewed = credentials.resolveByPath(path, at(now.plusSeconds(1))).orElseThrow();
        assertFalse(first.getAccessKeyId().equals(renewed.getAccessKeyId()));
        assertTrue(iamService.findSecretKey(first.getAccessKeyId(), first.getSessionToken()).isEmpty());
        assertEquals(1, sessions.scan(key -> true).size());
    }

    @Test
    void deletingTheRoleRevokesThePathAndEveryCachedGeneration() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential first = credentials.resolveByPath(path, now).orElseThrow();
        credentials.issue(TASK_ARN, roleArn, "000000000000", at(first.getExpiration().minusSeconds(60))).orElseThrow();
        iamService.deleteRole("ecsTaskRole");
        assertTrue(credentials.resolveByPath(path, clock.instant()).isEmpty());
        assertTrue(sessions.scan(key -> true).isEmpty());
    }

    @Test
    void recreatingTheRoleCannotRenewAnOldTasksCredentials() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        iamService.deleteRole("ecsTaskRole");
        iamService.createRole("ecsTaskRole", "/", "{}", null, 3600, null);
        assertTrue(credentials.resolveByPath(path, now).isEmpty());
        assertTrue(sessions.scan(key -> true).isEmpty());
    }

    @Test
    void wrongAccountRoleDoesNotMintASession() {
        assertTrue(credentials.issue(TASK_ARN, roleArn, "111111111111", clock.instant()).isEmpty());
        assertTrue(sessions.scan(key -> true).isEmpty());
    }

    @Test
    void changingToAnUnknownRoleRevokesTheOldAssignment() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        assertTrue(credentials.issue(TASK_ARN, "arn:aws:iam::000000000000:role/missing", "000000000000", now).isEmpty());
        assertTrue(credentials.resolveByPath(path, now).isEmpty());
        assertTrue(sessions.scan(key -> true).isEmpty());
    }

    @Test
    void changingTheAccountRevokesTheOldAssignment() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        assertTrue(credentials.issue(TASK_ARN, roleArn, "111111111111", now).isEmpty());
        assertTrue(credentials.resolveByPath(path, now).isEmpty());
        assertTrue(sessions.scan(key -> true).isEmpty());
    }

    @Test
    void changingToAnotherRoleReplacesThePathAndRevokesCachedGenerations() {
        Instant now = clock.instant();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential first = credentials.resolveByPath(path, now).orElseThrow();
        Instant nearExpiry = at(first.getExpiration().minusSeconds(60));
        credentials.resolveByPath(path, nearExpiry).orElseThrow();
        String nextRole = iamService.createRole("nextRole", "/", "{}", null, 3600, null).getArn();
        String nextPath = credentials.issue(TASK_ARN, nextRole, "000000000000", nearExpiry).orElseThrow();
        assertNotEquals(path, nextPath);
        assertTrue(credentials.resolveByPath(path, nearExpiry).isEmpty());
        assertEquals(nextRole, credentials.resolveByPath(nextPath, nearExpiry).orElseThrow().getRoleArn());
        assertEquals(1, sessions.scan(key -> true).size());
    }

    private Instant at(Instant now) {
        when(clock.instant()).thenReturn(now);
        return now;
    }
}
