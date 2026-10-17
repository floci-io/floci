package io.github.hectorvent.floci.config;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version this Floci build reports on {@code /health}, {@code /_floci/health} and in its
 * certificate metadata.
 *
 * <p>{@code FLOCI_VERSION} wins when set, so an image can still override it. Otherwise the
 * version stamped into {@code floci/version.properties} by the Maven build is used, which is what
 * a standalone native binary reports. {@code "dev"} remains only for a build that carries no
 * stamped version, such as an IDE run without resource processing.
 *
 * <p>Plain static code on purpose: {@link TlsConfigSource} runs while the configuration is still
 * being assembled, before CDI or {@code ConfigProvider} can be used.
 */
public final class FlociVersion {

    private static final Logger LOG = Logger.getLogger(FlociVersion.class);

    static final String RESOURCE = "floci/version.properties";
    static final String FALLBACK = "dev";

    private static final String BUILD_VERSION = loadBuildVersion();

    private FlociVersion() {
    }

    public static String current() {
        return resolve(System.getenv("FLOCI_VERSION"), BUILD_VERSION);
    }

    static String resolve(String override, String buildVersion) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (buildVersion != null && !buildVersion.isBlank()) {
            return buildVersion;
        }
        return FALLBACK;
    }

    private static String loadBuildVersion() {
        try (InputStream in = FlociVersion.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            // An unfiltered copy (resources not processed by Maven) still holds the placeholder.
            if (version == null || version.startsWith("${")) {
                return null;
            }
            return version.trim();
        } catch (IOException e) {
            LOG.debugv("Could not read {0}: {1}", RESOURCE, e.getMessage());
            return null;
        }
    }
}
