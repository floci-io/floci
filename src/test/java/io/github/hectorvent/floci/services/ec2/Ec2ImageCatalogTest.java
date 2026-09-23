package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;

@QuarkusTest
class Ec2ImageCatalogTest {

    @Inject
    Ec2ImageCatalog imageCatalog;

    @Inject
    AmiImageResolver amiImageResolver;

    @Test
    void catalogContainsCurrentEc2ImagesAndFlociAliases() {
        Set<String> imageIds = imageCatalog.images().stream()
                .map(image -> image.imageId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "ami-0abcdef1234567890",
                "ami-0abcdef1234567891",
                "ami-amazonlinux2023-arm64",
                "ami-0abcdef1234567892",
                "ami-ubuntu2204",
                "ami-ubuntu2404-arm64",
                "ami-ubuntu2404-amd64",
                "ami-ubuntu2404-cloud-arm64",
                "ami-debian12",
                "ami-alpine",
                "ami-0abcdef1234567893"), imageIds);

        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2023").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2023-arm64").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2004").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2404").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2404-cloud").isPresent());
    }

    @Test
    void amazonLinux2023EntriesCoverArm64AndX86_64Architectures() {
        Ec2ImageCatalog.CatalogImage x86 = imageCatalog.findByIdOrAlias("ami-0abcdef1234567891").orElseThrow();
        assertEquals("x86_64", x86.architecture);
        assertEquals("al2023-ami-2023.0.20230315.0-kernel-6.1-x86_64", x86.name);
        assertEquals(List.of(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-minimal-kernel-default-x86_64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64"),
                x86.publicParameterNames());

        Ec2ImageCatalog.CatalogImage arm64 = imageCatalog.findByIdOrAlias("ami-amazonlinux2023-arm64").orElseThrow();
        assertEquals("arm64", arm64.architecture);
        assertEquals("al2023-ami-2023.0.20230315.0-kernel-6.1-arm64", arm64.name);
        assertEquals(List.of(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-minimal-kernel-default-arm64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-arm64"),
                arm64.publicParameterNames());
    }

    @Test
    void resolverUsesCatalogDockerImagesForIdsAndAliases() {
        imageCatalog.images()
                .stream()
                .filter(image -> !"windows".equalsIgnoreCase(image.platform))
                .forEach(image -> assertEquals(image.dockerImage, amiImageResolver.resolve(image.imageId)));

        List.of("ami-amazonlinux2", "ami-amazonlinux2023", "ami-ubuntu2004", "ami-ubuntu2404")
                .forEach(alias -> assertEquals(
                        imageCatalog.findByIdOrAlias(alias).orElseThrow().dockerImage,
                        amiImageResolver.resolve(alias)));
    }

    @Test
    void resolverExposesCloudImageGuestRuntimeMetadata() {
        ResolvedAmiImage image = amiImageResolver.resolveImage("ami-ubuntu2404-cloud");

        assertEquals("floci/ami-ubuntu:24.04-arm64", image.dockerImage());
        assertEquals(ResolvedAmiImage.SYSTEMD_RUNTIME, image.guestRuntime());
        assertTrue(image.cloudInit());
        assertTrue(image.systemd());
    }

    @Test
    void resolverMapsImageArchitectureToDockerPlatform() {
        assertEquals("linux/arm64", amiImageResolver.resolveImage("ami-ubuntu2404-cloud-arm64").dockerPlatform());
        assertEquals("linux/arm64", amiImageResolver.resolveImage("ami-amazonlinux2023-arm64").dockerPlatform());
        assertEquals("linux/amd64", amiImageResolver.resolveImage("ami-0abcdef1234567891").dockerPlatform());
    }

    @Test
    void resolverRejectsWindowsImagesForContainerExecution() {
        AwsException error = assertThrows(AwsException.class,
                () -> amiImageResolver.resolveImage("ami-0abcdef1234567893"));

        assertEquals("UnsupportedOperation", error.getErrorCode());
    }

    @Test
    void unknownImageUsesCatalogDefault() {
        assertFalse(imageCatalog.findByIdOrAlias("ami-unknown").isPresent());
        assertEquals(imageCatalog.defaultDockerImage(), amiImageResolver.resolve("ami-unknown"));
    }
}
