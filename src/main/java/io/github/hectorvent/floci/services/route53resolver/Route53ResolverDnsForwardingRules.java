package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsForwardingRule;
import io.github.hectorvent.floci.core.common.dns.DnsForwardingRuleSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns the resolver rules {@link Route53ResolverService} stores into the forwarding rules the
 * embedded DNS server applies. Discovered via CDI as a {@link DnsForwardingRuleSource}, so the DNS
 * server never imports this service, and the rules are read from its stores rather than copied.
 *
 * <p>Only rules the querying account has associated with the querying VPC, in the querying region,
 * are returned. An unassociated rule is inert in AWS, an association naming a VPC of some other
 * account is not that account's business, and Route 53 Resolver is regional, so a rule governs
 * nothing outside the region it was created in. {@code FORWARD} rules carry their target resolvers;
 * {@code SYSTEM} and {@code RECURSIVE} rules carry none and so hand the query back to Floci's own
 * resolution, which is what both mean in AWS. {@code DELEGATE} is stored but not applied.
 */
@ApplicationScoped
public class Route53ResolverDnsForwardingRules implements DnsForwardingRuleSource {

    private final Route53ResolverService resolverService;
    private final EmulatorConfig config;

    @Inject
    public Route53ResolverDnsForwardingRules(Route53ResolverService resolverService, EmulatorConfig config) {
        this.resolverService = resolverService;
        this.config = config;
    }

    @Override
    public List<DnsForwardingRule> rulesFor(String accountId, String region, String vpcId) {
        if (!config.services().route53resolver().enabled()) {
            return List.of();
        }
        List<DnsForwardingRule> rules = new ArrayList<>();
        for (String ruleId : resolverService.resolverRuleIdsAssociatedWith(accountId, vpcId)) {
            resolverService.resolverRuleIn(accountId, region, ruleId)
                    .flatMap(Route53ResolverDnsForwardingRules::forwardingRuleFor)
                    .ifPresent(rules::add);
        }
        // Most specific first, so the DNS server's choice between two equally specific rules is the
        // same on every query rather than following whatever order the store scanned in.
        rules.sort(Comparator.comparingInt(DnsForwardingRule::specificity).reversed()
                .thenComparing(DnsForwardingRule::domainName));
        return List.copyOf(rules);
    }

    private static Optional<DnsForwardingRule> forwardingRuleFor(ObjectNode rule) {
        String domainName = text(rule, "DomainName");
        if (domainName == null || domainName.isBlank()) {
            return Optional.empty();
        }
        String ruleType = text(rule, "RuleType");
        return switch (ruleType == null ? "" : ruleType) {
            case "FORWARD" -> targetsOf(rule).map(targets -> DnsForwardingRule.forwardTo(domainName, targets));
            case "SYSTEM", "RECURSIVE" -> Optional.of(DnsForwardingRule.system(domainName));
            default -> Optional.empty();
        };
    }

    /**
     * The rule's IPv4 targets. {@code Ipv6} targets are skipped because the embedded DNS server
     * binds and forwards over IPv4 only, and a rule left with no usable target is dropped rather
     * than applied, so its queries resolve as they would without it.
     */
    private static Optional<List<DnsForwardingRule.Target>> targetsOf(ObjectNode rule) {
        List<DnsForwardingRule.Target> targets = new ArrayList<>();
        for (JsonNode target : rule.path("TargetIps")) {
            String address = text(target, "Ip");
            if (address == null || address.isBlank()) {
                continue;
            }
            int port = target.path("Port").asInt(DnsForwardingRule.Target.DEFAULT_DNS_PORT);
            targets.add(new DnsForwardingRule.Target(address.trim(),
                    port > 0 && port <= 65535 ? port : DnsForwardingRule.Target.DEFAULT_DNS_PORT));
        }
        return targets.isEmpty() ? Optional.empty() : Optional.of(targets);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
