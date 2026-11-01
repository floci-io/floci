package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import io.github.hectorvent.floci.core.common.Digests;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Cached helper images with explicit platform and recipe metadata for Docker client fixtures. */
public final class CachedImageStubs {

    private CachedImageStubs() {
    }

    public static void image(DockerClient docker, String reference, String os, String architecture, String recipe) {
        InspectImageResponse image = mock(InspectImageResponse.class);
        when(image.getOs()).thenReturn(os);
        when(image.getArch()).thenReturn(architecture);
        when(image.getConfig()).thenReturn(new ContainerConfig().withLabels(recipe == null
                ? Map.of() : Map.of(LocallyBuiltHelperImage.RECIPE_LABEL, recipe)));
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(inspect.exec()).thenReturn(image);
        when(docker.inspectImageCmd(reference)).thenReturn(inspect);
    }

    public static String recipe(String dockerfile) {
        try (InputStream stream = CachedImageStubs.class.getResourceAsStream(dockerfile)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture Dockerfile: " + dockerfile);
            }
            return Digests.sha256Hex(stream.readAllBytes());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read fixture Dockerfile: " + dockerfile, failure);
        }
    }
}
