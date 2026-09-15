package io.github.hectorvent.floci.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Refuses to start Floci on an address outside loopback unless
 * {@code floci.security.allow-unsafe-network-exposure} is set: anyone who can reach Floci's port
 * can call its APIs.
 *
 * <p>The check runs before every other startup observer and before Quarkus opens its HTTP socket.
 * In TLS mode {@link TlsProxyServer} owns the public port and applies the same check before it binds.
 */
@ApplicationScoped
public class NetworkExposureGuard {

    private static final String HTTP_HOST = "quarkus.http.host";
    /** Quarkus's own production default, for a configuration that sets no host at all. */
    private static final String QUARKUS_DEFAULT_HOST = "0.0.0.0";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    // Starts with a hex digit or a colon, which InetAddress parses as a literal and never resolves.
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F]*:[0-9a-fA-F:.]*");
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                    + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*");

    private final EmulatorConfig config;
    private final Config mpConfig;

    @Inject
    public NetworkExposureGuard(EmulatorConfig config, Config mpConfig) {
        this.config = config;
        this.mpConfig = mpConfig;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ignored) {
        requireConsent(publicHost(mpConfig), config.security());
    }

    /**
     * The address Floci's public listener binds: Quarkus itself, or {@link TlsProxyServer} in TLS
     * mode. {@link TlsConfigSource} pins {@code quarkus.http.host} to loopback for the internal
     * backends, so when the effective value is that pin the configured host is the next source's.
     */
    static String publicHost(Config config) {
        ConfigValue effective = config.getConfigValue(HTTP_HOST);
        if (effective.getValue() != null && !TlsConfigSource.NAME.equals(effective.getSourceName())) {
            return effective.getValue();
        }
        for (ConfigSource source : config.getConfigSources()) {
            String host = source.getValue(HTTP_HOST);
            if (host != null && !TlsConfigSource.NAME.equals(source.getName())) {
                return host;
            }
        }
        return QUARKUS_DEFAULT_HOST;
    }

    static void requireConsent(String host, EmulatorConfig.SecurityConfig security) {
        if (!isValidHost(host)) {
            throw new IllegalStateException("Refusing to listen on malformed host: " + host);
        }
        if (isLoopback(host) || security.allowUnsafeNetworkExposure()) {
            return;
        }
        throw new IllegalStateException("Refusing to listen on " + host
                + ": it is not a loopback address, so anyone who can reach it can call Floci's APIs."
                + " Set quarkus.http.host (QUARKUS_HTTP_HOST) to 127.0.0.1, or set"
                + " floci.security.allow-unsafe-network-exposure (FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE)"
                + " to true to allow it.");
    }

    /** True for 127.0.0.0/8, ::1 and localhost. Only IP literals are parsed; no name is resolved. */
    static boolean isLoopback(String host) {
        String literal = host.strip();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (IPV4.matcher(literal).matches()) {
            return literal.startsWith("127.");
        }
        if (!IPV6.matcher(literal).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isValidHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String literal = host.strip();
        boolean bracketed = literal.startsWith("[") || literal.endsWith("]");
        if (bracketed) {
            if (!(literal.startsWith("[") && literal.endsWith("]"))) {
                return false;
            }
            literal = literal.substring(1, literal.length() - 1);
        }
        if (IPV4.matcher(literal).matches()) {
            for (String part : literal.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return !bracketed;
        }
        if (literal.indexOf(':') >= 0) {
            if (!IPV6.matcher(literal).matches()) {
                return false;
            }
            try {
                return InetAddress.getByName(literal).getHostAddress() != null;
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return !bracketed && HOSTNAME.matcher(literal).matches();
    }
}
