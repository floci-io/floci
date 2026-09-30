package io.github.hectorvent.floci.core.common.dns;

import java.util.List;

/**
 * A source of {@link DnsForwardingRule}s that {@link EmbeddedDnsServer} consults before resolving a
 * query itself, discovered through CDI so the DNS server never imports the service that stores them.
 *
 * <p>The source is asked for the rules of one VPC, in one account and one region, so association
 * and scoping are its decision; picking between several matching rules is the DNS server's, which
 * compares them on domain specificity. Returning the rules most specific first makes that choice
 * deterministic when two rules tie.
 *
 * <p>It runs outside any request context, on a worker thread.
 */
public interface DnsForwardingRuleSource {

    /**
     * The rules {@code accountId} has associated with {@code vpcId} in {@code region}, none of which
     * is blank. An empty list leaves resolution exactly as it is without this source.
     */
    List<DnsForwardingRule> rulesFor(String accountId, String region, String vpcId);
}
