package io.github.hectorvent.floci.tools.ami;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AmiImageToolTest {
    @TempDir
    Path tempDir;

    @Test
    void metadataDrivesGenerationAndCatalogUpdate() throws Exception {
        Path rootfs = tempDir.resolve("ubuntu-root.tar.xz");
        Path manifest = tempDir.resolve("ubuntu.manifest");
        Files.writeString(rootfs, "rootfs", StandardCharsets.UTF_8);
        Files.writeString(manifest, "systemd\t1\ncloud-init\t1\n", StandardCharsets.UTF_8);
        Path metadata = tempDir.resolve("image-build-metadata.yaml");
        Files.writeString(metadata, """
                releaseId: test-release
                images:
                  - id: ubuntu-24.04-arm64
                    catalogImageId: ami-ubuntu2404-cloud-arm64
                    catalogAliases: [ami-ubuntu2404-cloud]
                    family: ubuntu
                    version: "24.04"
                    architecture: arm64
                    aws:
                      region: us-east-1
                      ownerId: "099720109477"
                      imageId: ami-source
                      name: ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-20260515
                      creationDate: "2026-05-15T00:00:00.000Z"
                      virtualizationType: hvm
                      rootDeviceType: ebs
                    canonical:
                      baseUrl: %s
                      rootfs: %s
                      rootfsSha256: %s
                      manifest: %s
                    docker:
                      image: floci/ami-ubuntu:24.04-arm64
                    guest:
                      runtime: systemd
                      cloudInit: true
                      smokePackages: [systemd, cloud-init]
                """.formatted(tempDir.toUri(), rootfs.getFileName(), sha256(rootfs), manifest.getFileName()));

        AmiImageTool.Metadata loaded = AmiImageTool.loadMetadata(metadata);
        AmiImageTool.ImageSpec image = loaded.images.getFirst();
        Path context = AmiImageTool.generate(image, tempDir.resolve("out"), true);

        assertTrue(Files.readString(context.resolve("Dockerfile")).contains("FROM scratch"));
        assertTrue(Files.readString(context.resolve("Dockerfile")).contains("io.floci.ami.catalog-image-id"));
        assertTrue(Files.readString(context.resolve("Dockerfile")).contains("ADD ubuntu-root.tar.xz /"));
        assertTrue(Files.readString(context.resolve("provenance.yaml")).contains("manifestSha256"));

        Path catalog = tempDir.resolve("image-catalog.yaml");
        Files.writeString(catalog, """
                defaultDockerImage: public.ecr.aws/amazonlinux/amazonlinux:2023
                images:
                  - imageId: ami-existing
                    dockerImage: public.ecr.aws/docker/library/ubuntu:24.04
                    name: existing
                    description: existing
                    architecture: arm64
                    creationDate: "2026-01-01T00:00:00.000Z"
                """);
        Path output = tempDir.resolve("custom-output");
        AmiImageTool.updateCatalog(image, catalog, output, true);
        String catalogText = Files.readString(catalog);
        assertTrue(catalogText.contains("ami-existing"));
        assertTrue(catalogText.contains("ami-ubuntu2404-cloud-arm64"));
        assertTrue(catalogText.contains("guestRuntime: \"systemd\""));
        assertTrue(catalogText.contains("cloudInit: true"));
        assertTrue(catalogText.contains("custom-output/ubuntu-24.04-arm64/provenance.yaml"));
    }

    @Test
    void metadataRejectsDockerLibraryUbuntuAsSource() throws Exception {
        Path badRootfsMetadata = tempDir.resolve("bad-rootfs.yaml");
        Files.writeString(badRootfsMetadata, """
                releaseId: bad
                images:
                  - id: bad
                    catalogImageId: ami-bad
                    family: ubuntu
                    version: "24.04"
                    architecture: arm64
                    aws:
                      region: us-east-1
                      ownerId: "099720109477"
                      imageId: ami-bad-source
                      name: bad
                      creationDate: "2026-05-15T00:00:00.000Z"
                      virtualizationType: hvm
                      rootDeviceType: ebs
                    canonical:
                      baseUrl: https://example.com
                      rootfs: ubuntu:24.04
                      rootfsSha256: abc
                      manifest: manifest
                    docker: { image: floci/ami-ubuntu:24.04-arm64 }
                    guest: { runtime: systemd, cloudInit: true }
                """);

        assertThrows(IllegalArgumentException.class, () -> AmiImageTool.loadMetadata(badRootfsMetadata));

        Path badDockerMetadata = tempDir.resolve("bad-docker.yaml");
        Files.writeString(badDockerMetadata, """
                releaseId: bad
                images:
                  - id: bad
                    catalogImageId: ami-bad
                    family: ubuntu
                    version: "24.04"
                    architecture: arm64
                    aws:
                      region: us-east-1
                      ownerId: "099720109477"
                      imageId: ami-bad-source
                      name: bad
                      creationDate: "2026-05-15T00:00:00.000Z"
                      virtualizationType: hvm
                      rootDeviceType: ebs
                    canonical:
                      baseUrl: https://example.com
                      rootfs: ubuntu-root.tar.xz
                      rootfsSha256: abc
                      manifest: manifest
                    docker: { image: public.ecr.aws/docker/library/ubuntu:24.04 }
                    guest: { runtime: systemd, cloudInit: true }
                """);

        assertThrows(IllegalArgumentException.class, () -> AmiImageTool.loadMetadata(badDockerMetadata));
    }

    @Test
    void toolTreeDoesNotContainShellScripts() throws Exception {
        Path tools = Path.of("src/main/java/io/github/hectorvent/floci/tools/ami");
        if (Files.isDirectory(tools)) {
            try (var stream = Files.walk(tools)) {
                assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".sh")));
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }
}
