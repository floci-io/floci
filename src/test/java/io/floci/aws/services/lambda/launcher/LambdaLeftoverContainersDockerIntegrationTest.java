package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(LambdaLeftoverContainersDockerIntegrationTest.LeftoversProfile.class)
class LambdaLeftoverContainersDockerIntegrationTest {

    @Inject
    DockerClient docker;
    @Inject
    ContainerBuilder builder;
    @Inject
    ContainerLifecycleManager lifecycle;
    @Inject
    ContainerLauncher launcher;
    @Inject
    EmulatorConfig config;
    @TempDir
    Path code;

    @BeforeEach
    void requireDocker() {
        try {
            docker.pingCmd().exec();
        } catch (RuntimeException unavailable) {
            Assumptions.abort("Docker daemon must be available for the Lambda leftover container test");
        }
    }

    @Test
    void nextRunRemovesRunningAndExitedLeftoversWithoutRemovingOtherOwnersOrNamedVolumes() throws Exception {
        Files.writeString(code.resolve("index.js"), "exports.handler = async () => 'ok';");
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("leftover-" + UUID.randomUUID());
        function.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:" + function.getFunctionName());
        function.setAccountId("000000000000");
        function.setRuntime("nodejs20.x");
        function.setHandler("index.handler");
        function.setCodeLocalPath(code.toString());

        List<String> fixtures = new ArrayList<>();
        String volume = "floci-lambda-leftover-test-" + UUID.randomUUID();
        ContainerHandle runtime = null;
        docker.createVolumeCmd().withName(volume).exec();
        try {
            runtime = launcher.launch(function);
            Map<String, String> labels = docker.inspectContainerCmd(runtime.getContainerId()).exec()
                    .getConfig().getLabels();
            assertEquals(ContainerStorageHelper.ownerIdentity(config), labels.get(ContainerStorageHelper.OWNER_LABEL));
            LambdaContainerReconciler nextRun = new LambdaContainerReconciler(config, lifecycle);
            assertNotEquals(nextRun.ownershipLabels().get(LambdaContainerReconciler.RUN_LABEL),
                    labels.get(LambdaContainerReconciler.RUN_LABEL));

            String exited = lifecycle.create(builder.newContainer(TestImages.BUSYBOX)
                    .withCmd(List.of("sh", "-c", "exit 1"))
                    .withLabels(labels).withNamedVolume(volume, "/code").build());
            fixtures.add(exited);
            docker.startContainerCmd(exited).exec();
            assertEquals(1, docker.waitContainerCmd(exited).exec(new WaitContainerResultCallback())
                    .awaitStatusCode(20, TimeUnit.SECONDS));
            String current = fixture(nextRun.ownershipLabels(), fixtures);
            String sibling = fixture(Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda",
                    ContainerStorageHelper.OWNER_LABEL, "another-deployment/4566",
                    LambdaContainerReconciler.RUN_LABEL, "previous-run"), fixtures);
            String unowned = fixture(Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda"), fixtures);

            nextRun.reconcile();

            assertFalse(exists(runtime.getContainerId()), "a previous run's running runtime must go");
            assertFalse(exists(exited), "a previous run's Exited(1) helper must go");
            assertTrue(exists(current), "a runtime from the current run must stay");
            assertTrue(exists(sibling), "another deployment's container must stay");
            assertTrue(exists(unowned), "a legacy container without proven ownership must stay");
            assertEquals(volume, docker.inspectVolumeCmd(volume).exec().getName(), "named code volumes must stay");
        } finally {
            if (runtime != null) {
                launcher.stop(runtime);
            }
            for (String id : fixtures) {
                lifecycle.removeIfExistsStrict(id, true);
            }
            docker.removeVolumeCmd(volume).exec();
        }
    }

    private String fixture(Map<String, String> labels, List<String> fixtures) {
        String id = lifecycle.create(builder.newContainer(TestImages.BUSYBOX)
                .withCmd(List.of("sleep", "120")).withLabels(labels).build());
        fixtures.add(id);
        return id;
    }

    private boolean exists(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return true;
        } catch (NotFoundException expected) {
            return false; // The sweep removed this exact test container.
        }
    }

    public static final class LeftoversProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.docker.resource-namespace", "lambda-leftovers-7731",
                    "floci.services.lambda.executor", "docker");
        }
    }
}
