package io.github.hectorvent.floci.core.common.dns;

import java.util.List;

/**
 * What a {@link DnsRecordSource} answers for a name inside a zone it owns: the A records with the
 * TTL the zone publishes for them, an existing name without A records, or an absent name. A name
 * the source does not own is an empty optional, not an answer.
 *
 * <p>The TTL is per answer rather than per record: every address behind one Cloud Map service
 * shares that service's TTL, which is how Route 53 publishes a record set.
 */
public record DnsAnswer(List<String> addresses, int ttlSeconds, boolean nameExists) {

    /** What the DNS server publishes for a name whose zone declares no TTL of its own. */
    public static final int DEFAULT_TTL_SECONDS = 60;

    private static final DnsAnswer NX_DOMAIN = new DnsAnswer(List.of(), DEFAULT_TTL_SECONDS, false);
    private static final DnsAnswer NO_DATA = new DnsAnswer(List.of(), DEFAULT_TTL_SECONDS, true);

    public DnsAnswer {
        // A TTL is an unsigned 31-bit field; RFC 2181 has a resolver read anything with the top
        // bit set as zero. A source picks its own fallback rather than putting one on the wire.
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("DNS TTL must not be negative: " + ttlSeconds);
        }
        addresses = List.copyOf(addresses);
    }

    /** A records for an existing name, all published with the same TTL. */
    public static DnsAnswer records(List<String> addresses, int ttlSeconds) {
        return new DnsAnswer(addresses, ttlSeconds, true);
    }

    /** An existing name without A records: NOERROR with no answers. */
    public static DnsAnswer noData() {
        return NO_DATA;
    }

    /** An absent name inside an owned zone: NXDOMAIN. */
    public static DnsAnswer nxDomain() {
        return NX_DOMAIN;
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }
}
