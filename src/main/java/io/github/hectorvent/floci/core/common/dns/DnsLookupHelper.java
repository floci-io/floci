package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves external hostnames to IPv4 addresses using upstream DNS servers
 * with fallback to system name resolution.
 */
@ApplicationScoped
public class DnsLookupHelper {

    private static final Logger LOG = Logger.getLogger(DnsLookupHelper.class);
    private static final int MAX_CNAME_DEPTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final List<String> upstreamServers;

    public DnsLookupHelper() {
        this((EmulatorConfig) null);
    }

    @Inject
    public DnsLookupHelper(EmulatorConfig config) {
        List<String> fallbacks = config != null && config.dns() != null
                ? config.dns().containerFallbackServers()
                : null;
        this.upstreamServers = EmbeddedDnsServer.composeUpstreams(
                EmbeddedDnsServer.readResolvConfNameservers(), fallbacks);
    }

    DnsLookupHelper(List<String> upstreamServers) {
        this.upstreamServers = upstreamServers != null ? List.copyOf(upstreamServers) : List.of();
    }

    public List<String> resolveIpv4(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        List<String> forwarderResults = resolveViaForwarders(target);
        if (!forwarderResults.isEmpty()) {
            return forwarderResults;
        }
        List<String> results = new ArrayList<>();
        try {
            for (InetAddress addr : InetAddress.getAllByName(target)) {
                if (addr instanceof Inet4Address && isIpv4(addr.getHostAddress())) {
                    results.add(addr.getHostAddress());
                }
            }
        } catch (Exception expected) {
            LOG.debugv("Failed to resolve external target {0}: {1}", target, expected.getMessage());
        }
        return List.copyOf(results);
    }

    private List<String> resolveViaForwarders(String target) {
        if (upstreamServers.isEmpty()) {
            return List.of();
        }
        short txId = (short) random.nextInt(1 << 16);
        byte[] query = buildQuery(target, txId, (short) 1);
        try {
            byte[] response = EmbeddedDnsServer.forwardToUpstreams(
                    query, upstreamServers, EmbeddedDnsServer.DNS_PORT);
            return parseARecordsFromDnsResponse(response, response.length, txId, target);
        } catch (Exception expected) {
            LOG.debugv("DNS query to upstreams for {0} failed: {1}", target, expected.getMessage());
            return List.of();
        }
    }

    static byte[] buildQuery(String qname, short txId, short qtype) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.putShort(txId);
        buf.putShort((short) 0x0100); // QR=0, RD=1
        buf.putShort((short) 1);      // qdcount=1
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        for (String label : qname.split("\\.")) {
            if (!label.isEmpty()) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                buf.put((byte) bytes.length);
                buf.put(bytes);
            }
        }
        buf.put((byte) 0);
        buf.putShort(qtype);
        buf.putShort((short) 1); // class IN

        byte[] query = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, query, 0, query.length);
        return query;
    }

    static List<String> parseARecordsFromDnsResponse(
            byte[] response, int length, short expectedTxId, String expectedTarget) {
        if (response == null || length < 12) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(response, 0, length);
        short txId = buf.getShort();
        if (txId != expectedTxId) {
            return List.of();
        }
        short flags = buf.getShort();
        if ((flags & 0x8000) == 0) {
            return List.of();
        }
        int rcode = flags & 0x000F;
        if (rcode != 0) {
            return List.of();
        }
        int qdCount = buf.getShort() & 0xFFFF;
        int anCount = buf.getShort() & 0xFFFF;
        buf.getShort(); // nsCount
        buf.getShort(); // arCount

        if (qdCount > 0) {
            String questionName = EmbeddedDnsServer.readName(buf, response);
            buf.getShort(); // qtype
            buf.getShort(); // qclass
            if (!normalizeTarget(questionName).equalsIgnoreCase(normalizeTarget(expectedTarget))) {
                return List.of();
            }
            for (int i = 1; i < qdCount; i++) {
                EmbeddedDnsServer.readName(buf, response);
                buf.getShort();
                buf.getShort();
            }
        }

        Map<String, String> cnames = new HashMap<>();
        Map<String, List<String>> aRecords = new HashMap<>();

        for (int i = 0; i < anCount && buf.hasRemaining(); i++) {
            String rName = normalizeTarget(EmbeddedDnsServer.readName(buf, response)).toLowerCase();
            if (!buf.hasRemaining()) {
                break;
            }
            short rType = buf.getShort();
            if (!buf.hasRemaining()) {
                break;
            }
            buf.getShort(); // rClass
            if (buf.remaining() < 6) {
                break;
            }
            buf.getInt();   // ttl
            int rdLength = buf.getShort() & 0xFFFF;
            if (buf.remaining() < rdLength) {
                break;
            }

            if (rType == 1 && rdLength == 4) {
                int b1 = buf.get() & 0xFF;
                int b2 = buf.get() & 0xFF;
                int b3 = buf.get() & 0xFF;
                int b4 = buf.get() & 0xFF;
                String ip = b1 + "." + b2 + "." + b3 + "." + b4;
                aRecords.computeIfAbsent(rName, k -> new ArrayList<>()).add(ip);
            } else if (rType == 5) {
                ByteBuffer rdataBuf = buf.slice();
                rdataBuf.limit(rdLength);
                String cnameTarget = normalizeTarget(EmbeddedDnsServer.readName(rdataBuf, response)).toLowerCase();
                if (!cnameTarget.isEmpty()) {
                    cnames.put(rName, cnameTarget);
                }
                buf.position(buf.position() + rdLength);
            } else {
                buf.position(buf.position() + rdLength);
            }
        }

        String current = normalizeTarget(expectedTarget).toLowerCase();
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < MAX_CNAME_DEPTH && visited.add(current); depth++) {
            List<String> directA = aRecords.get(current);
            if (directA != null && !directA.isEmpty()) {
                return directA;
            }
            String next = cnames.get(current);
            if (next == null) {
                break;
            }
            current = next;
        }

        return List.of();
    }

    private static String normalizeTarget(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean isIpv4(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String[] parts = s.split("\\.");
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
