package io.github.hectorvent.floci.config;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Serves {@link LocalStackParity}'s translation as configuration.
 *
 * <p>The ordinal (299) sits just below environment variables (300), and above {@code .env} (295)
 * and {@code config/application.yml} (265), as the {@code FLOCI_*} variables the replaced shell
 * script exported did. A translated key is only present when its Floci variable is unset, so it
 * never shadows one. Staying below 300 also keeps {@link TlsConfigSource}'s loopback pin of
 * {@code quarkus.http.host} in force when TLS is on and {@code GATEWAY_LISTEN} names the public
 * address, which {@link NetworkExposureGuard#publicHost} still reads from here.
 */
public class LocalStackParityConfigSource implements ConfigSource {

    static final int ORDINAL = 299;

    private final Map<String, String> properties;

    public LocalStackParityConfigSource() {
        this(LocalStackParity.forCurrentEnvironment());
    }

    LocalStackParityConfigSource(Map<String, String> properties) {
        this.properties = Map.copyOf(properties);
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
        return "floci-localstack-parity";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
