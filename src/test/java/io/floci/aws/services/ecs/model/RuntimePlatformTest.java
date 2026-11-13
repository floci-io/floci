package io.floci.aws.services.ecs.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimePlatformTest {

    @Test
    void aTaskRunsItsImagesForItsCpuArchitecture() {
        assertEquals("linux/amd64", new RuntimePlatform("X86_64", "LINUX").dockerPlatform());
        assertEquals("linux/arm64", new RuntimePlatform("ARM64", "LINUX").dockerPlatform());
    }

    @Test
    void aTaskThatNamesNoCpuArchitectureRunsOnTheDockerHostsOwn() {
        assertNull(new RuntimePlatform(null, "LINUX").dockerPlatform());
    }
}
