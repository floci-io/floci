package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class Ec2ExternalImageCatalogTest {
    @TempDir
    Path directory;

    @Test
    void externalCatalogSelectsLocalSystemdImageWithoutChangingBundledCatalog() throws Exception {
        Path catalog = directory.resolve("images.yaml");
        Files.writeString(catalog, """
                defaultDockerImage: example/base:1
                images:
                  - imageId: ami-local-worker
                    aliases: [ami-worker-alias]
                    dockerImage: example/worker:1
                    name: local-worker
                    description: local worker
                    architecture: arm64
                    creationDate: '2025-11-05T00:00:00.000Z'
                    guestRuntime: systemd
                """);
        Ec2ImageCatalog external = new Ec2ImageCatalog(catalog);
        assertEquals("example/worker:1", external.findByIdOrAlias("ami-worker-alias").orElseThrow().dockerImage);
        assertEquals("systemd", external.findByIdOrAlias("ami-local-worker").orElseThrow().guestRuntime);
        assertEquals("example/base:1", external.defaultDockerImage());
        assertTrue(new Ec2ImageCatalog().findByIdOrAlias("ami-amazonlinux2023").isPresent());
        assertTrue(new Ec2ImageCatalog().findByIdOrAlias("ami-local-worker").isEmpty());
    }

    @Test
    void missingOrInvalidFileFailsInsteadOfSilentlyUsingBundledImages() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> new Ec2ImageCatalog(directory.resolve("missing.yaml")).images());
        Path invalid = directory.resolve("invalid.yaml");
        Files.writeString(invalid, "images: [not-valid");
        assertThrows(IllegalStateException.class, () -> new Ec2ImageCatalog(invalid).images());
    }
}
