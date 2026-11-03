package io.github.hectorvent.floci.services.elbv2;

import org.jboss.logging.Logger;

import java.net.URI;

/** ALB's default Host rewrite uses the request authority and listener, never the target address. */
final class ElbV2HostHeader {

    private static final Logger LOG = Logger.getLogger(ElbV2HostHeader.class);

    private ElbV2HostHeader() {
    }

    static String normalize(String requestTarget, String authority, int listenerPort) {
        if (requestTarget != null && (requestTarget.regionMatches(true, 0, "http://", 0, 7)
                || requestTarget.regionMatches(true, 0, "https://", 0, 8))) {
            try {
                URI uri = URI.create(requestTarget);
                if (uri.getRawAuthority() != null && uri.getRawUserInfo() == null) {
                    authority = uri.getRawAuthority();
                }
            } catch (IllegalArgumentException error) {
                LOG.debug("Unparseable absolute request target retains the supplied Host authority", error);
            }
        }
        if (authority == null || authority.isEmpty()) {
            return authority;
        }
        int separator = portSeparator(authority);
        if (listenerPort == 80 || listenerPort == 443) {
            return separator >= 0 ? authority.substring(0, separator) : authority;
        }
        return separator >= 0 ? authority : authority + ":" + listenerPort;
    }

    private static int portSeparator(String authority) {
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end >= 0 && end + 1 < authority.length() && authority.charAt(end + 1) == ':' ? end + 1 : -1;
        }
        int colon = authority.indexOf(':');
        return colon == authority.lastIndexOf(':') ? colon : -1;
    }
}
