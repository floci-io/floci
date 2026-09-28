package io.github.hectorvent.floci.services.eks;

import java.util.Objects;

/**
 * Represents a link-local network endpoint (IP address and TCP port)
 * routed from pod network namespaces to local node listeners.
 */
public record LinkLocalEndpoint(String ip, int port) {

    public LinkLocalEndpoint {
        Objects.requireNonNull(ip, "ip cannot be null");
        if (ip.isBlank()) {
            throw new IllegalArgumentException("ip cannot be blank");
        }
        if (!isValidIpv4Literal(ip.trim())) {
            throw new IllegalArgumentException("ip must be a valid IPv4 address literal: " + ip);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        ip = ip.trim();
    }

    private static boolean isValidIpv4Literal(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            int val = Integer.parseInt(part);
            if (val < 0 || val > 255) {
                return false;
            }
            if (part.length() > 1 && part.startsWith("0")) {
                return false;
            }
        }
        return true;
    }
}
