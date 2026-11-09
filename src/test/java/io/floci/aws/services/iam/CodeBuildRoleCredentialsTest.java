package io.floci.aws.services.iam;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.codebuild.CodeBuildRoleCredentials;
import io.floci.aws.services.ecs.container.EcsCredentialsProxy;
import io.floci.aws.services.ecs.container.EcsTaskLinkLocalAddresses;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentials;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentialsServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A build's session against a real {@link IamService}, as {@code EcsTaskRoleCredentialsTest} reads
 * a task's: the caller STS answers for it, and what its end leaves.
 */
class CodeBuildRoleCredentialsTest {

    private static final String ACCOUNT = "000000000000";
    private static final String BUILD_ARN = "arn:aws:codebuild:us-east-1:" + ACCOUNT + ":build/project:7";
    private static final String RELATIVE_URI = "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI";

    private IamService iamService;
    private EcsTaskRoleCredentials sessions;
    private EcsCredentialsProxy proxy;
    private CodeBuildRoleCredentials credentials;
    private String roleArn;

    @BeforeEach
    void setUp() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.now());
        iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT), false, clock);
        roleArn = iamService.createRole("build-role", "/service-role/", "{}", null, 3600, null).getArn();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        sessions = new EcsTaskRoleCredentials(iamService, config);
        EcsTaskRoleCredentialsServer server = mock(EcsTaskRoleCredentialsServer.class);
        when(server.start()).thenReturn(CompletableFuture.completedFuture(null));
        proxy = mock(EcsCredentialsProxy.class);
        credentials = new CodeBuildRoleCredentials(sessions, server, proxy, new EcsTaskLinkLocalAddresses());
    }

    @Test
    void theBuildsCallerIsItsRolesCodeBuildSession() {
        CodeBuildRoleCredentials.BuildCredentials handed =
                credentials.provision(BUILD_ARN, "project:7", roleArn, ACCOUNT, Optional.of("builds"));

        String path = handed.env().get(RELATIVE_URI);
        String accessKeyId = sessions.resolveByPath(path, Instant.now()).orElseThrow().getAccessKeyId();
        assertEquals(Optional.of("arn:aws:sts::" + ACCOUNT + ":assumed-role/build-role/AWSCodeBuild-7"),
                iamService.resolveCallerArn(accessKeyId));
        assertTrue(handed.linkLocalAddress().startsWith("169.254."), handed.linkLocalAddress());
        verify(proxy).ensureProxyOn("builds");
    }

    @Test
    void theBuildsEndRevokesItsSession() {
        CodeBuildRoleCredentials.BuildCredentials handed =
                credentials.provision(BUILD_ARN, "project:7", roleArn, ACCOUNT, Optional.of("builds"));
        String path = handed.env().get(RELATIVE_URI);
        String accessKeyId = sessions.resolveByPath(path, Instant.now()).orElseThrow().getAccessKeyId();

        credentials.revoke(BUILD_ARN);

        assertTrue(sessions.resolveByPath(path, Instant.now()).isEmpty());
        assertTrue(iamService.resolveCallerArn(accessKeyId).isEmpty());
    }

    @Test
    void onTheDefaultBridgeTheSessionIsHandedAsKeysAndNoEndpointIsReadied() {
        CodeBuildRoleCredentials.BuildCredentials handed =
                credentials.provision(BUILD_ARN, "project:7", roleArn, ACCOUNT, Optional.of("bridge"));

        assertEquals(Optional.of("arn:aws:sts::" + ACCOUNT + ":assumed-role/build-role/AWSCodeBuild-7"),
                iamService.resolveCallerArn(handed.env().get("AWS_ACCESS_KEY_ID")));
        assertEquals(null, handed.linkLocalAddress());
        verify(proxy, never()).ensureProxyOn("bridge");
    }

    @Test
    void aRoleNotFoundMintsNothing() {
        String missing = "arn:aws:iam::" + ACCOUNT + ":role/missing";

        CodeBuildRoleCredentials.RoleNotFoundException refused = assertThrows(
                CodeBuildRoleCredentials.RoleNotFoundException.class,
                () -> credentials.provision(BUILD_ARN, "project:7", missing, ACCOUNT, Optional.of("builds")));

        assertTrue(refused.getMessage().contains(missing), refused.getMessage());
        verify(proxy, never()).ensureProxyOn("builds");
    }
}
