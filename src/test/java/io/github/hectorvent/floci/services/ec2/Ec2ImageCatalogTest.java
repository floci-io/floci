package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
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
                "ami-0abcdef1234567892",
                "ami-ubuntu2204",
                "ami-ubuntu2404-arm64",
                "ami-ubuntu2404-amd64",
                "ami-debian12",
                "ami-alpine",
                "ami-0abcdef1234567893"), imageIds);

        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2023").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2004").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2404").isPresent());
    }

    @Test
    void resolverUsesCatalogDockerImagesForIdsAndAliases() {
        imageCatalog.images()
                .forEach(image -> assertEquals(image.dockerImage, amiImageResolver.resolve(image.imageId)));

        List.of("ami-amazonlinux2", "ami-amazonlinux2023", "ami-ubuntu2004", "ami-ubuntu2404")
                .forEach(alias -> assertEquals(
                        imageCatalog.findByIdOrAlias(alias).orElseThrow().dockerImage,
                        amiImageResolver.resolve(alias)));
    }

    @Test
    void unknownImageUsesCatalogDefault() {
        assertFalse(imageCatalog.findByIdOrAlias("ami-unknown").isPresent());
        assertEquals(imageCatalog.defaultDockerImage(), amiImageResolver.resolve("ami-unknown"));
    }
}
