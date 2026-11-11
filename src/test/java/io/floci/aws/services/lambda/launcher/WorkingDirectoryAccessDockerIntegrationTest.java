package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.DockerClientProducer;
import io.floci.aws.testing.DockerAvailability;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reads the working directory's permissions from a created, unstarted container of a real image. */
class WorkingDirectoryAccessDockerIntegrationTest {

    private static final String DOCKERFILE = """
            FROM busybox:1.37.0
            RUN mkdir -p /home/nonroot /srv/open \\
                && chown 65532:65532 /home/nonroot && chmod 0700 /home/nonroot \\
                && chmod 0755 /srv/open
            USER 65532
            WORKDIR /home/nonroot
            """;

    @Test
    void aWorkingDirectoryOnlyTheImageUserCanEnterFailsInitAsAnInvalidEntrypoint() throws Exception {
        withImage((docker, image) -> {
            String containerId = docker.createContainerCmd(image).exec().getId();
            try {
                LambdaInitException error = assertThrows(LambdaInitException.class,
                        () -> WorkingDirectoryAccess.requireLambdaUserCanEnter(docker, containerId));
                assertEquals("Runtime.InvalidEntrypoint", error.errorType());
            } finally {
                docker.removeContainerCmd(containerId).withForce(true).exec();
            }
        });
    }

    @Test
    void aWorkingDirectoryEveryoneCanEnterStartsAsBefore() throws Exception {
        withImage((docker, image) -> {
            String containerId = docker.createContainerCmd(image).withWorkingDir("/srv/open").exec().getId();
            try {
                assertDoesNotThrow(() -> WorkingDirectoryAccess.requireLambdaUserCanEnter(docker, containerId));
            } finally {
                docker.removeContainerCmd(containerId).withForce(true).exec();
            }
        });
    }

    private interface ImageTest {
        void run(DockerClient docker, String image) throws Exception;
    }

    private static void withImage(ImageTest test) throws Exception {
        EmulatorConfig config = new SmallRyeConfigBuilder().withMapping(EmulatorConfig.class)
                .build().getConfigMapping(EmulatorConfig.class);
        try (DockerClient docker = new DockerClientProducer(config).dockerClient()) {
            Assumptions.assumeTrue(DockerAvailability.answers(docker), "Docker daemon is unavailable");
            String image = "floci/lambda-workdir-test:" + UUID.randomUUID();
            try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
                docker.buildImageCmd(new ByteArrayInputStream(dockerfileArchive()))
                        .withTags(Set.of(image)).exec(callback).awaitImageId();
            }
            try {
                test.run(docker, image);
            } finally {
                docker.removeImageCmd(image).withForce(true).exec();
            }
        }
    }

    private static byte[] dockerfileArchive() throws IOException {
        byte[] content = DOCKERFILE.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return archive.toByteArray();
    }
}
