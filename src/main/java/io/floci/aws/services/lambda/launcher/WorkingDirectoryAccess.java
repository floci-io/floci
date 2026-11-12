package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
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

    /** Linux's own limit on the symlinks one path lookup follows. */
    private static final int MAX_SYMLINKS = 40;

    private WorkingDirectoryAccess() {
    }

    /** A path in the container: a directory with its mode, or a symlink with its target. */
    record PathEntry(int mode, String linkTarget) {

        static PathEntry directory(int mode) {
            return new PathEntry(mode, null);
        }

        static PathEntry symlink(String target) {
            return new PathEntry(0, target);
        }
    }

    /**
     * Fails with {@code Runtime.InvalidEntrypoint} when Lambda's user cannot enter the created container's
     * working directory. A file system mounted for the function is entered as its access point's user, so
     * the check stops at its mount path.
     */
    static void requireLambdaUserCanEnter(DockerClient docker, String containerId, Collection<String> mountPaths) {
        String workingDir = docker.inspectContainerCmd(containerId).exec().getConfig().getWorkingDir();
        firstDirectoryLambdaCannotEnter(workingDir, mountPaths, path -> pathEntry(docker, containerId, path))
                .ifPresent(closed -> {
                    throw new LambdaInitException("Runtime.InvalidEntrypoint",
                            "Lambda's default user cannot enter the working directory " + workingDir
                                    + ": " + closed + " is not executable by other users");
                });
    }

    /**
     * The first directory on the path to {@code workingDir} that Lambda's user cannot enter, following
     * symlinks the way the kernel does. {@code entryOf} answers {@code null} for a path it does not know.
     */
    static Optional<String> firstDirectoryLambdaCannotEnter(String workingDir, Collection<String> mountPaths,
                                                            Function<String, PathEntry> entryOf) {
        if (workingDir == null || workingDir.isBlank()) {
            return Optional.empty();
        }
        List<String> mounts = mountPaths.stream().map(WorkingDirectoryAccess::withoutTrailingSlash).toList();
        Deque<String> remaining = new ArrayDeque<>(Arrays.asList(workingDir.split("/")));
        String resolved = "";
        int links = 0;
        while (!remaining.isEmpty()) {
            String segment = remaining.removeFirst();
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                resolved = resolved.substring(0, Math.max(resolved.lastIndexOf('/'), 0));
                continue;
            }
            String path = resolved + "/" + segment;
            if (mounts.contains(path)) {
                return Optional.empty();
            }
            PathEntry entry = entryOf.apply(path);
            if (entry != null && entry.linkTarget() != null) {
                if (++links > MAX_SYMLINKS) {
                    return Optional.empty();
                }
                List<String> target = Arrays.asList(entry.linkTarget().split("/"));
                for (int i = target.size() - 1; i >= 0; i--) {
                    remaining.addFirst(target.get(i));
                }
                if (entry.linkTarget().startsWith("/")) {
                    resolved = "";
                }
                continue;
            }
            if (entry != null && (entry.mode() & OTHERS_EXECUTE) == 0) {
                return Optional.of(path);
            }
            resolved = path;
        }
        return Optional.empty();
    }

    private static String withoutTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * A path in the container, from the first entry of the archive Docker returns for it, which is the
     * path itself and not what a symlink points to. Read through the archive API so it works on an image
     * with no shell, and on a container that has not started.
     */
    private static PathEntry pathEntry(DockerClient docker, String containerId, String path) {
        try (InputStream stream = docker.copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(stream)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null) {
                return null;
            }
            if (entry.isSymbolicLink()) {
                return PathEntry.symlink(entry.getLinkName());
            }
            return entry.isDirectory() ? PathEntry.directory(entry.getMode()) : null;
        } catch (NotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the mode of " + path, e);
        }
    }
}
