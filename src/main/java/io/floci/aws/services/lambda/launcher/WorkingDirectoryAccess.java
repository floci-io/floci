package io.floci.aws.services.lambda.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.common.net.UrlEscapers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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

    /** Go's FileMode type bit for a directory, as the archive stat header reports the mode. */
    private static final int GO_MODE_DIR = 1 << 31;

    /** Go's FileMode type bit for a symlink, as the archive stat header reports the mode. */
    private static final int GO_MODE_SYMLINK = 1 << 27;

    private static final ObjectMapper STAT_JSON = new ObjectMapper();

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
     * the check stops at its mount path. The mode of each folder comes from {@code http}, the control
     * plane transport the container was created on.
     */
    static void requireLambdaUserCanEnter(DockerClient docker, DockerHttpClient http, String containerId,
                                          Collection<String> mountPaths) {
        String workingDir = docker.inspectContainerCmd(containerId).exec().getConfig().getWorkingDir();
        firstDirectoryLambdaCannotEnter(workingDir, mountPaths, path -> pathEntry(http, containerId, path))
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
     * A path in the container, from {@code HEAD /containers/{id}/archive}. The stat header answers the
     * mode and symlink target without Docker archiving the whole directory tree, which for a parent
     * such as {@code /var} means streaming hundreds of megabytes to read one entry.
     */
    private static PathEntry pathEntry(DockerHttpClient http, String containerId, String path) {
        String requestPath = "/containers/" + containerId + "/archive?path="
                + UrlEscapers.urlFormParameterEscaper().escape(path);
        try (DockerHttpClient.Response response = http.execute(new HeadRequest(requestPath))) {
            if (response.getStatusCode() == 404) {
                return null;
            }
            if (response.getStatusCode() >= 300) {
                throw new IllegalStateException("HEAD " + requestPath + " returned " + response.getStatusCode());
            }
            return entryOf(response.getHeader("X-Docker-Container-Path-Stat"));
        }
    }

    /**
     * The path's entry from the base64 JSON the stat header carries, or null for a path that is
     * neither a directory nor a symlink. The mode is Go's FileMode: type in the high bits,
     * permissions in the low nine.
     */
    private static PathEntry entryOf(String encodedStat) {
        if (encodedStat == null) {
            return null;
        }
        JsonNode stat;
        try {
            stat = STAT_JSON.readTree(Base64.getDecoder().decode(encodedStat));
        } catch (IOException e) {
            throw new IllegalStateException("Malformed archive stat header: " + e.getMessage(), e);
        }
        int mode = stat.path("mode").asInt(0);
        if ((mode & GO_MODE_SYMLINK) != 0) {
            return PathEntry.symlink(stat.path("linkTarget").asText(""));
        }
        if ((mode & GO_MODE_DIR) == 0) {
            return null;
        }
        return PathEntry.directory(mode & 07777);
    }

    /** A request docker-java's enum has no constant for: the archive API's path stat probe. */
    private static final class HeadRequest extends DockerHttpClient.Request {

        private final String path;

        HeadRequest(String path) {
            this.path = path;
        }

        @Override
        public String method() {
            return "HEAD";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public byte[] bodyBytes() {
            return null;
        }

        @Override
        public InputStream hijackedInput() {
            return null;
        }

        @Override
        public Map<String, String> headers() {
            return Map.of();
        }
    }
}
