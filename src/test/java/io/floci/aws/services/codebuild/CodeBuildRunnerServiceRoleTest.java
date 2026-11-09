package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.BuildPhase;
import io.floci.aws.services.codebuild.model.Project;
import io.floci.aws.services.codebuild.model.ProjectEnvironment;
import io.floci.aws.services.codebuild.model.ProjectSource;
import io.floci.aws.services.ecs.container.EcsCredentialsProxy;
import io.floci.aws.services.ecs.container.EcsTaskLinkLocalAddresses;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentials;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentialsServer;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A build runs as the role its project names as {@code serviceRole}, handed over the way CodeBuild
 * hands it: on a user-defined network, the container credentials endpoint's relative URI and no
 * keys; on the default bridge, where no container can hold a link-local address, the same session
 * as keys. A build whose role cannot be found never runs a command.
 */
class CodeBuildRunnerServiceRoleTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_NAME = "build-role";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/service-role/" + ROLE_NAME;
    private static final String NETWORK = "builds";
    private static final Set<String> NO_KEYS = Set.of(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_CONTAINER_AUTHORIZATION_TOKEN",
            "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");

    @Test
    void aBuildOnAUserDefinedNetworkIsServedItsRoleAtTheRelativeUriAndHoldsNoKeys() throws Exception {
        Fixture fixture = fixture(Optional.of(NETWORK), true, List.of());

        fixture.run();

        List<String> env = fixture.env();
        assertEquals(List.of(), env.stream().filter(e -> NO_KEYS.contains(name(e))).toList(),
                "the build's environment holds credentials of its own: " + env);
        String relativeUri = value(env, "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
                .orElseThrow(() -> new AssertionError("no AWS_CONTAINER_CREDENTIALS_RELATIVE_URI in " + env));
        assertTrue(relativeUri.startsWith("/"), relativeUri);
        assertTrue(!relativeUri.contains("://"), relativeUri);
        verify(fixture.specBuilder()).withLinkLocalIp(startsWith("169.254."));

        SessionCredential session = fixture.issuedSession();
        assertEquals(ROLE_ARN, session.getRoleArn());
        assertEquals("AWSCodeBuild-7", session.getRoleSessionName());
        assertEquals(ACCOUNT, session.getOriginAccountId());
    }

    @Test
    void aBuildOnTheDefaultBridgeHoldsItsRolesSessionAsKeys() throws Exception {
        Fixture fixture = fixture(Optional.empty(), true, List.of());

        fixture.run();

        List<String> env = fixture.env();
        SessionCredential session = fixture.issuedSession();
        assertEquals(Optional.of(session.getAccessKeyId()), value(env, "AWS_ACCESS_KEY_ID"), env::toString);
        assertEquals(Optional.of(session.getSecretAccessKey()), value(env, "AWS_SECRET_ACCESS_KEY"), env::toString);
        assertEquals(Optional.of(session.getSessionToken()), value(env, "AWS_SESSION_TOKEN"), env::toString);
        assertTrue(session.getAccessKeyId().startsWith("ASIA"), session.getAccessKeyId());
        assertEquals(ROLE_ARN, session.getRoleArn());
        assertEquals("AWSCodeBuild-7", session.getRoleSessionName());
        assertTrue(value(env, "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI").isEmpty(), env::toString);
        verify(fixture.specBuilder(), never()).withLinkLocalIp(anyString());
    }

    @Test
    void credentialVariablesTheProjectSetsStillReachTheBuild() throws Exception {
        Fixture fixture = fixture(Optional.of(NETWORK), true, List.of(
                Map.of("name", "AWS_ACCESS_KEY_ID", "value", "project-key"),
                Map.of("name", "AWS_SESSION_TOKEN", "value", "project-token")));

        fixture.run();

        List<String> env = fixture.env();
        assertEquals(Optional.of("project-key"), value(env, "AWS_ACCESS_KEY_ID"), env::toString);
        assertEquals(Optional.of("project-token"), value(env, "AWS_SESSION_TOKEN"), env::toString);
    }

    @Test
    void aBuildWhoseRoleIsNotFoundFailsBeforeItsFirstCommand() throws Exception {
        Fixture fixture = fixture(Optional.of(NETWORK), false, List.of());

        fixture.run();

        Build build = fixture.build();
        assertEquals("FAILED", build.getBuildStatus());
        BuildPhase failed = build.getPhases().stream()
                .filter(phase -> "FAILED".equals(phase.getPhaseStatus()))
                .findFirst().orElseThrow(() -> new AssertionError("no failed phase in " + phases(build)));
        assertTrue(String.valueOf(failed.getContexts().getFirst().get("message")).contains(ROLE_ARN),
                () -> failed.getPhaseType() + " " + failed.getContexts());
        verify(fixture.lifecycle(), never()).createAndStart(any());
        verify(fixture.runner(), never())
                .runPhase(anyString(), anyString(), anyList(), anyList(), anyInt(), any(AtomicBoolean.class));
    }

    private static List<String> phases(Build build) {
        return build.getPhases().stream().map(p -> p.getPhaseType() + "=" + p.getPhaseStatus()).toList();
    }

    private static String name(String entry) {
        int equals = entry.indexOf('=');
        return equals < 0 ? entry : entry.substring(0, equals);
    }

    private static Optional<String> value(List<String> env, String name) {
        Optional<String> found = Optional.empty();
        for (String entry : env) {
            if (name.equals(name(entry))) {
                found = Optional.of(entry.substring(name.length() + 1));
            }
        }
        return found;
    }

    private Fixture fixture(Optional<String> network, boolean roleExists, List<Map<String, String>> projectVariables) {
        DockerClient docker = mock(DockerClient.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(network);
        when(specBuilder.build()).thenReturn(new ContainerSpec("alpine:latest"));

        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("container", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        when(logStreamer.attach(eq("container"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mock(Closeable.class));

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().dockerNetwork()).thenReturn(network);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.port()).thenReturn(4566);

        IamService iam = mock(IamService.class);
        IamRole role = new IamRole();
        role.setRoleId("AROABUILDROLE");
        role.setRoleName(ROLE_NAME);
        role.setArn(ROLE_ARN);
        when(iam.findRole(ACCOUNT, ROLE_NAME)).thenReturn(roleExists ? Optional.of(role) : Optional.empty());
        EcsTaskRoleCredentials credentials = new EcsTaskRoleCredentials(iam, config);
        EcsTaskRoleCredentialsServer server = mock(EcsTaskRoleCredentialsServer.class);
        when(server.start()).thenReturn(CompletableFuture.completedFuture(null));
        EcsCredentialsProxy proxy = mock(EcsCredentialsProxy.class);

        CodeBuildRunner runner = spy(new CodeBuildRunner(docker, containerBuilder, lifecycle, logStreamer,
                null, null, null, config, mock(ContainerDetector.class), regionResolver, endpoint,
                new CodeBuildRoleCredentials(credentials, server, proxy, new EcsTaskLinkLocalAddresses())));
        doReturn(CodeBuildRunner.PhaseResult.ofSuccess()).when(runner)
                .runPhase(anyString(), anyString(), anyList(), anyList(), anyInt(), any(AtomicBoolean.class));

        Build build = new Build();
        build.setId("project:7");
        build.setArn("arn:aws:codebuild:us-east-1:" + ACCOUNT + ":build/project:7");
        build.setBuildNumber(7L);
        build.setPhases(new ArrayList<>());

        Project project = new Project();
        project.setName("project");
        project.setArn("arn:aws:codebuild:us-east-1:" + ACCOUNT + ":project/project");
        project.setServiceRole(ROLE_ARN);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        source.setBuildspec("version: 0.2\nphases:\n  build:\n    commands:\n      - aws sts get-caller-identity\n");
        project.setSource(source);
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setImage("alpine:latest");
        environment.setEnvironmentVariables(projectVariables);
        project.setEnvironment(environment);

        return new Fixture(runner, build, project, specBuilder, lifecycle, iam);
    }

    private record Fixture(CodeBuildRunner runner, Build build, Project project, ContainerBuilder.Builder specBuilder,
                           ContainerLifecycleManager lifecycle, IamService iam) {

        void run() throws InterruptedException {
            runner.startBuild("us-east-1", build, project, null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Boolean.TRUE.equals(build.getBuildComplete()) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Boolean.TRUE.equals(build.getBuildComplete()), "build did not finish: " + phases(build));
        }

        @SuppressWarnings("unchecked")
        List<String> env() {
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(specBuilder, atLeastOnce()).withEnv(captor.capture());
            return captor.getValue();
        }

        /** The one session the build's start registered with IAM. */
        SessionCredential issuedSession() {
            ArgumentCaptor<SessionCredential> captor = ArgumentCaptor.forClass(SessionCredential.class);
            verify(iam).registerEcsTaskRoleSession(captor.capture());
            return captor.getValue();
        }
    }
}
