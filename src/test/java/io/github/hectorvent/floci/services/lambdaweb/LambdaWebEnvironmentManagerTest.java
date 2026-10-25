package io.github.hectorvent.floci.services.lambdaweb;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.launcher.ImageResolver;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.StartFailure;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LambdaWebEnvironmentManagerTest {

    private static final List<String> BASE = List.of("--expose-gc", "--max-http-header-size", "135168",
            "--no-experimental-require-module");
    private static final List<String> DEFAULT_HEAP = List.of("--max-semi-space-size=102", "--max-old-space-size=1024");

    private final LambdaWebExecutionRole executionRole = mock(LambdaWebExecutionRole.class);
    private final LambdaExecutionRoleCredentials credentials = mock(LambdaExecutionRoleCredentials.class);
    private final LambdaWebMetadataServer metadata = mock(LambdaWebMetadataServer.class);
    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);

    @BeforeEach
    void trustTheRole() {
        when(executionRole.assumableFor(any(), any(), any())).thenReturn(true);
    }

    @Test
    void heapFlagsInNodeOptionsReplaceBothDefaultHeapFlags() {
        assertEquals(flags(DEFAULT_HEAP), LambdaWebEnvironmentManager.nodeFlags(null));
        assertEquals(flags(List.of("--max-old-space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536"));
        assertEquals(flags(List.of("--max-semi-space-size=64")),
                LambdaWebEnvironmentManager.nodeFlags("--max-semi-space-size=64"));
        assertEquals(flags(List.of("--max-old-space-size=1536", "--max-semi-space-size=64")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536 --max-semi-space-size=64"));
        assertEquals(flags(List.of("--max-old-space-size=1536", "--max-old-space-size=1200")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536 --max-old-space-size=1200"));
        assertEquals(flags(List.of("--max_old_space_size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max_old_space_size=1536"));
        assertEquals(flags(List.of("--max-old_space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old_space-size=1536"));
        assertEquals(flags(List.of("--max-old-space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--expose-gc --max-old-space-size=1536 --title=y"));
        assertEquals(flags(List.of("--max-semi-space-size=32")),
                LambdaWebEnvironmentManager.nodeFlags("--expose-gc   --max-semi-space-size=32"));
        assertEquals(flags(List.of("--max-old-space-size")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size 1536"));
        assertEquals(flags(List.of("--max-old-space-size=\"1536\"")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=\"1536\""));
    }

    @Test
    void otherFlagsInNodeOptionsKeepTheDefaults() {
        List<String> defaults = flags(DEFAULT_HEAP);
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--expose-gc"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--max-http-header-size=1000"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--require ./pre.js"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("\"--max-old-space-size=1536\""));
    }

    @Test
    void aRoleDeletedAfterTheTrustCheckStartsNoContainer() {
        when(credentials.forRole(any(), any(), any())).thenReturn(Optional.empty());

        StartFailure failure = assertThrows(StartFailure.class, this::acquire);
        assertEquals(LambdaWebErrorPages.CANNOT_ASSUME_ROLE, failure.page());
        verifyNoInteractions(containerBuilder);
    }

    @Test
    void aMetadataServerThatCannotStartLeavesNoSessionOrToken() {
        when(metadata.address()).thenThrow(new IllegalStateException("no port"));

        assertThrows(IllegalStateException.class, this::acquire);
        verifyNoInteractions(credentials, containerBuilder);
        verify(metadata, never()).register(any());
    }

    private void acquire() {
        WebFunction fn = new WebFunction();
        fn.functionName = "fn";
        fn.functionArn = "arn:aws:lambda:us-east-1:000000000000:web-function/fn";
        WebFunctionRevision revision = new WebFunctionRevision();
        revision.revisionId = "1";
        revision.buildConfig.runtime = "nodejs24.x";
        revision.serviceConfig.executionRoleArn = "arn:aws:iam::000000000000:role/web";
        LambdaWebEnvironmentManager manager = new LambdaWebEnvironmentManager(
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), containerBuilder,
                mock(ContainerLifecycleManager.class), mock(DockerClient.class), mock(ImageResolver.class),
                mock(EcrRegistryManager.class), mock(LambdaWebCode.class), mock(ZipExtractor.class),
                mock(LaunchedContainerAwsEnv.class), credentials, executionRole, metadata,
                mock(LambdaWebLogs.class), mock(LambdaWebMetrics.class));
        try {
            manager.acquire("000000000000", "us-east-1", fn, "endpoint", null, revision);
        } finally {
            manager.shutdown();
        }
    }

    private static List<String> flags(List<String> heap) {
        List<String> flags = new ArrayList<>(BASE);
        flags.addAll(heap);
        return flags;
    }

    @Test
    void onlyAnExitSeenBeforeTheRequestStartedAllowsARetry() {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        long started = System.nanoTime();
        assertFalse(environment.exitedBefore(started));

        environment.sawExit(started);

        assertFalse(environment.exitedBefore(started));
        assertFalse(environment.exitedBefore(started - 1));
        assertTrue(environment.exitedBefore(started + 1));
    }

    @Test
    void awaitExitEndsWhenTheExitIsSeen() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        CompletableFuture<Void> waited = environment.awaitExit(Duration.ofMinutes(1));
        assertFalse(waited.isDone());

        environment.sawExit(System.nanoTime());

        waited.get(5, TimeUnit.SECONDS);
    }

    @Test
    void awaitExitGivesUpOnAProcessThatKeepsRunning() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");

        environment.awaitExit(Duration.ofMillis(10)).get(5, TimeUnit.SECONDS);

        assertFalse(environment.exitedBefore(System.nanoTime()));
        environment.sawExit(System.nanoTime());
        assertTrue(environment.exitedBefore(System.nanoTime()));
    }
}
