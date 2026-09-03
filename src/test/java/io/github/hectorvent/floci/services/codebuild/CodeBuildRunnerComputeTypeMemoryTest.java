package io.github.hectorvent.floci.services.codebuild;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Build containers are the only launched container type (unlike Lambda/ECS/Batch) that
 * previously got no memory cap at all — an unbounded CodeBuild container run alongside
 * others during a multi-stage fan-out is a confirmed contributor to guest VM memory
 * exhaustion (see issues/0005). This resolves AWS's published computeType memory tiers
 * so every build container gets a real cgroup limit.
 */
class CodeBuildRunnerComputeTypeMemoryTest {

    @Test
    void knownComputeTypesResolveToTheirPublishedMemoryTier() {
        assertEquals(3072, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_GENERAL1_SMALL"));
        assertEquals(7168, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_GENERAL1_MEDIUM"));
        assertEquals(15360, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_GENERAL1_LARGE"));
        assertEquals(145 * 1024, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_GENERAL1_2XLARGE"));
        assertEquals(1024, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_LAMBDA_1GB"));
        assertEquals(2048, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_LAMBDA_2GB"));
        assertEquals(4096, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_LAMBDA_4GB"));
        assertEquals(8192, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_LAMBDA_8GB"));
        assertEquals(10240, CodeBuildRunner.resolveComputeTypeMemoryMb("BUILD_LAMBDA_10GB"));
    }

    @Test
    void nullComputeTypeFallsBackToASafeDefaultInsteadOfBeingUnbounded() {
        assertEquals(3072, CodeBuildRunner.resolveComputeTypeMemoryMb(null));
    }

    @Test
    void unrecognizedComputeTypeFallsBackToASafeDefaultInsteadOfBeingUnbounded() {
        assertEquals(3072, CodeBuildRunner.resolveComputeTypeMemoryMb("SOME_FUTURE_TIER_WE_DONT_KNOW_YET"));
    }
}
