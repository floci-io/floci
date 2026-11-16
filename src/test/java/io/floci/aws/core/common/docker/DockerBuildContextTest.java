package io.floci.aws.core.common.docker;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DockerBuildContextTest {

    @Test
    void theContextHoldsOnlyTheDockerfile() throws IOException {
        byte[] dockerfile = "FROM busybox:stable\n".getBytes(StandardCharsets.UTF_8);

        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ByteArrayInputStream(DockerBuildContext.ofDockerfile(dockerfile)))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("Dockerfile", entry.getName());
            assertEquals("FROM busybox:stable\n", new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            assertNull(tar.getNextEntry());
        }
    }
}
