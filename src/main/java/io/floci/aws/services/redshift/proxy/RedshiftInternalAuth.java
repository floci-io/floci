package io.floci.aws.services.redshift.proxy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for short-lived, single-use authentication tokens used to identify internal
 * connections originating from trusted Floci services (such as Redshift Data API) through the proxy.
 */
public final class RedshiftInternalAuth {

    private static final String DATA_API_PREFIX = "floci-redshift-data:";
    private static final long TICKET_EXPIRY_MILLIS = 30_000L;
    private static final Map<String, Long> ACTIVE_TICKETS = new ConcurrentHashMap<>();

    private RedshiftInternalAuth() {
    }

    /**
     * Issues a single-use token identifying an internal Data API connection.
     * The returned value is formatted as the PostgreSQL {@code application_name}.
     */
    public static String issueDataApiToken() {
        cleanExpiredTickets();
        String ticket = UUID.randomUUID().toString();
        ACTIVE_TICKETS.put(ticket, System.currentTimeMillis() + TICKET_EXPIRY_MILLIS);
        return DATA_API_PREFIX + ticket;
    }

    /**
     * Validates and consumes a single-use token from the connection's startup parameters.
     * Returns {@code true} only if the token matches a valid registered ticket that has not expired.
     */
    public static boolean consumeDataApiToken(String applicationName) {
        if (applicationName == null || !applicationName.startsWith(DATA_API_PREFIX)) {
            return false;
        }
        String ticket = applicationName.substring(DATA_API_PREFIX.length());
        Long expiry = ACTIVE_TICKETS.remove(ticket);
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    private static void cleanExpiredTickets() {
        long now = System.currentTimeMillis();
        ACTIVE_TICKETS.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
