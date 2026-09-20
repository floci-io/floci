package io.github.hectorvent.floci.core.common;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * SSRF protection for any code that resolves a caller-supplied hostname and fetches from the
 * resolved address server-side: rejects private, link-local, loopback, multicast, and other
 * reserved IPv4/IPv6 ranges. {@code CloudFrontServingController} (via
 * {@code CloudFrontOriginHttpClient}) and {@code VerifiedPermissionsOidcSignatureVerifier} each
 * implemented this independently before this class existed.
 */
public final class SsrfProtection {

    private SsrfProtection() {
    }

    /**
     * Narrower than {@link #isBlockedAddress}: only the addresses cloud instance-metadata
     * services listen on, i.e. IPv4 and IPv6 link-local ({@code 169.254.0.0/16}, {@code fe80::/10})
     * and the AWS IPv6 metadata address {@code fd00:ec2::254}. For callers that must still reach
     * loopback and private networks, such as a local emulator proxying to a neighbouring container.
     */
    public static boolean isMetadataAddress(InetAddress address) {
        if (address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            if (isIpv4MappedAddress(bytes)) {
                return Byte.toUnsignedInt(bytes[12]) == 169 && Byte.toUnsignedInt(bytes[13]) == 254;
            }
            return isAwsIpv6Metadata(bytes);
        }
        return false;
    }

    /**
     * Returns {@code addresses} unchanged, or throws when any of them is a metadata address per
     * {@link #isMetadataAddress}. Callers that connect to the returned addresses, instead of
     * resolving {@code host} again, are not exposed to a hostname that answers differently the
     * second time.
     */
    public static InetAddress[] rejectMetadataAddresses(InetAddress[] addresses, String host) throws IOException {
        for (InetAddress address : addresses) {
            if (isMetadataAddress(address)) {
                throw new IOException("integration URI resolves to a link-local or metadata address: " + host);
            }
        }
        return addresses;
    }

    private static boolean isAwsIpv6Metadata(byte[] bytes) {
        byte[] metadata = {(byte) 0xfd, 0x00, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};
        return Arrays.equals(bytes, metadata);
    }

    public static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4Address(bytes, 0);
        }
        if (bytes.length == 16) {
            if (isIpv4MappedAddress(bytes)) {
                return isBlockedIpv4Address(bytes, 12);
            }
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return uniqueLocal || documentation;
        }
        return true;
    }

    private static boolean isIpv4MappedAddress(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean isBlockedIpv4Address(byte[] bytes, int offset) {
        int first = Byte.toUnsignedInt(bytes[offset]);
        int second = Byte.toUnsignedInt(bytes[offset + 1]);
        int third = Byte.toUnsignedInt(bytes[offset + 2]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }
}
