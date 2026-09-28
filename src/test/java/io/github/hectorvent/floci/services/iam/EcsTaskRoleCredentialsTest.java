package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @BeforeEach
    void setUp() {
        iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"), false);
        roleArn = iamService.createRole("ecsTaskRole", "/", "{}", null, 3600, null).getArn();

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        credentials = new EcsTaskRoleCredentials(iamService, config);
    }

    @Test
    void issueMintsAResolvableSessionScopedToTheTask() {
        Instant now = Instant.now();
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
                "000000000000", Instant.now()).isEmpty());
    }

    @Test
    void reissuingWellBeforeExpiryReturnsTheSamePath() {
        Instant now = Instant.now();
        String first = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        String second = credentials.issue(TASK_ARN, roleArn, "000000000000", now.plusSeconds(10)).orElseThrow();

        assertEquals(first, second);
    }

    @Test
    void reissuingInsideTheRotationWindowMintsAFreshGenerationAndRetiresTheOld() {
        Instant now = Instant.now();
        String first = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();
        SessionCredential firstSession = credentials.resolveByPath(first, now).orElseThrow();

        // Inside the 300-second rotation window, so a fresh generation is minted.
        Instant nearExpiry = firstSession.getExpiration().minusSeconds(60);
        String second = credentials.issue(TASK_ARN, roleArn, "000000000000", nearExpiry).orElseThrow();

        assertNotEquals(first, second);
        assertTrue(credentials.resolveByPath(first, nearExpiry).isEmpty(), "retired generation must stop resolving");
        assertTrue(credentials.resolveByPath(second, nearExpiry).isPresent());
        assertTrue(iamService.findSecretKey(firstSession.getAccessKeyId(), firstSession.getSessionToken()).isEmpty(),
                "the retired generation's access key must stop authenticating");
    }

    @Test
    void separateTasksGetIndependentSessions() {
        Instant now = Instant.now();
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
        Instant now = Instant.now();
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
    void resolveByPathExpiresAndRevokesOnceThePastExpirationIsSeen() {
        Instant now = Instant.now();
        String path = credentials.issue(TASK_ARN, roleArn, "000000000000", now).orElseThrow();

        Optional<SessionCredential> pastExpiry = credentials.resolveByPath(path, now.plusSeconds(21601));

        assertTrue(pastExpiry.isEmpty());
        assertTrue(credentials.resolveByPath(path, now).isEmpty(), "an expired path must not come back");
    }

    @Test
    void resolveByPathForUnknownPathIsEmpty() {
        assertTrue(credentials.resolveByPath("/v2/credentials/does-not-exist", Instant.now()).isEmpty());
        assertTrue(credentials.resolveByPath(null, Instant.now()).isEmpty());
    }
}
