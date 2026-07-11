package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsContainerManagerSecretsTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private SsmService ssmService;
    private SecretsManagerService secretsManagerService;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        ssmService = mock(SsmService.class);
        secretsManagerService = mock(SecretsManagerService.class);

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, ssmService, secretsManagerService);
    }

    @Test
    void resolvesSsmAndSecretsManagerSecretsAndKeepsOverridesLast() {
        String secretArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:db-password-AbCdEf";
        when(ssmService.getParameter("/app/config", "us-east-1"))
                .thenReturn(new Parameter("/app/config", "from-ssm", "String"));
        when(secretsManagerService.getSecretValue(secretArn, null, null, "us-east-1"))
                .thenReturn(secretVersion("from-secrets-manager"));

        ContainerDefinition app = containerDef("app", List.of(
                new Secret("CONFIG", "/app/config"),
                new Secret("PASSWORD", secretArn)));
        app.setEnvironment(List.of(new KeyValuePair("PASSWORD", "plain-env")));

        ContainerOverride override = new ContainerOverride();
        override.setName("app");
        override.setEnvironment(List.of(new KeyValuePair("PASSWORD", "override")));

        manager.startTask(task(), taskDef(app), List.of(override), "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(envCaptor.capture());

        List<String> env = envCaptor.getValue();
        assertTrue(env.contains("CONFIG=from-ssm"));
        assertTrue(env.contains("PASSWORD=override"));
    }

    @Test
    void ssmArnKeepsLeadingSlashInParameterName() {
        String ssmArn = "arn:aws:ssm:us-east-1:000000000000:parameter/foo/bar";
        when(ssmService.getParameter("/foo/bar", "us-east-1"))
                .thenReturn(new Parameter("/foo/bar", "path-value", "String"));

        manager.startTask(task(), taskDef(containerDef("app", List.of(new Secret("PATH_VALUE", ssmArn)))),
                List.of(), "us-east-1");

        verify(ssmService).getParameter("/foo/bar", "us-east-1");
    }

    @Test
    void crossRegionArnResolvesAgainstArnRegionNotTaskRegion() {
        String ssmArn = "arn:aws:ssm:eu-west-1:000000000000:parameter/app/token";
        String secretArn = "arn:aws:secretsmanager:eu-west-1:000000000000:secret:db-AbCdEf";
        when(ssmService.getParameter("/app/token", "eu-west-1"))
                .thenReturn(new Parameter("/app/token", "ssm-eu", "String"));
        when(secretsManagerService.getSecretValue(secretArn, null, null, "eu-west-1"))
                .thenReturn(secretVersion("sm-eu"));

        // Task runs in us-east-1, but both references are eu-west-1 ARNs.
        manager.startTask(task(), taskDef(containerDef("app", List.of(
                new Secret("TOKEN", ssmArn),
                new Secret("PASSWORD", secretArn)))),
                List.of(), "us-east-1");

        verify(ssmService).getParameter("/app/token", "eu-west-1");
        verify(secretsManagerService).getSecretValue(secretArn, null, null, "eu-west-1");
    }

    @Test
    void unresolvedSecretPropagatesAndNoContainerIsCreated() {
        ContainerDefinition first = containerDef("first", List.of(new Secret("OK", "/ok")));
        ContainerDefinition second = containerDef("second", List.of(new Secret("MISSING", "/missing")));
        when(ssmService.getParameter("/ok", "us-east-1"))
                .thenReturn(new Parameter("/ok", "ok", "String"));
        when(ssmService.getParameter("/missing", "us-east-1"))
                .thenThrow(new AwsException("ParameterNotFound", "Parameter /missing not found.", 400));

        AwsException thrown = assertThrows(AwsException.class,
                () -> manager.startTask(task(), taskDef(first, second), List.of(), "us-east-1"));

        assertTrue(thrown.getMessage().contains("ResourceInitializationError"));
        assertTrue(thrown.getMessage().contains("/missing"));
        verify(containerBuilder, never()).newContainer(anyString());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void binarySecretWithNoStringValueFailsLaunch() {
        String secretArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:bin-AbCdEf";
        // A SecretBinary-only secret round-trips with a null SecretString. Injecting
        // "BIN=null" would be wrong, so the launch must fail like real AWS rather than
        // start the container with a missing value.
        when(secretsManagerService.getSecretValue(secretArn, null, null, "us-east-1"))
                .thenReturn(new SecretVersion());

        AwsException thrown = assertThrows(AwsException.class,
                () -> manager.startTask(task(),
                        taskDef(containerDef("app", List.of(new Secret("BIN", secretArn)))),
                        List.of(), "us-east-1"));

        assertTrue(thrown.getMessage().contains("ResourceInitializationError"));
        assertTrue(thrown.getMessage().contains(secretArn));
        verify(containerBuilder, never()).newContainer(anyString());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    private static ContainerDefinition containerDef(String name, List<Secret> secrets) {
        ContainerDefinition def = new ContainerDefinition();
        def.setName(name);
        def.setImage(name + ":latest");
        def.setSecrets(secrets);
        return def;
    }

    private static TaskDefinition taskDef(ContainerDefinition... containers) {
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(containers));
        return taskDef;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        return task;
    }

    private static SecretVersion secretVersion(String value) {
        SecretVersion version = new SecretVersion();
        version.setSecretString(value);
        return version;
    }
}
