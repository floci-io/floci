package io.github.hectorvent.floci.services.quicksight;

import java.net.URI;
import java.util.Locale;

final class QuickSightOrigins {
    private QuickSightOrigins() {}

    static String fromUrl(String value) {
        if (value == null) {
            return "";
        }
        try {
            return normalize(URI.create(value));
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    static String normalize(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() > 65535) {
            return "";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return "";
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || "https".equals(scheme) && port == 443
                || "http".equals(scheme) && port == 80;
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
