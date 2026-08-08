package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Curated {@code aws/codebuild/*} image names must resolve to something pullable:
 * Amazon Linux names map to their public.ecr.aws mirrors, the Ubuntu standard family
 * (never published publicly) runs the configured substitute.
 */
class CuratedImageResolutionTest {

    private CodeBuildRunner runner(String substitute) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().curatedImageSubstitute())
                .thenReturn(java.util.Optional.ofNullable(substitute));
        return new CodeBuildRunner(null, null, null, null, null, null, null, config, null, null);
    }

    private CodeBuildRunner runner() {
        return runner(null);
    }

    @Test
    void amazonLinuxCuratedNamesMapToPublicEcrMirrors() {
        assertEquals("public.ecr.aws/codebuild/amazonlinux-x86_64-standard:5.0",
                runner().resolveCuratedImage("aws/codebuild/amazonlinux-x86_64-standard:5.0"));
    }

    @Test
    void ubuntuStandardFamilyFallsBackToArchAwareDefault() {
        String resolved = runner().resolveCuratedImage("aws/codebuild/standard:7.0");
        assertEquals(CodeBuildRunner.defaultCuratedSubstitute(System.getProperty("os.arch", "")),
                resolved);
    }

    @Test
    void configuredSubstituteWinsOverArchDefault() {
        assertEquals("my-registry/custom-build:1",
                runner("my-registry/custom-build:1").resolveCuratedImage("aws/codebuild/standard:7.0"));
    }

    @Test
    void defaultSubstituteFollowsHostArchitecture() {
        assertEquals("public.ecr.aws/codebuild/amazonlinux-x86_64-standard:6.0",
                CodeBuildRunner.defaultCuratedSubstitute("amd64"));
        assertEquals("public.ecr.aws/codebuild/amazonlinux-aarch64-standard:4.0",
                CodeBuildRunner.defaultCuratedSubstitute("aarch64"));
    }

    @Test
    void nonCuratedImagesPassThrough() {
        assertEquals("ubuntu:22.04", runner().resolveCuratedImage("ubuntu:22.04"));
        assertEquals("public.ecr.aws/lambda/nodejs:22",
                runner().resolveCuratedImage("public.ecr.aws/lambda/nodejs:22"));
        assertNull(runner().resolveCuratedImage(null));
    }
}
