package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.DockerBuildContext;
import io.floci.aws.core.common.docker.DockerClientProducer;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reads the working directory's permissions from a created, unstarted container of a real image. */
class WorkingDirectoryAccessDockerIntegrationTest {

    private static final String DOCKERFILE = """
            FROM %s
            RUN mkdir -p /home/nonroot /srv/open \\
                && chown 65532:65532 /home/nonroot && chmod 0700 /home/nonroot \\
                && chmod 0755 /srv/open \\
                && ln -s /home/nonroot /app
            USER 65532
            WORKDIR /home/nonroot
            """.formatted(TestImages.BUSYBOX);

    @Test
    void aWorkingDirectoryOnlyTheImageUserCanEnterFailsInitAsAnInvalidEntrypoint() throws Exception {
        withContainer(null, (docker, containerId) -> {
            LambdaInitException error = assertThrows(LambdaInitException.class,
                    () -> WorkingDirectoryAccess.requireLambdaUserCanEnter(docker, containerId, List.of()));
            assertEquals("Runtime.InvalidEntrypoint", error.errorType());
        });
    }

    @Test
    void aSymlinkToADirectoryOnlyTheImageUserCanEnterFailsInit() throws Exception {
        withContainer("/app", (docker, containerId) -> assertThrows(LambdaInitException.class,
                () -> WorkingDirectoryAccess.requireLambdaUserCanEnter(docker, containerId, List.of())));
    }

    @Test
    void aWorkingDirectoryEveryoneCanEnterStartsAsBefore() throws Exception {
        withContainer("/srv/open", (docker, containerId) -> assertDoesNotThrow(
                () -> WorkingDirectoryAccess.requireLambdaUserCanEnter(docker, containerId, List.of())));
    }

    private interface ContainerTest {
        void run(DockerClient docker, String containerId) throws Exception;
    }

    private static void withContainer(String workingDir, ContainerTest test) throws Exception {
        EmulatorConfig config = new SmallRyeConfigBuilder().withMapping(EmulatorConfig.class)
                .build().getConfigMapping(EmulatorConfig.class);
        try (DockerClient docker = new DockerClientProducer(config).dockerClient()) {
            Assumptions.assumeTrue(DockerAvailability.answers(docker), "Docker daemon is unavailable");
            String image = "floci/lambda-workdir-test:" + UUID.randomUUID();
            byte[] context = DockerBuildContext.ofDockerfile(DOCKERFILE.getBytes(StandardCharsets.UTF_8));
            try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
                docker.buildImageCmd(new ByteArrayInputStream(context))
                        .withTags(Set.of(image)).exec(callback).awaitImageId();
            }
            try {
                CreateContainerCmd create = docker.createContainerCmd(image);
                if (workingDir != null) {
                    create.withWorkingDir(workingDir);
                }
                String containerId = create.exec().getId();
                try {
                    test.run(docker, containerId);
                } finally {
                    docker.removeContainerCmd(containerId).withForce(true).exec();
                }
            } finally {
                docker.removeImageCmd(image).withForce(true).exec();
            }
        }
    }
}
