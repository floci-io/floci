package io.github.hectorvent.floci.services.rdsdata;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JdbcDriverNativeSupportTest {

    @Test
    void everyRegisteredClassNameExistsInTheDrivers() {
        RegisterForReflection registration = JdbcDriverNativeSupport.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(registration);
        ClassLoader loader = JdbcDriverNativeSupport.class.getClassLoader();
        for (String className : registration.classNames()) {
            assertDoesNotThrow(() -> Class.forName(className, false, loader), className);
        }
    }
}
