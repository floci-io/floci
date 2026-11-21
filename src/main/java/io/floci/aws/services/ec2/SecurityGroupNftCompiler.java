package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.SecurityGroup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds only rules for an endpoint's private nftables table. Docker's tables are untouched. */
public final class SecurityGroupNftCompiler {

    /** The ECS task metadata and credentials endpoint, which security groups do not filter on AWS. */
    static final String ECS_TASK_METADATA_ADDRESS = "169.254.170.2";

    private SecurityGroupNftCompiler() {}

    /**
     * A managed ENI: its logical addresses (the ENI's own, which security-group rules are written against) and the
     * Docker transport addresses its traffic actually carries. An ENI may hold several IPv6 addresses, all carried
     * by the helper's one Docker IPv6 address, which is null on a Docker network without IPv6.
     */
    public record Endpoint(String accountId, String region, String vpcId, String eniId,
                           String logicalAddress, List<String> logicalIpv6Addresses,
                           String transportAddress, String transportIpv6Address,
                           Set<String> groupIds, List<SecurityGroup> groups) {

        public Endpoint(String accountId, String region, String vpcId, String eniId,
                        String logicalAddress, String transportAddress, Set<String> groupIds,
                        List<SecurityGroup> groups) {
            this(accountId, region, vpcId, eniId, logicalAddress, List.of(), transportAddress, null, groupIds,
                    groups);
        }

        public Endpoint {
            logicalIpv6Addresses = logicalIpv6Addresses == null ? List.of() : List.copyOf(logicalIpv6Addresses);
        }

        /** The same ENI identity with the policy the manager re-resolved. */
        public Endpoint withPolicy(Set<String> groupIds, List<SecurityGroup> groups) {
            return new Endpoint(accountId, region, vpcId, eniId, logicalAddress, logicalIpv6Addresses,
                    transportAddress, transportIpv6Address, groupIds, groups);
        }

        /** The same ENI and policy, holding {@code addresses} as its IPv6 addresses now. */
        public Endpoint withIpv6Addresses(List<String> addresses) {
            return new Endpoint(accountId, region, vpcId, eniId, logicalAddress, addresses, transportAddress,
                    transportIpv6Address, groupIds, groups);
        }

        SecurityGroupPolicy.Peer peer(String address) {
            return new SecurityGroupPolicy.Peer(address, accountId, vpcId, groupIds);
        }

        /**
         * The (logical, transport) pairs a peer's rules are written for. An IPv6 address pairs only with the IPv6
         * transport: emitted against the IPv4 one, a rule allowing a peer's IPv6 range would admit IPv4 traffic no
         * permission allows.
         */
        List<AddressIdentity> identities() {
            List<AddressIdentity> identities = new ArrayList<>();
            if (literal(logicalAddress) && literal(transportAddress)) {
                identities.add(new AddressIdentity(logicalAddress, transportAddress));
            }
            if (literal(transportIpv6Address)) {
                for (String address : logicalIpv6Addresses) {
                    if (literal(address)) {
                        identities.add(new AddressIdentity(address, transportIpv6Address));
                    }
                }
            }
            return identities;
        }

        /** Every Docker address the endpoint's traffic can carry, whether or not an identity uses it. */
        List<String> transports() {
            List<String> transports = new ArrayList<>(2);
            if (literal(transportAddress)) {
                transports.add(transportAddress);
            }
            if (literal(transportIpv6Address)) {
                transports.add(transportIpv6Address);
            }
            return transports;
        }
    }

    record AddressIdentity(String logical, String transport) {
    }

    public static String initialRuleset() {
        return "add table inet floci_sg\n"
                + "add chain inet floci_sg ingress { type filter hook input priority -10; policy drop; }\n"
                + "add chain inet floci_sg egress { type filter hook output priority -10; policy drop; }\n";
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                 Map<String, List<String>> prefixLists) {
        return compile(target, peers, prefixLists, List.of());
    }

    public static String compile(Endpoint target, Collection<Endpoint> peers,
                                 Map<String, List<String>> prefixLists, List<String> vpcResolvers) {
        if (target == null || target.groups() == null || target.groups().isEmpty()) {
            throw new IllegalArgumentException("Protected endpoint needs at least one security group");
        }
        StringBuilder rules = new StringBuilder("flush chain inet floci_sg ingress\n")
                .append("flush chain inet floci_sg egress\n")
                .append("add rule inet floci_sg ingress iifname lo accept\n")
                .append("add rule inet floci_sg egress oifname lo accept\n")
                .append("add rule inet floci_sg ingress ct state established,related accept\n")
                .append("add rule inet floci_sg egress ct state established,related accept\n");

        // Docker's embedded DNS listens on loopback but forwards from this namespace. Security
        // groups never filter the VPC resolver on AWS. Only DNS is exempt, because the resolver
        // address also serves Floci's API. DHCP, the link-local metadata endpoint and the ECS
        // task metadata endpoint, where a task reads its role credentials, are the other local
        // infrastructure exceptions. The ECS endpoint is opened for its one listener only, so a
        // workload cannot use it to reach anything else on the Docker network.
        for (String server : vpcResolvers) {
            String family = server.contains(":") ? "ip6" : "ip";
            rules.append("add rule inet floci_sg egress ").append(family).append(" daddr ").append(server)
                    .append(" udp dport 53 accept\n")
                    .append("add rule inet floci_sg egress ").append(family).append(" daddr ").append(server)
                    .append(" tcp dport 53 accept\n");
        }
        rules.append("add rule inet floci_sg egress udp sport 68 udp dport 67 accept\n")
                .append("add rule inet floci_sg ingress udp sport 67 udp dport 68 accept\n")
                .append("add rule inet floci_sg egress ip daddr 169.254.169.254 accept\n")
                .append("add rule inet floci_sg ingress ip saddr 169.254.169.254 accept\n")
                .append("add rule inet floci_sg egress ip daddr ").append(ECS_TASK_METADATA_ADDRESS)
                .append(" tcp dport 80 accept\n");

        List<Endpoint> managed = peers == null ? List.of() : peers.stream()
                .filter(peer -> !target.eniId().equals(peer.eniId()))
                .filter(peer -> !peer.transports().isEmpty())
                .toList();

        for (boolean egress : new boolean[]{false, true}) {
            String chain = egress ? "egress" : "ingress";
            String addressField = egress ? "daddr" : "saddr";
            for (SecurityGroup group : target.groups()) {
                List<IpPermission> permissions = egress
                        ? group.getIpPermissionsEgress() : group.getIpPermissions();
                if (permissions == null) {
                    continue;
                }
                for (IpPermission permission : permissions) {
                    String protocol = protocolExpression(permission);
                    if (protocol == null) {
                        continue;
                    }
                    for (Endpoint peer : managed) {
                        if (!target.accountId().equals(peer.accountId()) || !target.region().equals(peer.region())
                                || !target.vpcId().equals(peer.vpcId())) {
                            continue;
                        }
                        for (AddressIdentity identity : peer.identities()) {
                            if (protocolFitsFamily(permission, identity.transport())
                                    && SecurityGroupPolicy.matchesPeer(group, permission, peer.peer(identity.logical()),
                                    prefixLists)) {
                                appendRule(rules, chain, addressField, identity.transport(), protocol);
                            }
                        }
                    }
                }
            }
            // A managed peer must never fall through to a broad external CIDR rule
            // evaluated against its Docker bridge IP instead of its logical ENI address.
            // Every transport a managed peer has, its IPv6 one included when its ENI holds no IPv6 address, so its
            // traffic on that address cannot reach a broad IPv6 CIDR rule either.
            Set<String> transports = new LinkedHashSet<>();
            managed.forEach(peer -> transports.addAll(peer.transports()));
            for (String transport : transports) {
                appendRule(rules, chain, addressField, transport, "drop");
            }
            for (SecurityGroup group : target.groups()) {
                List<IpPermission> permissions = egress
                        ? group.getIpPermissionsEgress() : group.getIpPermissions();
                if (permissions == null) {
                    continue;
                }
                for (IpPermission permission : permissions) {
                    String protocol = protocolExpression(permission);
                    if (protocol == null) {
                        continue;
                    }
                    Set<String> cidrs = new LinkedHashSet<>();
                    if (permission.getIpRanges() != null) {
                        permission.getIpRanges().forEach(range -> cidrs.add(range.getCidrIp()));
                    }
                    if (permission.getIpv6Ranges() != null) {
                        permission.getIpv6Ranges().forEach(range -> cidrs.add(range.getCidrIpv6()));
                    }
                    if (permission.getPrefixListIds() != null && prefixLists != null) {
                        permission.getPrefixListIds().forEach(ref ->
                                cidrs.addAll(prefixLists.getOrDefault(ref.getPrefixListId(), List.of())));
                    }
                    for (String cidr : cidrs) {
                        if (SecurityGroupPolicy.validCidr(cidr)) {
                            appendRule(rules, chain, addressField, cidr, protocol);
                        }
                    }
                }
            }
        }
        return rules.toString();
    }

    private static void appendRule(StringBuilder rules, String chain, String addressField,
                                   String address, String expression) {
        rules.append("add rule inet floci_sg ").append(chain).append(' ')
                .append(address.indexOf(':') >= 0 ? "ip6 " : "ip ")
                .append(addressField).append(' ').append(address).append(' ')
                .append(expression).append('\n');
    }

    /**
     * Whether a permission's protocol can be written against {@code address}'s family: ICMP is IPv4's and ICMPv6 is
     * IPv6's, and nftables refuses an {@code icmp} header match beside an {@code ip6} address, and the reverse.
     */
    private static boolean protocolFitsFamily(IpPermission permission, String address) {
        String protocol = permission.getIpProtocol() == null ? "" : permission.getIpProtocol().toLowerCase(Locale.ROOT);
        boolean ipv6 = address.indexOf(':') >= 0;
        return switch (protocol) {
            case "icmp", "1" -> !ipv6;
            case "icmpv6", "58" -> ipv6;
            default -> true;
        };
    }

    private static String protocolExpression(IpPermission permission) {
        String protocol = permission.getIpProtocol();
        if (protocol == null) {
            return null;
        }
        if ("-1".equals(protocol)) {
            return "accept";
        }
        String normalized = switch (protocol.toLowerCase(Locale.ROOT)) {
            case "6" -> "tcp";
            case "17" -> "udp";
            case "1" -> "icmp";
            case "58" -> "icmpv6";
            default -> protocol.toLowerCase(Locale.ROOT);
        };
        if ("tcp".equals(normalized) || "udp".equals(normalized)) {
            Integer from = permission.getFromPort();
            Integer to = permission.getToPort();
            if (from == null || to == null || from < 0 || to > 65535 || to < from) {
                return null;
            }
            return normalized + " dport " + (from.equals(to) ? from : from + "-" + to) + " accept";
        }
        if ("icmp".equals(normalized) || "icmpv6".equals(normalized)) {
            // nftables header fields are separate expressions, so type and code each repeat
            // the protocol keyword: "icmp type 8 icmp code 0", never "icmp type 8 code 0".
            StringBuilder expression = new StringBuilder();
            Integer type = permission.getFromPort();
            Integer code = permission.getToPort();
            if (type != null && type >= 0) {
                expression.append(normalized).append(" type ").append(type).append(' ');
            }
            if (code != null && code >= 0) {
                expression.append(normalized).append(" code ").append(code).append(' ');
            }
            if (expression.isEmpty()) {
                expression.append("meta l4proto ").append(normalized).append(' ');
            }
            return expression.append("accept").toString();
        }
        try {
            int number = Integer.parseInt(normalized);
            return number >= 0 && number <= 255 ? "meta l4proto " + number + " accept" : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean literal(String address) {
        try {
            if (address == null || address.contains("%")
                    || !(address.contains(":") ? address.matches("[0-9a-fA-F:.]+")
                    : address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
                return false;
            }
            InetAddress.getByName(address);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
