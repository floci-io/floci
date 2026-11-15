package io.floci.aws.core.common.docker;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** The tar build context Docker's build API takes, for an image built from a Dockerfile alone. */
public final class DockerBuildContext {

    private DockerBuildContext() {
    }

    public static byte[] ofDockerfile(byte[] dockerfile) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream(dockerfile.length + 1024);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
            entry.setSize(dockerfile.length);
            tar.putArchiveEntry(entry);
            tar.write(dockerfile);
            tar.closeArchiveEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the Docker build context", e);
        }
        return archive.toByteArray();
    }
}
