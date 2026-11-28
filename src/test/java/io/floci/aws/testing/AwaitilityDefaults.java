package io.floci.aws.testing;

import io.quarkus.test.junit.callback.QuarkusTestBeforeClassCallback;
import org.awaitility.Awaitility;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.time.Duration;

/**
 * Awaitility sleeps one poll interval before its first check unless told otherwise, so every
 * {@code await()} paid 100 ms even when its condition already held. This makes the first check
 * immediate for the whole suite; an await that needs a delay sets {@code pollDelay} itself.
 *
 * <p>Awaitility's defaults are static, and a {@code @QuarkusTest} class sees its own copy of
 * Awaitility, loaded by the Quarkus runtime class loader. So the class is registered twice: as a
 * launcher session listener for the copy plain test classes see, and as a Quarkus before-class
 * callback, which Quarkus loads through the runtime class loader of each test application.
 */
public class AwaitilityDefaults implements LauncherSessionListener, QuarkusTestBeforeClassCallback {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        applyDefaults();
    }

    @Override
    public void beforeClass(Class<?> testClass) {
        applyDefaults();
    }

    private static void applyDefaults() {
        Awaitility.setDefaultPollDelay(Duration.ZERO);
    }
}
