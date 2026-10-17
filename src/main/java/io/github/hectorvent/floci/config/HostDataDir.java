package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.quarkus.runtime.ImageMode;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where a standalone Floci binary keeps its state when nothing says otherwise.
 *
 * <p>The bundled default, {@code ./data}, is right for the image (which sets
 * {@code /app/data}) and for a developer running from the checkout, but a native binary on the
 * user's PATH would write into whatever directory it happens to be started from. A native run
 * outside a container therefore defaults to the platform's per-user data directory instead.
 * The JVM, dev mode and the image keep {@code ./data}.
 */
public final class HostDataDir {

    static final String BUNDLED_DEFAULT = "./data";
    static final String DIR_NAME = "floci-aws";

    private HostDataDir() {
    }

    /** The persistent path to use when none is configured. */
    public static String persistentPathDefault() {
        if (!applies()) {
            return BUNDLED_DEFAULT;
        }
        return forPlatform(System.getProperty("os.name", ""), System.getenv(),
                System.getProperty("user.home", ".")).toString();
    }

    static boolean applies() {
        return ImageMode.current() == ImageMode.NATIVE_RUN && !new ContainerDetector().isRunningInContainer();
    }

    static Path forPlatform(String osName, Map<String, String> env, String userHome) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String localAppData = env.get("LOCALAPPDATA");
            Path base = isSet(localAppData) ? Path.of(localAppData) : Path.of(userHome, "AppData", "Local");
            return base.resolve(DIR_NAME);
        }
        if (os.startsWith("mac")) {
            return Path.of(userHome, "Library", "Application Support", DIR_NAME);
        }
        String xdgDataHome = env.get("XDG_DATA_HOME");
        Path base = isSet(xdgDataHome) ? Path.of(xdgDataHome) : Path.of(userHome, ".local", "share");
        return base.resolve(DIR_NAME);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
