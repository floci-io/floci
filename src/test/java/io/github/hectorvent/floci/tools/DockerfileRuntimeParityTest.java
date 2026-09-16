package io.github.hectorvent.floci.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins {@code docker/Dockerfile.jvm-package} to the runtime stage of {@code docker/Dockerfile}.
 *
 * <p>The local-package image exists only to skip the in-image Maven build, so everything it does
 * after the {@code FROM} is meant to be the canonical image's runtime stage verbatim. Nothing
 * enforced that, and the first copy silently dropped the {@code EXPOSE 6379-6399} block that exists
 * for the docker-java compatibility workaround in issue #2095: an image that looked equivalent
 * published a different port set.
 *
 * <p>Two lines are allowed to differ and are listed in {@link #INTENDED_DIFFERENCES}. Everything
 * else, comments included, has to match, so a change to the canonical runtime stage fails here
 * until it is mirrored.
 */
class DockerfileRuntimeParityTest {

    private static final Path CANONICAL = Path.of("docker/Dockerfile");
    private static final Path LOCAL_PACKAGE = Path.of("docker/Dockerfile.jvm-package");

    /**
     * Canonical line to the line the local-package image carries instead.
     *
     * <p>The version default, because this image is built from a working tree rather than a
     * release, and where the application comes from, which is the whole point of the file. Both
     * sides are asserted present, so an entry cannot quietly stop applying.
     */
    private static final Map<String, String> INTENDED_DIFFERENCES = new LinkedHashMap<>(Map.of(
            "ARG VERSION=latest", "ARG VERSION=dev",
            "COPY --from=build /build/target/quarkus-app/ quarkus-app/",
            "COPY target/quarkus-app/ quarkus-app/"));

    @Test
    void theLocalPackageImageMirrorsTheCanonicalRuntimeStage() {
        List<String> canonical = runtimeStage(CANONICAL, lastFrom(read(CANONICAL)));
        List<String> local = runtimeStage(LOCAL_PACKAGE, onlyFrom(read(LOCAL_PACKAGE)));

        List<String> expected = canonical.stream()
                .map(line -> INTENDED_DIFFERENCES.getOrDefault(line, line))
                .toList();

        for (int i = 0; i < Math.min(expected.size(), local.size()); i++) {
            if (!expected.get(i).equals(local.get(i))) {
                fail("docker/Dockerfile.jvm-package has drifted from the runtime stage of "
                        + "docker/Dockerfile at line " + (i + 1) + " of that stage.\n"
                        + "  canonical: " + expected.get(i) + "\n"
                        + "  local:     " + local.get(i) + "\n"
                        + "Mirror the canonical change, or add the line to INTENDED_DIFFERENCES "
                        + "with a reason if it is meant to differ.");
            }
        }
        assertEquals(expected.size(), local.size(),
                "the two runtime stages have different lengths; docker/Dockerfile.jvm-package must "
                        + "carry every instruction of the canonical runtime stage, "
                        + "EXPOSE block included");
    }

    /**
     * Guards the allow-list itself: an entry whose canonical side has been edited away would
     * otherwise sit there looking like it still permits something.
     */
    @Test
    void everyIntendedDifferenceIsStillPresentOnBothSides() {
        String canonical = String.join("\n", read(CANONICAL));
        String local = String.join("\n", read(LOCAL_PACKAGE));
        INTENDED_DIFFERENCES.forEach((canonicalLine, localLine) -> {
            assertTrue(canonical.contains(canonicalLine),
                    "docker/Dockerfile no longer contains the allow-listed line: " + canonicalLine);
            assertTrue(local.contains(localLine),
                    "docker/Dockerfile.jvm-package no longer contains its replacement: " + localLine);
        });
    }

    /** The canonical file is multi-stage; its runtime stage is the last one. */
    private static int lastFrom(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).startsWith("FROM ")) {
                return i;
            }
        }
        return fail("docker/Dockerfile has no FROM instruction");
    }

    /** The local-package file has no build stage, so a second FROM would mean it grew one. */
    private static int onlyFrom(List<String> lines) {
        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("FROM ")) {
                if (found >= 0) {
                    fail("docker/Dockerfile.jvm-package has more than one FROM; it is meant to be a "
                            + "single runtime stage consuming a prebuilt target/quarkus-app");
                }
                found = i;
            }
        }
        return found >= 0 ? found : fail("docker/Dockerfile.jvm-package has no FROM instruction");
    }

    private static List<String> runtimeStage(Path file, int from) {
        return read(file).subList(from, read(file).size());
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
