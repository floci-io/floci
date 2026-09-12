package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.List;

/**
 * Minimal IPv4 CIDR math backing the IPAM emulation: containment, overlap and
 * first-free-block scans over a pool's provisioned space.
 */
final class Ipv4Cidrs {

    private Ipv4Cidrs() {}

    /** True when {@code inner} lies entirely within {@code outer}. */
    static boolean contains(String outer, String inner) {
        long[] o = parse(outer);
        long[] i = parse(inner);
        return o[0] <= i[0] && i[1] <= o[1];
    }

    /** True when the two blocks share any address. */
    static boolean overlaps(String a, String b) {
        long[] x = parse(a);
        long[] y = parse(b);
        return x[0] <= y[1] && y[0] <= x[1];
    }

    /**
     * True when {@code host} is one of the addresses AWS keeps for itself inside {@code cidr}.
     *
     * <p>The reserved set is the first four addresses of the subnet plus the last one. CreateSubnet
     * states it directly in ec2/2016-11-15: "Amazon Web Services reserves both the first four and
     * the last IPv4 address in each subnet's CIDR block. They're not available for your use."
     * None of the five can be assigned to a resource, so anything asking for a specific interface
     * address has to refuse them.
     *
     * <p>Small blocks need no special case. A /28, the smallest subnet AWS accepts, keeps 11 of its
     * 16 addresses, a /29 keeps three, and a block of four addresses or fewer keeps none, which is
     * the arithmetic working rather than failing. The two ends are clamped so they cannot cross,
     * and an address outside {@code cidr} is not reserved by it.
     *
     * @param host a single address as "a.b.c.d/32"
     */
    static boolean isSubnetReserved(String cidr, String host) {
        long[] block = parse(cidr);
        long address = parse(host)[0];
        if (address < block[0] || address > block[1]) {
            return false;
        }
        return address <= Math.min(block[0] + 3, block[1]) || address == block[1];
    }

    /** True when {@code cidr} parses as an IPv4 block, so the other checks can be applied to it. */
    static boolean isIpv4(String cidr) {
        try {
            parse(cidr);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    /**
     * The first /{@code netmask} block inside any of the {@code provisioned}
     * CIDRs (in order) that overlaps none of the {@code occupied} CIDRs, or
     * {@code null} when the space is exhausted or the request is larger than
     * every provisioned block.
     */
    static String firstFreeBlock(List<String> provisioned, List<String> occupied, int netmask) {
        if (netmask < 0 || netmask > 32) {
            throw invalid("/" + netmask);
        }
        long size = 1L << (32 - netmask);
        for (String pool : provisioned) {
            long[] p = parse(pool);
            long base = alignUp(p[0], size);
            while (base + size - 1 <= p[1]) {
                long blockedUntil = -1;
                for (String occ : occupied) {
                    long[] o = parse(occ);
                    if (o[0] <= base + size - 1 && base <= o[1]) {
                        blockedUntil = Math.max(blockedUntil, o[1]);
                    }
                }
                if (blockedUntil < 0) {
                    return toIp(base) + "/" + netmask;
                }
                base = alignUp(blockedUntil + 1, size);
            }
        }
        return null;
    }

    private static long alignUp(long address, long size) {
        long rem = address % size;
        return rem == 0 ? address : address + (size - rem);
    }

    /** Parse "a.b.c.d/len" into {firstAddress, lastAddress} (network-aligned). */
    private static long[] parse(String cidr) {
        if (cidr == null) {
            throw invalid("null");
        }
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw invalid(cidr);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw invalid(cidr);
        }
        if (prefix < 0 || prefix > 32) {
            throw invalid(cidr);
        }
        String[] octets = cidr.substring(0, slash).split("\\.");
        if (octets.length != 4) {
            throw invalid(cidr);
        }
        long ip = 0;
        for (String octet : octets) {
            int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException e) {
                throw invalid(cidr);
            }
            if (value < 0 || value > 255) {
                throw invalid(cidr);
            }
            ip = (ip << 8) | value;
        }
        long size = 1L << (32 - prefix);
        long base = (ip / size) * size;
        return new long[] {base, base + size - 1};
    }

    private static String toIp(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "."
                + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    private static AwsException invalid(String cidr) {
        return new AwsException("InvalidParameterValue", "Invalid IPv4 CIDR: " + cidr, 400);
    }
}
