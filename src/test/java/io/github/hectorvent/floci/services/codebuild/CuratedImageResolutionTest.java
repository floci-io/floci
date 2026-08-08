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

    private CodeBuildRunner runner() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().curatedImageSubstitute())
                .thenReturn("public.ecr.aws/codebuild/amazonlinux-x86_64-standard:5.0");
        return new CodeBuildRunner(null, null, null, null, null, null, null, config, null, null);
    }

    @Test
    void amazonLinuxCuratedNamesMapToPublicEcrMirrors() {
        assertEquals("public.ecr.aws/codebuild/amazonlinux-x86_64-standard:5.0",
                runner().resolveCuratedImage("aws/codebuild/amazonlinux-x86_64-standard:5.0"));
    }

    @Test
    void ubuntuStandardFamilyFallsBackToSubstitute() {
        assertEquals("public.ecr.aws/codebuild/amazonlinux-x86_64-standard:5.0",
                runner().resolveCuratedImage("aws/codebuild/standard:7.0"));
    }

    @Test
    void nonCuratedImagesPassThrough() {
        assertEquals("ubuntu:22.04", runner().resolveCuratedImage("ubuntu:22.04"));
        assertEquals("public.ecr.aws/lambda/nodejs:22",
                runner().resolveCuratedImage("public.ecr.aws/lambda/nodejs:22"));
        assertNull(runner().resolveCuratedImage(null));
    }
}
