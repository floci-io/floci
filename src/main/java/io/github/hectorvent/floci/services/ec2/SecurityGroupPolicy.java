package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Evaluates the live permissions on security groups, rather than the separately stored rule records. */
public final class SecurityGroupPolicy {

    private SecurityGroupPolicy() {}

    public record Peer(String logicalAddress, String accountId, String vpcId, Set<String> groupIds) {}

    public static boolean allows(List<SecurityGroup> groups, boolean egress, String protocol,
                                 int port, int icmpCode, Peer peer,
                                 Map<String, List<String>> prefixLists) {
        if (groups == null || peer == null || protocol == null) {
            return false;
        }
        for (SecurityGroup group : groups) {
            List<IpPermission> permissions = egress
                    ? group.getIpPermissionsEgress() : group.getIpPermissions();
            if (permissions == null) {
                continue;
            }
            for (IpPermission permission : permissions) {
                if (matchesProtocol(permission, protocol, port, icmpCode)
                        && matchesPeer(group, permission, peer, prefixLists)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesProtocol(IpPermission permission, String protocol, int port, int icmpCode) {
        String ruleProtocol = permission.getIpProtocol();
        if ("-1".equals(ruleProtocol)) {
            return true;
        }
        if (!Objects.equals(normalizeProtocol(ruleProtocol), normalizeProtocol(protocol))) {
            return false;
        }
        if ("tcp".equals(normalizeProtocol(protocol)) || "udp".equals(normalizeProtocol(protocol))) {
            Integer from = permission.getFromPort();
            Integer to = permission.getToPort();
            return from != null && to != null && port >= from && port <= to;
        }
        if ("icmp".equals(normalizeProtocol(protocol)) || "icmpv6".equals(normalizeProtocol(protocol))) {
            Integer type = permission.getFromPort();
            Integer code = permission.getToPort();
            return (type == null || type == -1 || type == port)
                    && (code == null || code == -1 || code == icmpCode);
        }
        return true;
    }

    private static String normalizeProtocol(String protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol.toLowerCase(Locale.ROOT)) {
            case "6" -> "tcp";
            case "17" -> "udp";
            case "1" -> "icmp";
            case "58" -> "icmpv6";
            default -> protocol.toLowerCase(Locale.ROOT);
        };
    }

    static boolean matchesPeer(SecurityGroup group, IpPermission permission, Peer peer,
                               Map<String, List<String>> prefixLists) {
        if (permission.getIpRanges() != null && permission.getIpRanges().stream()
                .anyMatch(range -> inCidr(peer.logicalAddress(), range.getCidrIp()))) {
            return true;
        }
        if (permission.getIpv6Ranges() != null && permission.getIpv6Ranges().stream()
                .anyMatch(range -> inCidr(peer.logicalAddress(), range.getCidrIpv6()))) {
            return true;
        }
        if (permission.getPrefixListIds() != null && prefixLists != null) {
            for (PrefixListId reference : permission.getPrefixListIds()) {
                if (prefixLists.getOrDefault(reference.getPrefixListId(), List.of()).stream()
                        .anyMatch(cidr -> inCidr(peer.logicalAddress(), cidr))) {
                    return true;
                }
            }
        }
        if (permission.getUserIdGroupPairs() != null && peer.groupIds() != null
                && Objects.equals(group.getVpcId(), peer.vpcId())) {
            return permission.getUserIdGroupPairs().stream().anyMatch(reference ->
                    peer.groupIds().contains(reference.getGroupId())
                            && Objects.equals(reference.getUserId() == null
                                    ? group.getOwnerId() : reference.getUserId(), peer.accountId()));
        }
        return false;
    }

    static boolean inCidr(String address, String cidr) {
        if (address == null || cidr == null || address.contains("%") || cidr.contains("%")) {
            return false;
        }
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2 || !literalAddress(address) || !literalAddress(parts[0])) {
            return false;
        }
        try {
            byte[] value = InetAddress.getByName(address).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int bits = Integer.parseInt(parts[1]);
            if (value.length != network.length || bits < 0 || bits > value.length * 8) {
                return false;
            }
            int whole = bits / 8;
            for (int i = 0; i < whole; i++) {
                if (value[i] != network[i]) {
                    return false;
                }
            }
            int remainder = bits % 8;
            int mask = (0xff << (8 - remainder)) & 0xff;
            return remainder == 0 || ((value[whole] & mask) == (network[whole] & mask));
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    public static boolean validCidr(String cidr) {
        if (cidr == null) {
            return false;
        }
        int slash = cidr.indexOf('/');
        return slash > 0 && inCidr(cidr.substring(0, slash), cidr);
    }

    private static boolean literalAddress(String address) {
        return address.indexOf(':') >= 0
                ? address.matches("[0-9a-fA-F:.]+")
                : address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    }
}
