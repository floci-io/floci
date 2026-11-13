package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DockerPlatformsTest {

    @Test
    void anAwsArchitectureInEitherCaseRunsOnItsDockerPlatform() {
        // EMR Serverless and ECS spell them in capitals, Lambda and EC2 in lower case.
        assertEquals("linux/amd64", DockerPlatforms.forArchitecture("X86_64"));
        assertEquals("linux/amd64", DockerPlatforms.forArchitecture("x86_64"));
        assertEquals("linux/arm64", DockerPlatforms.forArchitecture("ARM64"));
        assertEquals("linux/arm64", DockerPlatforms.forArchitecture("arm64"));
    }

    @Test
    void noArchitectureOrAnUnknownOneLeavesTheDockerHostsOwn() {
        assertNull(DockerPlatforms.forArchitecture(null));
        assertNull(DockerPlatforms.forArchitecture("s390x"));
    }
}
