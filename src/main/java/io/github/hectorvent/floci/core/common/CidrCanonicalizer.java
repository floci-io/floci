package io.github.hectorvent.floci.core.common;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Canonicalises IPv4 and IPv6 CIDR blocks the way AWS does on write: the address is
 * rewritten so every bit outside the prefix is zero, then re-rendered in AWS's own
 * textual form (dotted-decimal for IPv4, compressed form for IPv6).
 *
 * <p>{@code 100.68.0.18/18} becomes {@code 100.68.0.0/18} — AWS's own documented
 * example — because bits 18-31 of the address are host bits and AWS zeroes them
 * before storing the block. A CIDR that is already canonical is returned unchanged
 * (the identical string, not merely an equal one).
 *
 * <p>This class draws a hard line between "malformed" and "valid but non-canonical":
 * {@link #canonicalize(String)} returns {@link Optional#empty()} only for a CIDR that
 * cannot be parsed at all, never as a stand-in for "already canonical". Callers that
 * need to tell the two apart before rewriting anything can call
 * {@link #isCanonical(String)} directly.
 *
 * <p>Pure function: no I/O, no DNS resolution. The address half of the CIDR is parsed
 * as a literal via {@link InetAddress#ofLiteral(String)}, which never triggers a
 * hostname lookup — critical here, because {@link InetAddress#getByName(String)} would
 * silently turn a malformed CIDR into a DNS query.
 */
public final class CidrCanonicalizer {

    private CidrCanonicalizer() {}

    /**
     * Parses {@code cidr}, zeroes its host bits, and re-renders it in canonical form.
     *
     * @param cidr an IPv4 or IPv6 CIDR block, e.g. {@code "100.68.0.18/18"} or
     *             {@code "2001:db8::1/32"}
     * @return the canonical CIDR string, or {@link Optional#empty()} if {@code cidr} is
     *     null, blank, missing a prefix, has a prefix out of range for the address
     *     family (0-32 for IPv4, 0-128 for IPv6), or has an address half that is not a
     *     literal IPv4/IPv6 address
     */
    public static Optional<String> canonicalize(String cidr) {
        return parse(cidr).map(parsed -> {
            String rendered = render(parsed);
            // Preserve reference identity when the input was already canonical, so
            // callers get the exact same String back rather than a merely-equal copy.
            return rendered.equals(cidr) ? cidr : rendered;
        });
    }

    /**
     * Reports whether {@code cidr} is both parseable and already in canonical form,
     * i.e. {@code canonicalize(cidr)} would return the identical string.
     *
     * @return {@code true} only when {@code cidr} is valid and already canonical;
     *     {@code false} both when it is malformed and when it is valid but would be
     *     rewritten — use {@link #canonicalize(String)} to distinguish those two cases
     */
    public static boolean isCanonical(String cidr) {
        return parse(cidr).map(parsed -> render(parsed).equals(cidr)).orElse(false);
    }

    private static Optional<ParsedCidr> parse(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return Optional.empty();
        }

        int slash = cidr.lastIndexOf('/');
        if (slash < 0 || slash == cidr.length() - 1) {
            return Optional.empty();
        }

        String addressPart = cidr.substring(0, slash);
        String prefixPart = cidr.substring(slash + 1);

        int prefixLength;
        try {
            prefixLength = Integer.parseInt(prefixPart);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (prefixLength < 0) {
            return Optional.empty();
        }

        InetAddress address;
        try {
            // ofLiteral never resolves a hostname; a non-literal input throws instead
            // of falling through to DNS, which is exactly what a malformed address
            // half of a CIDR must do.
            address = InetAddress.ofLiteral(addressPart);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        int maxPrefixLength = address instanceof Inet4Address ? 32 : 128;
        if (prefixLength > maxPrefixLength) {
            return Optional.empty();
        }

        return Optional.of(new ParsedCidr(address, prefixLength));
    }

    private static String render(ParsedCidr parsed) {
        byte[] masked = maskHostBits(parsed.address().getAddress(), parsed.prefixLength());
        InetAddress maskedAddress;
        try {
            maskedAddress = InetAddress.getByAddress(masked);
        } catch (java.net.UnknownHostException e) {
            // Only thrown for an address of the wrong byte length, which cannot
            // happen here: `masked` is always copied from a real InetAddress.
            throw new AssertionError("unreachable: masked address has invalid length", e);
        }
        return hostAddress(maskedAddress) + "/" + parsed.prefixLength();
    }

    /**
     * Returns the textual form AWS uses: dotted-decimal for IPv4, RFC 5952 compressed
     * form for IPv6.
     *
     * <p>{@link InetAddress#getHostAddress()} renders IPv6 addresses in fully expanded
     * form ({@code 2001:db8:0:0:0:0:0:0}, not {@code 2001:db8::}), so the compression
     * step is done here explicitly rather than delegated.
     */
    private static String hostAddress(InetAddress address) {
        if (address instanceof Inet6Address v6) {
            return compressIpv6(v6.getAddress());
        }
        return address.getHostAddress();
    }

    /**
     * Renders 16 raw IPv6 address bytes as RFC 5952 compressed text: the longest run
     * of two or more consecutive all-zero 16-bit groups is collapsed to {@code ::}
     * (the leftmost run wins on a tie), and remaining groups are lowercase hex with no
     * leading zeros.
     */
    private static String compressIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }

        int bestStart = -1;
        int bestLength = 0;
        int runStart = -1;
        for (int i = 0; i <= 8; i++) {
            boolean zero = i < 8 && groups[i] == 0;
            if (zero) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                int runLength = i - runStart;
                if (runLength > bestLength) {
                    bestStart = runStart;
                    bestLength = runLength;
                }
                runStart = -1;
            }
        }
        // A run must be at least two groups to be worth eliding (RFC 5952 §4.2.2).
        if (bestLength < 2) {
            bestStart = -1;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; ) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLength;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
            i++;
        }
        // An address with no elided run at all (e.g. all groups non-zero) never hits
        // the "::" branch, so nothing above needs a leading-colon special case; a run
        // starting at index 0 naturally produces the correct leading "::".
        return sb.toString();
    }

    private static byte[] maskHostBits(byte[] addressBytes, int prefixLength) {
        byte[] masked = addressBytes.clone();
        int fullBytes = prefixLength / 8;
        int remainderBits = prefixLength % 8;

        if (remainderBits != 0 && fullBytes < masked.length) {
            int keepMask = 0xFF << (8 - remainderBits) & 0xFF;
            masked[fullBytes] = (byte) (masked[fullBytes] & keepMask);
            fullBytes++;
        }
        for (int i = fullBytes; i < masked.length; i++) {
            masked[i] = 0;
        }
        return masked;
    }

    private record ParsedCidr(InetAddress address, int prefixLength) {}
}
