package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CodeBuildSourceCopyTest {

    @Test
    void retriesSourceUploadWithFreshTarAfterBrokenPipe(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("buildspec.yml"), "version: 0.2\n");
        DockerClient docker = mock(DockerClient.class);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "build-container");
        uploads.failures = 1;
        uploads.consumedBytes = 8;
        uploads.failure = new RuntimeException(new IOException("Broken pipe"));

        new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src", 0);

        assertEquals(2, uploads.commands.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        for (CopyArchiveToContainerCmd command : uploads.commands) {
            verify(command).withRemotePath("/codebuild/output/src/src");
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("buildspec.yml", entry.getName());
            assertEquals("version: 0.2\n", new String(tar.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void propagatesFailureAfterTransientRetryBudgetIsExhausted(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("buildspec.yml"), "version: 0.2\n");
        DockerClient docker = mock(DockerClient.class);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "build-container");
        uploads.failures = 10;
        uploads.failure = new RuntimeException(new IOException("Broken pipe"));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                        .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src", 0));

        assertEquals(6, uploads.commands.size());
        assertTrue(failure.getMessage().contains("codebuild-source"));
    }

    @Test
    void propagatesSourceUploadFailureInsteadOfRunningWithoutSource(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("buildspec.yml"), "version: 0.2\n");
        DockerClient docker = mock(DockerClient.class);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "build-container");
        uploads.failures = 10;
        uploads.failure = new IllegalStateException("No such container");

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                        .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src", 0));

        assertTrue(failure.getMessage().contains("No such container"));
        assertEquals(1, uploads.commands.size());
    }

    @Test
    void skipsEmptyWorkspace(@TempDir Path workspace) {
        DockerClient docker = mock(DockerClient.class);

        new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src");

        verifyNoInteractions(docker);
    }
}
