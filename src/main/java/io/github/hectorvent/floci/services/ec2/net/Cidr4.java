package io.github.hectorvent.floci.services.ec2.net;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An IPv4 CIDR block, as arithmetic rather than text.
 *
 * <p>Floci needs to answer questions about declared VPC and subnet CIDRs that string
 * handling cannot: does this subnet actually sit inside its VPC, does this block collide
 * with a range Docker has already handed to some other network, is it inside RFC 1918,
 * and what is the Nth usable address in it. Every one of those is an integer comparison
 * once the block is a (network, prefix) pair, and every one of them is a guess when it is
 * a string that gets split on dots.
 *
 * <p>Addresses are held as unsigned values widened into a {@code long}, so the whole IPv4
 * space compares and sorts correctly without sign trouble at 128.0.0.0.
 */
public final class Cidr4 {

    private static final Pattern CIDR = Pattern.compile(
            "^\\s*(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})\\s*$");

    /** The three RFC 1918 private ranges, the only space Floci will put a VPC network in. */
    private static final Cidr4[] RFC_1918 = {
            new Cidr4(toLong(10, 0, 0, 0), 8),
            new Cidr4(toLong(172, 16, 0, 0), 12),
            new Cidr4(toLong(192, 168, 0, 0), 16)
    };

    private final long network;
    private final int prefix;

    Cidr4(long network, int prefix) {
        this.prefix = prefix;
        this.network = network & maskFor(prefix);
    }

    /**
     * @return the block, or empty when {@code text} is null, malformed, or has an octet or
     *         prefix length out of range. Callers treat empty as "the declaration is unusable",
     *         which is a fallback trigger, never a failure.
     */
    public static Optional<Cidr4> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = CIDR.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        long[] octets = new long[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Long.parseLong(m.group(i + 1));
            if (octets[i] > 255) {
                return Optional.empty();
            }
        }
        int prefix = Integer.parseInt(m.group(5));
        if (prefix > 32) {
            return Optional.empty();
        }
        return Optional.of(new Cidr4(
                (octets[0] << 24) | (octets[1] << 16) | (octets[2] << 8) | octets[3], prefix));
    }

    private static long maskFor(int prefix) {
        return prefix == 0 ? 0L : ((0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL);
    }

    private static long toLong(int a, int b, int c, int d) {
        return ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
    }

    public long network() {
        return network;
    }

    public int prefix() {
        return prefix;
    }

    /** Number of addresses in the block, network and broadcast included. */
    public long size() {
        return 1L << (32 - prefix);
    }

    public long broadcast() {
        return network + size() - 1;
    }

    public boolean containsAddress(long address) {
        return address >= network && address <= broadcast();
    }

    public boolean contains(Cidr4 other) {
        return other.network >= network && other.broadcast() <= broadcast();
    }

    public boolean overlaps(Cidr4 other) {
        return network <= other.broadcast() && other.network <= broadcast();
    }

    public boolean isRfc1918() {
        for (Cidr4 range : RFC_1918) {
            if (range.contains(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The address {@code offset} positions above the network address, as dotted quad.
     *
     * @return the address, or empty when the offset falls outside the block. Offset 0 is the
     *         network address itself and offset 1 is conventionally the gateway, so instance
     *         allocation starts well above both.
     */
    public Optional<String> addressAt(long offset) {
        if (offset < 0 || offset >= size()) {
            return Optional.empty();
        }
        return Optional.of(format(network + offset));
    }

    public static String format(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "."
                + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    /** A block of the same prefix length starting {@code index} blocks further along. */
    Cidr4 shifted(long index) {
        return new Cidr4(network + index * size(), prefix);
    }

    @Override
    public String toString() {
        return format(network) + "/" + prefix;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr4 other && other.network == network && other.prefix == prefix;
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, prefix);
    }
}
