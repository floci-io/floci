package io.floci.aws.core.common;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The host of an application UI Floci opens through {@link AppUiProxy}, {@code <secret>-<port>.<label>.<Floci's host>}:
 * the secret of the link, which is its authority, as no request to the interface carries AWS credentials; the port of
 * the interface; and, after the label of the service whose interface it is ({@link AppUiRoutes#hostLabel}), Floci's
 * own host and port, which {@code rest} holds from the dot before the label.
 */
public record AppUiHost(String secret, int port, String rest) {

    /** A secret's length: 26 characters of base32, 130 random bits. */
    public static final int SECRET_LENGTH = 26;
    // In lower case, as a host name is read without case.
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

    /** The host {@code hostHeader} names, or null where it is not an interface's host under {@code label}. */
    public static AppUiHost parse(String hostHeader, String label) {
        String host = hostHeader.toLowerCase(Locale.ROOT);
        int dot = host.indexOf('.');
        int dash = dot > 0 ? host.lastIndexOf('-', dot) : -1;
        if (dash != SECRET_LENGTH || !host.startsWith(label + ".", dot + 1)) {
            return null;
        }
        try {
            return new AppUiHost(host.substring(0, dash), Integer.parseInt(host.substring(dash + 1, dot)),
                    host.substring(dot));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A new link's secret, drawn from {@code random}. */
    public static String newSecret(SecureRandom random) {
        StringBuilder secret = new StringBuilder(SECRET_LENGTH);
        for (int i = 0; i < SECRET_LENGTH; i++) {
            secret.append(BASE32.charAt(random.nextInt(BASE32.length())));
        }
        return secret.toString();
    }

    /**
     * The URL of {@code path} on the interface on {@code port} of the link {@code secret}, under {@code label} on
     * Floci's {@code baseUrl}.
     */
    public static String url(String baseUrl, String secret, int port, String label, String path) {
        URI base = URI.create(baseUrl);
        return base.getScheme() + "://" + secret + "-" + port + "." + label + "." + base.getRawAuthority() + path;
    }

    /** The origin of the link's interface on {@code otherPort}, as a browser reaches it over {@code scheme}. */
    public String origin(String scheme, int otherPort) {
        return scheme + "://" + secret + "-" + otherPort + rest;
    }
}
