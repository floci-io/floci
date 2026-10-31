package io.github.hectorvent.floci.testutil;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that a background worker stops before EmulatorLifecycle.onStop flushes and closes storage. */
public final class ShutdownOrdering {
    private ShutdownOrdering() {
    }

    /** The class's {@code onStop(ShutdownEvent)} observer must carry a priority below APPLICATION. */
    public static void assertStopsBeforeStorageCloses(Class<?> owner) throws NoSuchMethodException {
        Method onStop = owner.getDeclaredMethod("onStop", ShutdownEvent.class);
        Annotation priority = Arrays.stream(onStop.getParameterAnnotations()[0])
                .filter(Priority.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError(owner.getSimpleName() + ".onStop has no @Priority"));
        assertTrue(((Priority) priority).value() < Interceptor.Priority.APPLICATION,
                owner.getSimpleName() + ".onStop must run before EmulatorLifecycle.onStop");
    }
}
