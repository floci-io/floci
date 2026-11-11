package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Function;

/**
 * Whether Lambda's default user can enter an image function's working directory. AWS runs an image
 * as its own least-privileged user rather than the image's {@code USER}, and that user owns nothing in
 * the image, so it passes a directory only through the directory's "other" execute permission. Init
 * fails with {@code Runtime.InvalidEntrypoint} when it cannot. Floci keeps running the container as the
 * image's user, so it checks the path itself before starting the container.
 */
final class WorkingDirectoryAccess {

    private static final int OTHERS_EXECUTE = 0001;

    private WorkingDirectoryAccess() {
    }

    /** Fails with {@code Runtime.InvalidEntrypoint} when Lambda's user cannot enter the created container's working directory. */
    static void requireLambdaUserCanEnter(DockerClient docker, String containerId) {
        String workingDir = docker.inspectContainerCmd(containerId).exec().getConfig().getWorkingDir();
        firstDirectoryLambdaCannotEnter(workingDir, path -> directoryMode(docker, containerId, path))
                .ifPresent(closed -> {
                    throw new LambdaInitException("Runtime.InvalidEntrypoint",
                            "Lambda's default user cannot enter the working directory " + workingDir
                                    + ": " + closed + " is not executable by other users");
                });
    }

    /**
     * The first directory on the path to {@code workingDir} that Lambda's user cannot enter, given each
     * directory's mode, or {@code null} from {@code modeOf} when it is not known.
     */
    static Optional<String> firstDirectoryLambdaCannotEnter(String workingDir, Function<String, Integer> modeOf) {
        if (workingDir == null || workingDir.isBlank()) {
            return Optional.empty();
        }
        StringBuilder path = new StringBuilder();
        for (String segment : workingDir.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            path.append('/').append(segment);
            Integer mode = modeOf.apply(path.toString());
            if (mode != null && (mode & OTHERS_EXECUTE) == 0) {
                return Optional.of(path.toString());
            }
        }
        return Optional.empty();
    }

    /**
     * The mode of a directory in the container, from the first entry of the archive Docker returns for
     * it, which is the directory itself. Read through the archive API so it works on an image with no
     * shell, and on a container that has not started.
     */
    private static Integer directoryMode(DockerClient docker, String containerId, String path) {
        try (InputStream stream = docker.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(stream)) {
            TarArchiveEntry entry = tar.getNextEntry();
            return entry != null && entry.isDirectory() ? entry.getMode() : null;
        } catch (NotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the mode of " + path, e);
        }
    }
}
