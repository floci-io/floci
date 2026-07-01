package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContainerLauncher#codeVolumeName(LambdaFunction)}, the naming logic for
 * the per-function-version code volume. The name must be stable for a given code version (so all
 * of a function's containers share one volume), distinct across code versions (so a redeploy gets
 * a fresh volume), and always a legal Docker volume name.
 *
 * <p>Kept separate from {@link ContainerLauncherTest} because these are pure static-method tests
 * that need none of that test's Mockito mocks; mixing them in would trip strict-stubbing.
 */
class ContainerLauncherVolumeNamingTest {

    /** The pattern Docker requires a volume name to match. */
    private static final Pattern DOCKER_VOLUME_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$");

    private static LambdaFunction fnWithSha(String name, String sha) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setCodeSha256(sha);
        return fn;
    }

    @Test
    void isFlociCodeShapedAndDockerSafe() {
        String name = ContainerLauncher.codeVolumeName(
                fnWithSha("my-fn", "AbC123+/xyz=deadbeefcafebabe0123456789"));

        assertTrue(name.startsWith("floci-code-my-fn-"),
                "should be floci-code-<functionName>-<hash> shaped, was: " + name);
        assertTrue(DOCKER_VOLUME_NAME.matcher(name).matches(),
                "must be a docker-volume-safe name, was: " + name);
    }

    @Test
    void sanitizesUnsafeFunctionNameChars() {
        // Real function names are constrained, but be defensive: any unsafe char must not leak
        // into the volume name (which would make `docker volume create` reject it).
        String name = ContainerLauncher.codeVolumeName(fnWithSha("weird name/v2:1", "abc123"));
        assertTrue(DOCKER_VOLUME_NAME.matcher(name).matches(),
                "unsafe function-name chars must be sanitized, was: " + name);
    }

    @Test
    void differsAcrossCodeVersions() {
        String v1 = ContainerLauncher.codeVolumeName(fnWithSha("same-fn", "sha-version-one"));
        String v2 = ContainerLauncher.codeVolumeName(fnWithSha("same-fn", "sha-version-two"));

        assertNotEquals(v1, v2,
                "different codeSha256 must yield different volume names so a redeploy gets a fresh volume");
    }

    @Test
    void isStableForSameFunctionAndSha() {
        String a = ContainerLauncher.codeVolumeName(fnWithSha("stable-fn", "stable-sha-abc"));
        String b = ContainerLauncher.codeVolumeName(fnWithSha("stable-fn", "stable-sha-abc"));

        assertEquals(a, b, "same function + same sha must produce a stable volume name");
    }

    @Test
    void fallsBackToLastModifiedWhenShaMissing() {
        LambdaFunction nullSha = new LambdaFunction();
        nullSha.setFunctionName("no-sha-fn");
        nullSha.setCodeSha256(null);
        nullSha.setLastModified(1700000000000L);

        LambdaFunction blankSha = new LambdaFunction();
        blankSha.setFunctionName("no-sha-fn");
        blankSha.setCodeSha256("   ");
        blankSha.setLastModified(1700000000000L);

        String nullName = ContainerLauncher.codeVolumeName(nullSha);
        String blankName = ContainerLauncher.codeVolumeName(blankSha);

        assertTrue(DOCKER_VOLUME_NAME.matcher(nullName).matches(),
                "must be docker-safe even when falling back to lastModified, was: " + nullName);
        assertTrue(nullName.contains("1700000000000"),
                "null sha should key off lastModified, was: " + nullName);
        assertEquals(nullName, blankName, "null and blank sha should both fall back to lastModified");

        // A different lastModified (new deploy) must yield a different name.
        LambdaFunction newer = new LambdaFunction();
        newer.setFunctionName("no-sha-fn");
        newer.setLastModified(1700000009999L);
        assertNotEquals(nullName, ContainerLauncher.codeVolumeName(newer),
                "a later lastModified must produce a different volume name");
    }

    @TempDir
    Path tempDir;

    @Test
    void shouldUseCodeVolume_falseForSmallDir_trueWhenOverThreshold() throws Exception {
        Path smallDir = Files.createDirectory(tempDir.resolve("small"));
        Files.write(smallDir.resolve("a.txt"), new byte[1024]);   // 1 KiB

        Path bigDir = Files.createDirectory(tempDir.resolve("big"));
        Files.write(bigDir.resolve("a.bin"), new byte[8 * 1024]); // 8 KiB

        long original = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        try {
            // Override the threshold small so we don't have to write huge files.
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024; // 4 KiB
            assertFalse(ContainerLauncher.shouldUseCodeVolume(smallDir),
                    "1 KiB dir is below the 4 KiB threshold");
            assertTrue(ContainerLauncher.shouldUseCodeVolume(bigDir),
                    "8 KiB dir exceeds the 4 KiB threshold");
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = original;
        }
    }

    @Test
    void shouldUseCodeVolume_falseWhenDirMissing() {
        // IO errors (e.g. a non-existent dir) fall back to the direct copy.
        assertFalse(ContainerLauncher.shouldUseCodeVolume(tempDir.resolve("does-not-exist")),
                "a missing code dir should fall back to direct copy (false)");
    }
}
