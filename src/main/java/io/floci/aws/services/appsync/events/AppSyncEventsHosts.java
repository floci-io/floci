package io.floci.aws.services.appsync.events;

import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The host forms of an Events API: {@code <apiId>.appsync-api.<region>.<suffix>} for HTTP publish
 * and {@code <apiId>.appsync-realtime-api.<region>.<suffix>} for the WebSocket. The suffix is the
 * partition's AWS DNS suffix, {@code localhost}, one of Floci's built-in suffixes, or the configured
 * hostname; the built-in suffixes are also accepted without a region, the grammar the execute-api
 * host filter uses.
 */
public final class AppSyncEventsHosts {

    public static final String HTTP_LABEL = "appsync-api";
    public static final String REALTIME_LABEL = "appsync-realtime-api";

    private static final Pattern HOST = Pattern.compile("^([a-z0-9]+)\\.([a-z-]+)\\.(.+)$", Pattern.CASE_INSENSITIVE);

    private AppSyncEventsHosts() {
    }

    /** The API id of an Events host with this service label, or null when the host is not one. */
    public static String extractApiId(String host, String label, String baseHostname) {
        if (host == null) {
            return null;
        }
        Matcher matcher = HOST.matcher(stripPort(host));
        if (!matcher.matches() || !label.equalsIgnoreCase(matcher.group(2))) {
            return null;
        }
        String tail = matcher.group(3);
        if (isBuiltInSuffix(tail)) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        int dot = tail.indexOf('.');
        if (dot <= 0 || !RegionResolver.isKnownRegion(tail.substring(0, dot))) {
            return null;
        }
        String endpointHost = tail.substring(dot + 1);
        if ("localhost".equalsIgnoreCase(endpointHost)
                || isBuiltInSuffix(endpointHost)
                || AwsPartitions.isDnsSuffix(endpointHost)
                || (baseHostname != null && baseHostname.equalsIgnoreCase(endpointHost))) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean isBuiltInSuffix(String host) {
        for (String suffix : EmbeddedDnsServer.BUILTIN_SUFFIXES) {
            if (suffix.equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    private static String stripPort(String host) {
        int colon = host.lastIndexOf(':');
        if (colon > 0 && colon < host.length() - 1 && host.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            return host.substring(0, colon);
        }
        return host;
    }
}
