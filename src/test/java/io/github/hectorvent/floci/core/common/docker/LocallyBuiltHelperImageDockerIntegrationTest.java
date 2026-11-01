package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Opt in only with DOCKER_HOST pointing to a disposable daemon and image cache. The test deliberately
 * replaces a shared base tag; the caller owns removal of that isolated daemon and its entire cache.
 */
@EnabledIfSystemProperty(named = "floci.test.isolated-helper-docker", matches = "true")
class LocallyBuiltHelperImageDockerIntegrationTest {
    private static final String BASE = "busybox:1.37.0";

    @Test
    void helperBuildReplacesForeignHelperAndBaseWithNativeExecutableImage() throws Exception {
        EmulatorConfig config = new SmallRyeConfigBuilder().withMapping(EmulatorConfig.class)
                .build().getConfigMapping(EmulatorConfig.class);
        try (DockerClient docker = new DockerClientProducer(config).dockerClient()) {
            try {
                docker.pingCmd().exec();
            } catch (RuntimeException e) {
                Assumptions.abort("Docker daemon is unavailable");
            }
            String nativePlatform = DockerPlatform.daemon(docker);
            Assumptions.assumeTrue(nativePlatform.equals("linux/amd64") || nativePlatform.equals("linux/arm64"));
            String foreignPlatform = nativePlatform.equals("linux/amd64") ? "linux/arm64" : "linux/amd64";
            try (PullImageResultCallback pulled = new PullImageResultCallback()) {
                assertTrue(docker.pullImageCmd(BASE).withPlatform(foreignPlatform).exec(pulled)
                        .awaitCompletion(120, TimeUnit.SECONDS));
            }
            String tag = UUID.randomUUID().toString();
            String repository = "floci/helper-platform-test";
            String helper = repository + ":" + tag;
            String containerId = null;
            docker.tagImageCmd(BASE, repository, tag).exec();
            try {
                Assumptions.assumeTrue(DockerPlatform.matches(docker.inspectImageCmd(helper).exec(), foreignPlatform),
                        "This daemon does not expose the cached foreign image platform");
                ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class));

                LocallyBuiltHelperImage.ensureBuilt(docker, builder, helper, helper,
                        "/docker/native-platform-helper.Dockerfile");

                assertTrue(DockerPlatform.matches(docker.inspectImageCmd(helper).exec(), nativePlatform));
                String builtImage = docker.inspectImageCmd(helper).exec().getId();
                LocallyBuiltHelperImage.ensureBuilt(docker, builder, helper, helper,
                        "/docker/native-platform-helper.Dockerfile");
                assertEquals(builtImage, docker.inspectImageCmd(helper).exec().getId());
                containerId = docker.createContainerCmd(helper).exec().getId();
                docker.startContainerCmd(containerId).exec();
                try (WaitContainerResultCallback exited = new WaitContainerResultCallback()) {
                    assertEquals(0, docker.waitContainerCmd(containerId).exec(exited)
                            .awaitStatusCode(30, TimeUnit.SECONDS));
                }
                StringBuilder output = new StringBuilder();
                try (ResultCallback.Adapter<Frame> logs = new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }) {
                    assertTrue(docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                            .exec(logs).awaitCompletion(30, TimeUnit.SECONDS));
                }
                assertEquals(nativePlatform.equals("linux/amd64") ? "x86_64" : "aarch64",
                        output.toString().trim());
            } finally {
                if (containerId != null) {
                    docker.removeContainerCmd(containerId).withForce(true).exec();
                }
                docker.removeImageCmd(helper).withForce(true).exec();
            }
        }
    }
}
