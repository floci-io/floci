package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Builds a Floci-recipe helper image in the workload daemon the first time something needs it,
 * instead of trying to pull an image that only exists as a Dockerfile checked into Floci itself.
 * Security-group enforcement is the caller today; this exists as a shared utility because a second
 * one is coming (an ECS task-role credentials proxy needs the same image), and the alternative was
 * a verbatim copy of the build alongside the original. The Dockerfile-to-image build handling, and
 * the guard that only builds when the caller is still pointed at Floci's own default, live here.
 */
public final class LocallyBuiltHelperImage {

    private LocallyBuiltHelperImage() {
    }

    /**
     * @param configuredImage the image the caller is actually configured to use
     * @param defaultImage the caller's own built-in default; a caller pointed anywhere else is
     *        responsible for that image being pullable, so nothing is built for it here
     * @param dockerfileResourcePath classpath location of the Dockerfile to build, e.g.
     *        {@code "/docker/network-helper.Dockerfile"}
     */
    public static synchronized void ensureBuilt(DockerClient dockerClient, ContainerBuilder containerBuilder,
                                                String configuredImage, String defaultImage,
                                                String dockerfileResourcePath) {
        if (!defaultImage.equals(configuredImage)) {
            return;
        }
        String image = containerBuilder.resolveImage(configuredImage);
        try {
            dockerClient.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException missing) {
            // Build the versioned Floci recipe in the daemon used for workloads.
        }
        try (InputStream dockerfile = LocallyBuiltHelperImage.class.getResourceAsStream(dockerfileResourcePath)) {
            if (dockerfile == null) {
                throw new IllegalStateException("Floci helper Dockerfile is missing: " + dockerfileResourcePath);
            }
            byte[] content = dockerfile.readAllBytes();
            ByteArrayOutputStream archive = new ByteArrayOutputStream(content.length + 1024);
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
                String built = dockerClient.buildImageCmd(new ByteArrayInputStream(archive.toByteArray()))
                        .withTags(Set.of(image)).exec(callback).awaitImageId();
                if (built == null || built.isBlank()) {
                    throw new IllegalStateException("Docker did not build the Floci helper image: " + image);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the Floci helper image: " + image, e);
        }
    }
}
