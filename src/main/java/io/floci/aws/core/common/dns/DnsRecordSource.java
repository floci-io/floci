package io.floci.aws.core.common.dns;

import java.util.Optional;

/**
 * A source of A records that {@link EmbeddedDnsServer} consults before forwarding a query
 * upstream. Implemented by services that own a private DNS zone, so the DNS server stays
 * independent of them: it discovers implementations through CDI rather than importing one.
 *
 * <p>An implementation answers only for names inside a zone it owns. An empty optional leaves
 * the query to the next source or the upstream resolvers; a present answer with no addresses
 * means the name is owned but has no A records. It runs outside any request context.
 */
public interface DnsRecordSource {

    /** Stored public authority data only; this lookup must not perform recursive resolution. */
    default Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
        return Optional.empty();
    }

    /** Supplies the receiving listener's address to sources that resolve local alias targets. */
    default Optional<DnsAnswer> resolveAuthoritative(String name, int type, String listenerAddress) {
        return resolveAuthoritative(name, type);
    }

    /** Resolves a stored resource's alias target without recursive DNS or suffix substitution. */
    default Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, String listenerAddress) {
        return Optional.empty();
    }

    /** A null origin identifies a query outside any known VPC. */
    default Optional<DnsAnswer> resolveAuthoritative(String name, int type, DnsClientVpcSource.ClientVpc origin) {
        return resolveAuthoritative(name, type);
    }

    /** Carries the listener address and querying VPC through authoritative lookup. */
    default Optional<DnsAnswer> resolveAuthoritative(String name, int type, String listenerAddress,
                                                     DnsClientVpcSource.ClientVpc origin) {
        return origin == null ? resolveAuthoritative(name, type, listenerAddress)
                : resolveAuthoritative(name, type, origin);
    }

    /**
     * Resolves a query name to IPv4 addresses and their TTL. An empty optional means this
     * source does not own the name. The name arrives without a trailing dot and in the case the client sent.
     */
    Optional<DnsAnswer> resolveIpv4(String name);

    /** A source that only implements IPv4 still preserves name ownership for other query types. */
    default Optional<DnsAnswer> resolve(String name, int type) {
        return resolveIpv4(name).map(answer -> type == 1 ? answer
                : answer.nameExists() ? DnsAnswer.noData() : DnsAnswer.nxDomain());
    }

    /** Private record sources must restrict their answers to the querying VPC's associations. */
    default Optional<DnsAnswer> resolve(String name, int type, DnsClientVpcSource.ClientVpc origin) {
        return resolve(name, type);
    }
}
