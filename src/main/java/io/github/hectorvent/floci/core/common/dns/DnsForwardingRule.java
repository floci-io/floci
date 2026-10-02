package io.github.hectorvent.floci.core.common.dns;

import java.util.List;
import java.util.Locale;

/**
 * One rule that steers a DNS query away from the resolution {@link EmbeddedDnsServer} would perform
 * on its own. A rule with targets sends a matching query to those resolvers, as a Route 53 Resolver
 * {@code FORWARD} rule does; a rule with no targets sends it back to Floci's own resolution, as a
 * {@code SYSTEM} rule does when it carves a subdomain out of a broader forwarding rule.
 *
 * <p>Matching follows Route 53 Resolver: a rule matches a query name that equals its domain name or
 * is a subdomain of it, and where several rules match, the one with the most labels in its domain
 * name wins. The root domain, which AWS spells {@code "."}, matches every name and is the least
 * specific rule there is.
 */
public record DnsForwardingRule(String domainName, List<Target> targets) {

    /** A resolver a matching query is forwarded to. Route 53 Resolver defaults the port to 53. */
    public record Target(String address, int port) {

        public static final int DEFAULT_DNS_PORT = 53;

        public Target(String address) {
            this(address, DEFAULT_DNS_PORT);
        }
    }

    public DnsForwardingRule {
        domainName = normalize(domainName);
        targets = List.copyOf(targets);
    }

    /** A rule that forwards matching queries to {@code targets}. */
    public static DnsForwardingRule forwardTo(String domainName, List<Target> targets) {
        return new DnsForwardingRule(domainName, targets);
    }

    /** A rule that hands matching queries back to Floci's own resolution. */
    public static DnsForwardingRule system(String domainName) {
        return new DnsForwardingRule(domainName, List.of());
    }

    public boolean forwards() {
        return !targets.isEmpty();
    }

    /** Whether {@code queryName} is this rule's domain name or a subdomain of it. */
    public boolean matches(String queryName) {
        if (queryName == null || queryName.isBlank()) {
            return false;
        }
        if (domainName.isEmpty()) {
            return true;
        }
        String query = normalize(queryName);
        return query.equals(domainName) || query.endsWith("." + domainName);
    }

    /**
     * How specific this rule's domain name is, as its label count. The root domain scores zero,
     * so any named rule outranks a rule for {@code "."}.
     */
    public int specificity() {
        return domainName.isEmpty() ? 0 : domainName.split("\\.").length;
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
