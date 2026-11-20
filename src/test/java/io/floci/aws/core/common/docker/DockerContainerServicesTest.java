package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerContainerServicesTest {

    @AfterEach
    void forgetTheTestContainers() {
        DockerContainerServices.forget("services-test-id");
        DockerContainerServices.forget("services-test-unlabelled");
        DockerContainerServices.forget("services-test-old");
        DockerContainerServices.forget("services-test-name");
    }

    @Test
    void aContainerWithoutTheServiceLabelIsUnattributed() {
        DockerContainerServices.record("services-test-unlabelled", null, Map.of("floci", "true"));

        assertEquals(DockerContainerServices.UNATTRIBUTED,
                DockerContainerServices.serviceOf("services-test-unlabelled"));
        assertEquals(DockerContainerServices.UNATTRIBUTED, DockerContainerServices.serviceOf(null));
    }

    // Catches: forgetting a container removed outside the transport also wiping a newer container's
    // entry under the same name, so that one's name-keyed streams read as unattributed.
    @Test
    void forgettingAnOldContainerLeavesANewerOneOfTheSameNameAttributed() {
        DockerContainerServices.record("services-test-old", "services-test-name",
                Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda"));
        DockerContainerServices.record("services-test-id", "services-test-name",
                Map.of(ContainerStorageHelper.SERVICE_LABEL, "ecs"));

        DockerContainerServices.forget("services-test-old");

        assertEquals("ecs", DockerContainerServices.serviceOf("services-test-name"));
        assertEquals("ecs", DockerContainerServices.serviceOf("services-test-id"));
    }

    @Test
    void aContainerIsKnownByItsIdAndItsNameUntilEitherIsForgotten() {
        DockerContainerServices.record("services-test-id", "services-test-name",
                Map.of(ContainerStorageHelper.SERVICE_LABEL, "opensearch"));
        assertEquals("opensearch", DockerContainerServices.serviceOf("services-test-id"));
        assertEquals("opensearch", DockerContainerServices.serviceOf("services-test-name"));

        DockerContainerServices.forget("services-test-name");

        assertEquals(DockerContainerServices.UNATTRIBUTED, DockerContainerServices.serviceOf("services-test-id"));
        assertEquals(DockerContainerServices.UNATTRIBUTED, DockerContainerServices.serviceOf("services-test-name"));
    }
}
