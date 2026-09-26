package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
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
    public Optional<List<String>> resolveIpv4(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (!route53Service.isCoveredByPrivateZone(name)) {
            return Optional.empty();
        }
        List<String> addresses = resolveAddresses(name, new HashSet<>(), 0);
        if (addresses.size() > MAX_DNS_ANSWERS) {
            addresses = addresses.subList(0, MAX_DNS_ANSWERS);
        }
        return Optional.of(addresses);
    }

    private List<String> resolveAddresses(String qname, Set<String> visited, int depth) {
        if (depth > MAX_CNAME_DEPTH) {
            return List.of();
        }
        String normalized = Route53Service.normalizeName(qname).toLowerCase();
        if (!visited.add(normalized)) {
            return List.of();
        }

        List<ResourceRecordSet> recordSets = route53Service.findPrivateRecordsForName(qname);
        if (recordSets.isEmpty()) {
            return List.of();
        }

        // 1. Direct A records
        Set<String> addresses = new LinkedHashSet<>();
        for (ResourceRecordSet rrs : recordSets) {
            if ("A".equalsIgnoreCase(rrs.getType())) {
                if (rrs.getRecords() != null) {
                    for (ResourceRecord rr : rrs.getRecords()) {
                        String val = rr.getValue();
                        if (isIpv4(val)) {
                            addresses.add(val.trim());
                        }
                    }
                }
                AliasTarget alias = rrs.getAliasTarget();
                if (alias != null && alias.getDnsName() != null && !alias.getDnsName().isBlank()) {
                    String cleanAlias = alias.getDnsName().trim();
                    if (cleanAlias.endsWith(".")) {
                        cleanAlias = cleanAlias.substring(0, cleanAlias.length() - 1);
                    }
                    if (route53Service.isCoveredByPrivateZone(cleanAlias)) {
                        addresses.addAll(resolveAddresses(cleanAlias, visited, depth + 1));
                    } else if (matchesFlociSuffix(cleanAlias)) {
                        getLocalFlociAddress().ifPresent(addresses::add);
                    } else {
                        Optional<String> ec2Ip = resolveEc2PrivateDnsName(cleanAlias);
                        if (ec2Ip.isPresent()) {
                            addresses.add(ec2Ip.get());
                        } else {
                            addresses.addAll(dnsLookupHelper.resolveIpv4(cleanAlias));
                        }
                    }
                }
            }
        }
        if (!addresses.isEmpty()) {
            return List.copyOf(addresses);
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
                        if (route53Service.isCoveredByPrivateZone(cleanTarget)) {
                            return resolveAddresses(cleanTarget, visited, depth + 1);
                        }
                        if (matchesFlociSuffix(cleanTarget)) {
                            return getLocalFlociAddress().map(List::of).orElse(List.of());
                        }
                        Optional<String> ec2Ip = resolveEc2PrivateDnsName(cleanTarget);
                        if (ec2Ip.isPresent()) {
                            return List.of(ec2Ip.get());
                        }
                        return dnsLookupHelper.resolveIpv4(cleanTarget);
                    }
                }
            }
        }

        return List.of();
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
