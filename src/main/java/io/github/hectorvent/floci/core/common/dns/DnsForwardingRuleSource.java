package io.github.hectorvent.floci.core.common.dns;

import java.util.List;

/**
 * A source of {@link DnsForwardingRule}s that {@link EmbeddedDnsServer} consults before resolving a
 * query itself, discovered through CDI so the DNS server never imports the service that stores them.
 *
 * <p>The source is asked for the rules of one VPC, so association scoping is its decision; picking
 * between several matching rules is the DNS server's, which compares them on domain specificity.
 * Returning the rules most specific first makes that choice deterministic when two rules tie.
 *
 * <p>It runs outside any request context, on a worker thread.
 */
public interface DnsForwardingRuleSource {

    /**
     * The rules associated with {@code vpcId}, which is never blank. An empty list leaves resolution
     * exactly as it is without this source.
     */
    List<DnsForwardingRule> rulesForVpc(String vpcId);
}
