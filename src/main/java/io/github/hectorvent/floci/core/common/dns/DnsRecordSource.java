package io.github.hectorvent.floci.core.common.dns;

import java.util.List;
import java.util.Optional;

/**
 * A source of A records that {@link EmbeddedDnsServer} consults before forwarding a query
 * upstream. Implemented by services that own a private DNS zone, so the DNS server stays
 * independent of them: it discovers implementations through CDI rather than importing one.
 *
 * <p>An implementation answers only for names inside a zone it owns. An empty optional leaves
 * the query to the next source or the upstream resolvers; a present empty list means the name
 * is owned but has no A records. It runs outside any request context.
 */
public interface DnsRecordSource {

    /**
     * Resolves a query name to IPv4 addresses. An empty optional means this source does not own
     * the name. The name arrives without a trailing dot and in the case the client sent.
     */
    Optional<List<String>> resolveIpv4(String name);
}
