package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.VersionComponent;
import com.github.dockerjava.api.model.VersionPlatform;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerJavaNativeSupportTest {

    @Test
    void versionCommandResponseTypesAreRegisteredForNativeReflection() {
        RegisterForReflection registration = DockerJavaNativeSupport.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(registration);
        List<String> registered = Arrays.asList(registration.classNames());
        for (Class<?> type : List.of(Version.class, VersionComponent.class, VersionPlatform.class)) {
            assertTrue(registered.contains(type.getName()), type.getName());
        }
    }
}
