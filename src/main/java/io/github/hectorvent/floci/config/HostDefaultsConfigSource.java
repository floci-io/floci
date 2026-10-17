package io.github.hectorvent.floci.config;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Defaults for a native binary running outside a container: {@link HostDataDir}'s per-user data
 * directory as the storage path, and a Docker resource namespace of {@code host-<port>}.
 *
 * <p>The namespace keeps the binary's child containers and volumes apart from those of an image,
 * or of a second binary on another port, sharing the same Docker daemon. Without it, both would
 * name an RDS instance {@code mydb} the same, and the one created second would remove the
 * other's container before starting its own.
 *
 * <p>The ordinal sits just above the bundled {@code application.yml} (255) and below every source
 * a user sets explicitly: {@code config/application.yml} in the working directory (265),
 * {@code .env} (295), environment variables (300) and system properties (400). So it only
 * replaces the bundled {@code ./data}; any path the user configures still wins. It is empty for
 * the JVM, dev mode and the image.
 */
public class HostDefaultsConfigSource implements ConfigSource {

    static final int ORDINAL = 260;
    static final String PERSISTENT_PATH = "floci.storage.persistent-path";
    static final String HOST_PERSISTENT_PATH = "floci.storage.host-persistent-path";
    static final String FOLLOW_PERSISTENT_PATH = "${" + PERSISTENT_PATH + "}";
    static final String RESOURCE_NAMESPACE = "floci.docker.resource-namespace";
    static final String HOST_NAMESPACE = "host-${floci.port}";

    private final Map<String, String> properties;

    public HostDefaultsConfigSource() {
        this(HostDataDir.applies());
    }

    HostDefaultsConfigSource(boolean nativeOnHost) {
        if (nativeOnHost) {
            String dir = HostDataDir.persistentPathDefault();
            // The binary and Docker share the host filesystem, so child containers bind the same path,
            // including one the user sets in config/application.yml.
            this.properties = Map.of(PERSISTENT_PATH, dir, HOST_PERSISTENT_PATH, FOLLOW_PERSISTENT_PATH,
                    RESOURCE_NAMESPACE, HOST_NAMESPACE);
        } else {
            this.properties = Map.of();
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "floci-host-defaults";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
