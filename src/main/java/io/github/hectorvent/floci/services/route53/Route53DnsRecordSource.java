package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsLookupHelper;
import io.github.hectorvent.floci.core.common.dns.DnsRecordSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Route 53 private hosted zone records for the embedded DNS server.
 *
 * <p>Discovered via CDI as a {@link DnsRecordSource} implementation, keeping
 * the embedded DNS server decoupled from Route 53 service classes.
 */
@ApplicationScoped
public class Route53DnsRecordSource implements DnsRecordSource {

    private static final Logger LOG = Logger.getLogger(Route53DnsRecordSource.class);
    private static final int MAX_CNAME_DEPTH = 8;
    private static final int MAX_DNS_ANSWERS = 8;
    private static final Pattern EC2_PRIVATE_DNS_NAME =
            Pattern.compile("^ip-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.ec2\\.internal$", Pattern.CASE_INSENSITIVE);

    private static final List<String> BUILTIN_SUFFIXES = EmbeddedDnsServer.BUILTIN_SUFFIXES;

    private final Route53Service route53Service;
    private final DnsLookupHelper dnsLookupHelper;
    private final Set<String> flociSuffixes = new LinkedHashSet<>();

    public Route53DnsRecordSource(Route53Service route53Service) {
        this(route53Service, null, new DnsLookupHelper());
    }

    public Route53DnsRecordSource(Route53Service route53Service, EmulatorConfig config) {
        this(route53Service, config, new DnsLookupHelper(config));
    }

    @Inject
    public Route53DnsRecordSource(Route53Service route53Service, EmulatorConfig config, DnsLookupHelper dnsLookupHelper) {
        this.route53Service = route53Service;
        this.dnsLookupHelper = dnsLookupHelper != null ? dnsLookupHelper : new DnsLookupHelper(config);
        this.flociSuffixes.addAll(BUILTIN_SUFFIXES);
        if (config != null) {
            config.hostname().ifPresent(this.flociSuffixes::add);
            if (config.dns() != null) {
                config.dns().extraSuffixes().ifPresent(this.flociSuffixes::addAll);
            }
        }
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (!route53Service.isCoveredByPrivateZone(name)) {
            return Optional.empty();
        }
        DnsAnswer answer = resolveAddresses(name, new HashSet<>(), 0);
        if (answer.addresses().size() > MAX_DNS_ANSWERS) {
            answer = DnsAnswer.records(answer.addresses().subList(0, MAX_DNS_ANSWERS), answer.ttlSeconds());
        }
        return Optional.of(answer);
    }

    private DnsAnswer resolveAddresses(String qname, Set<String> visited, int depth) {
        if (depth > MAX_CNAME_DEPTH) {
            return DnsAnswer.noData();
        }
        String normalized = Route53Service.normalizeName(qname).toLowerCase(Locale.ROOT);
        if (!visited.add(normalized)) {
            return DnsAnswer.noData();
        }

        List<ResourceRecordSet> recordSets = route53Service.findPrivateRecordsForName(qname);
        if (recordSets.isEmpty()) {
            return route53Service.hasPrivateRecordsBeneath(qname) ? DnsAnswer.noData() : DnsAnswer.nxDomain();
        }

        // 1. Direct A records
        Set<String> addresses = new LinkedHashSet<>();
        int ttl = Integer.MAX_VALUE;
        for (ResourceRecordSet rrs : recordSets) {
            if ("A".equalsIgnoreCase(rrs.getType())) {
                if (rrs.getRecords() != null) {
                    for (ResourceRecord rr : rrs.getRecords()) {
                        String val = rr.getValue();
                        if (isIpv4(val)) {
                            addresses.add(val.trim());
                            ttl = Math.min(ttl, ttlOf(rrs));
                        }
                    }
                }
                AliasTarget alias = rrs.getAliasTarget();
                if (alias != null && alias.getDnsName() != null && !alias.getDnsName().isBlank()) {
                    String cleanAlias = alias.getDnsName().trim();
                    if (cleanAlias.endsWith(".")) {
                        cleanAlias = cleanAlias.substring(0, cleanAlias.length() - 1);
                    }
                    // An alias record has no TTL of its own; Route 53 answers with the target's.
                    if (route53Service.isCoveredByPrivateZone(cleanAlias)) {
                        DnsAnswer target = resolveAddresses(cleanAlias, visited, depth + 1);
                        if (!target.isEmpty()) {
                            addresses.addAll(target.addresses());
                            ttl = Math.min(ttl, target.ttlSeconds());
                        }
                    } else {
                        List<String> resolved = resolveOutsidePrivateZones(cleanAlias);
                        if (!resolved.isEmpty()) {
                            addresses.addAll(resolved);
                            ttl = Math.min(ttl, DnsAnswer.DEFAULT_TTL_SECONDS);
                        }
                    }
                }
            }
        }
        if (!addresses.isEmpty()) {
            return DnsAnswer.records(List.copyOf(addresses), ttl);
        }

        // 2. CNAME records
        for (ResourceRecordSet rrs : recordSets) {
            if ("CNAME".equalsIgnoreCase(rrs.getType())) {
                if (rrs.getRecords() != null && !rrs.getRecords().isEmpty()) {
                    String target = rrs.getRecords().get(0).getValue();
                    if (target != null && !target.isBlank()) {
                        String cleanTarget = target.trim();
                        if (cleanTarget.endsWith(".")) {
                            cleanTarget = cleanTarget.substring(0, cleanTarget.length() - 1);
                        }
                        // The answer is flattened to A records, so it can be cached only as long
                        // as the shortest-lived record in the chain.
                        int cnameTtl = ttlOf(rrs);
                        if (route53Service.isCoveredByPrivateZone(cleanTarget)) {
                            DnsAnswer targetAnswer = resolveAddresses(cleanTarget, visited, depth + 1);
                            return DnsAnswer.records(targetAnswer.addresses(),
                                    Math.min(cnameTtl, targetAnswer.ttlSeconds()));
                        }
                        return DnsAnswer.records(resolveOutsidePrivateZones(cleanTarget),
                                Math.min(cnameTtl, DnsAnswer.DEFAULT_TTL_SECONDS));
                    }
                }
            }
        }

        return DnsAnswer.noData();
    }

    private List<String> resolveOutsidePrivateZones(String name) {
        if (matchesFlociSuffix(name)) {
            return getLocalFlociAddress().map(List::of).orElse(List.of());
        }
        Optional<String> ec2Ip = resolveEc2PrivateDnsName(name);
        if (ec2Ip.isPresent()) {
            return List.of(ec2Ip.get());
        }
        return dnsLookupHelper.resolveIpv4(name);
    }

    private static int ttlOf(ResourceRecordSet rrs) {
        Long ttl = rrs.getTtl();
        if (ttl == null || ttl < 0) {
            return DnsAnswer.DEFAULT_TTL_SECONDS;
        }
        return (int) Math.min(ttl, Integer.MAX_VALUE);
    }

    private boolean matchesFlociSuffix(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String suffix : flociSuffixes) {
            String s = suffix.toLowerCase();
            if (lower.equals(s) || lower.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> getLocalFlociAddress() {
        try {
            return Optional.of(InetAddress.getLocalHost().getHostAddress());
        } catch (Exception e) {
            LOG.debugv("Failed to determine local host address: {0}", e.getMessage());
            return Optional.of("127.0.0.1");
        }
    }

    private Optional<String> resolveEc2PrivateDnsName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = EC2_PRIVATE_DNS_NAME.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        StringBuilder address = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(matcher.group(i));
            if (octet > 255) {
                return Optional.empty();
            }
            if (i > 1) {
                address.append('.');
            }
            address.append(octet);
        }
        return Optional.of(address.toString());
    }

    private static boolean isIpv4(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String[] parts = s.trim().split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
