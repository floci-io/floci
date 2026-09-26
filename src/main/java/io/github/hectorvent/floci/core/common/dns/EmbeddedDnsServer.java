package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded UDP/53 DNS server that runs inside the Floci container and is injected
 * into every spawned container (Lambda, RDS, ElastiCache) as their DNS resolver.
 *
 * Resolves *.{floci.hostname} (and any configured extra-suffixes) to Floci's own
 * Docker network IP so virtual-hosted S3 URLs (my-bucket.floci:4566) work from
 * inside Lambda containers without requiring wildcard Docker aliases.
 *
 * Also answers for any name a {@link DnsRecordSource} owns, which is how a service that holds
 * a private DNS zone (Cloud Map) gets real records rather than only API responses.
 *
 * All other queries are forwarded to the upstream resolvers read from /etc/resolv.conf
 * (Docker's embedded DNS at 127.0.0.11), falling back to the configured public resolvers
 * (floci.dns.container-fallback-servers) so public hostnames still resolve when the
 * resolv.conf resolver does not answer.
 *
 * Only starts when Floci detects it is running inside Docker. No-op on the host.
 */
@ApplicationScoped
@Startup
public class EmbeddedDnsServer {

    private static final Logger LOG = Logger.getLogger(EmbeddedDnsServer.class);
    private static final int DNS_PORT = 53;
    private static final int TTL = 60;
    private static final String FALLBACK_UPSTREAM = "127.0.0.11";
    // EDNS0-capable resolvers (Node/c-ares, glibc) advertise UDP payloads well above the
    // legacy 512-byte limit; CDN-backed public names return larger responses. Receiving into
    // a 512-byte buffer silently truncates the datagram and corrupts the forwarded answer.
    private static final int MAX_DNS_UDP_RESPONSE = 4096;
    // Per-upstream timeout. Bounded so trying every upstream stays under a typical 5s client
    // resolver timeout even in the worst case.
    private static final int FORWARD_TIMEOUT_MS = 1500;
    public static final String DEFAULT_SUFFIX = "localhost.floci.io";
    public static final String LOCALSTACK_SUFFIX = "localhost.localstack.cloud";
    private static final Pattern EC2_PRIVATE_DNS_NAME =
            Pattern.compile("^ip-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.ec2\\.internal$", Pattern.CASE_INSENSITIVE);

    // Well-known emulator wildcard DNS domains that always resolve to Floci's IP.
    // The suffix "localhost.X" covers "localhost.X" itself and "*.localhost.X" — it does
    // NOT cover "*.X" (e.g. "localhost.floci.io" does NOT resolve bare "*.floci.io").
    //   localhost.localstack.cloud → localhost.localstack.cloud, *.localhost.localstack.cloud
    //   localhost.floci.io         → localhost.floci.io, *.localhost.floci.io
    public static final List<String> BUILTIN_SUFFIXES = List.of(DEFAULT_SUFFIX, LOCALSTACK_SUFFIX);

    private volatile String serverIp;
    private final SequencedSet<String> suffixes = new LinkedHashSet<>();
    private volatile List<String> upstreamDnsServers = List.of();
    // Held as the Iterable a CDI Instance already is, so iterating resolves the beans lazily on
    // the packet path rather than at startup, where a source's storage must not be touched yet.
    private final Iterable<DnsRecordSource> recordSources;

    EmbeddedDnsServer(List<String> suffixes) {
        this(suffixes, List.of());
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources) {
        this.suffixes.addAll(BUILTIN_SUFFIXES);
        this.suffixes.addAll(suffixes);
        this.recordSources = recordSources;
    }

    @Inject
    public EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx,
                             Instance<DnsRecordSource> recordSources) {
        this.recordSources = recordSources;
        if (!containerDetector.isRunningInContainer()) {
            return;
        }
        try {
            String myIp = InetAddress.getLocalHost().getHostAddress();
            upstreamDnsServers = composeUpstreams(readResolvConfNameservers(),
                    config.dns().containerFallbackServers());

            suffixes.addAll(BUILTIN_SUFFIXES);
            config.hostname().ifPresent(suffixes::add);
            config.dns().extraSuffixes().ifPresent(suffixes::addAll);

            DatagramSocket socket = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
            socket.listen(DNS_PORT, "0.0.0.0", ar -> {
                if (ar.succeeded()) {
                    serverIp = myIp;
                    LOG.infov("Embedded DNS server started on {0}:53, resolving {1} → {0}", myIp, suffixes);
                    socket.handler(packet -> handleQuery(
                            vertx, socket, packet.data().getBytes(),
                            packet.sender().host(), packet.sender().port(), myIp));
                } else {
                    LOG.warnv("Embedded DNS server failed to bind on port 53: {0}", ar.cause().getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warnv("Failed to initialize embedded DNS server: {0}", e.getMessage());
        }
    }

    public Optional<String> getServerIp() {
        return Optional.ofNullable(serverIp);
    }

    // ── packet handling ───────────────────────────────────────────────────────

    private void handleQuery(Vertx vertx, DatagramSocket socket, byte[] data,
                             String senderHost, int senderPort, String myIp) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            short txId = buf.getShort();
            short flags = buf.getShort();
            short qdCount = buf.getShort();
            buf.getShort(); // ancount
            buf.getShort(); // nscount
            buf.getShort(); // arcount

            if ((flags & 0x8000) != 0 || qdCount < 1) {
                return; // not a standard query
            }

            int questionOffset = buf.position(); // always 12 for a standard query
            String qname = readName(buf, data);
            short qtype = buf.getShort();
            buf.getShort(); // qclass
            int questionEnd = buf.position();

            vertx.<Optional<List<String>>>executeBlocking(() -> resolveARecordWithOwnership(qname, myIp), false)
                    .onSuccess(answer -> {
                        if (answer.isEmpty()) {
                            forwardAsync(vertx, socket, data, senderHost, senderPort);
                            return;
                        }
                        List<String> addresses = answer.orElseThrow();
                        byte[] response = qtype == 1 && !addresses.isEmpty()
                                ? buildAResponse(data, txId, questionOffset, questionEnd, addresses)
                                : buildEmptyResponse(data, txId, questionOffset, questionEnd,
                                        addresses.isEmpty() ? 3 : 0);
                        socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {});
                    })
                    .onFailure(e -> LOG.warnv("DNS record lookup failed for {0}: {1}", qname, e.getMessage()));
        } catch (Exception e) {
            LOG.debugv("DNS packet error: {0}", e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    boolean matchesSuffix(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String suffix : suffixes) {
            String s = suffix.toLowerCase();
            if (lower.equals(s) || lower.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    List<String> resolveARecord(String name, String myIp) {
        return resolveARecordWithOwnership(name, myIp).orElse(List.of());
    }

    Optional<List<String>> resolveARecordWithOwnership(String name, String myIp) {
        if (matchesSuffix(name)) {
            return Optional.of(List.of(myIp));
        }
        Optional<String> ec2PrivateDnsName = resolveEc2PrivateDnsName(name);
        return ec2PrivateDnsName.<List<String>>map(List::of)
                .map(Optional::of).orElseGet(() -> resolveFromRecordSources(name));
    }

    /**
     * Answers from a service that owns a private DNS zone, Cloud Map being the one that does
     * today. A source that throws must not take the DNS server down with it: the query falls
     * through to the upstream resolvers, which is what happened before any source existed.
     */
    private Optional<List<String>> resolveFromRecordSources(String name) {
        if (recordSources == null) {
            return Optional.empty();
        }
        for (DnsRecordSource source : recordSources) {
            try {
                Optional<List<String>> addresses = source.resolveIpv4(name);
                if (addresses != null && addresses.isPresent()) {
                    return addresses;
                }
            } catch (Exception e) {
                LOG.debugv("DNS record source {0} failed to resolve {1}: {2}",
                        source.getClass().getSimpleName(), name, e.getMessage());
            }
        }
        return Optional.empty();
    }

    byte[] buildEmptyResponse(byte[] query, short txId, int questionOffset, int questionEnd, int responseCode) {
        ByteBuffer response = ByteBuffer.allocate(12 + questionEnd - questionOffset);
        response.putShort(txId);
        response.putShort((short) (0x8580 | responseCode));
        response.putShort((short) 1);
        response.putShort((short) 0);
        response.putShort((short) 0);
        response.putShort((short) 0);
        response.put(query, questionOffset, questionEnd - questionOffset);
        return response.array();
    }

    Optional<String> resolveEc2PrivateDnsName(String name) {
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

    String readName(ByteBuffer buf, byte[] data) {
        StringBuilder sb = new StringBuilder();
        int safety = 0;
        while (buf.hasRemaining() && safety++ < 128) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                // compression pointer
                int offset = ((len & 0x3F) << 8) | (buf.get() & 0xFF);
                ByteBuffer ptr = ByteBuffer.wrap(data);
                ptr.position(offset);
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(readName(ptr, data));
                return sb.toString();
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            byte[] label = new byte[len];
            buf.get(label);
            sb.append(new String(label));
        }
        return sb.toString();
    }

    byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd, List<String> ips) {
        int questionLength = questionEnd - questionOffset;
        // header(12) + question + per answer(name-ptr(2) + type(2) + class(2) + ttl(4) + rdlen(2) + rdata(4))
        ByteBuffer resp = ByteBuffer.allocate(12 + questionLength + 16 * ips.size());

        // header
        resp.putShort(txId);
        resp.putShort((short) 0x8180);      // QR=1, AA=1, RD=1, RCODE=0
        resp.putShort((short) 1);           // qdcount
        resp.putShort((short) ips.size());  // ancount
        resp.putShort((short) 0);           // nscount
        resp.putShort((short) 0);           // arcount

        // question (copied verbatim from query)
        resp.put(query, questionOffset, questionLength);

        // answers. A name with several registered addresses gets one A record each, which is
        // what a Cloud Map service backed by more than one instance resolves to on AWS.
        for (String ip : ips) {
            resp.putShort((short) 0xC00C); // name pointer to offset 12 (start of question name)
            resp.putShort((short) 1);       // type A
            resp.putShort((short) 1);       // class IN
            resp.putInt(TTL);
            resp.putShort((short) 4);       // rdlength

            for (String octet : ip.split("\\.")) {
                resp.put((byte) Integer.parseInt(octet));
            }
        }

        return resp.array();
    }

    private void forwardAsync(Vertx vertx, DatagramSocket socket, byte[] query,
                              String senderHost, int senderPort) {
        List<String> upstreams = upstreamDnsServers;
        if (upstreams.isEmpty()) {
            return;
        }
        vertx.executeBlocking(() -> forwardToUpstreams(query, upstreams, DNS_PORT))
                .onSuccess(response ->
                        socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {}))
                .onFailure(e ->
                        LOG.warnv("DNS forwarding failed on all upstreams {0}: {1}",
                                upstreams, e.getMessage()));
    }

    /**
     * Forwards the query to each upstream in order and returns the first valid UDP response.
     * Throws if every upstream times out or errors, so the caller can log a single warning.
     * The {@code upstreamPort} is parameterised for tests; production always uses {@link #DNS_PORT}.
     */
    byte[] forwardToUpstreams(byte[] query, List<String> upstreams, int upstreamPort) throws Exception {
        Exception last = null;
        for (String upstream : upstreams) {
            try (java.net.DatagramSocket fwd = new java.net.DatagramSocket()) {
                fwd.setSoTimeout(FORWARD_TIMEOUT_MS);
                InetAddress addr = InetAddress.getByName(upstream);
                fwd.send(new DatagramPacket(query, query.length, addr, upstreamPort));
                byte[] buf = new byte[MAX_DNS_UDP_RESPONSE];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                fwd.receive(resp);
                return Arrays.copyOf(resp.getData(), resp.getLength());
            } catch (Exception e) {
                last = e;
                LOG.debugv("DNS forward to {0} failed: {1}", upstream, e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no upstream resolvers configured");
    }

    /**
     * Builds the ordered, de-duplicated upstream list the forwarder tries in turn: the
     * resolver(s) from {@code /etc/resolv.conf} first (or Docker's embedded resolver as a
     * baseline when none are usable), then the configured public fallbacks. The fallbacks let
     * public names resolve even when the resolv.conf resolver does not answer, mirroring the
     * {@code --dns <FlociIP> --dns 8.8.8.8} workaround.
     */
    static List<String> composeUpstreams(List<String> resolvConf, List<String> fallbacks) {
        SequencedSet<String> ordered = new LinkedHashSet<>();
        for (String server : resolvConf) {
            if (isUsableUpstream(server)) {
                ordered.add(server.trim());
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(FALLBACK_UPSTREAM);
        }
        if (fallbacks != null) {
            for (String server : fallbacks) {
                if (isUsableUpstream(server)) {
                    ordered.add(server.trim());
                }
            }
        }
        return List.copyOf(ordered);
    }

    private static boolean isUsableUpstream(String server) {
        return server != null && !server.isBlank() && !server.trim().equals("127.0.0.1");
    }

    private List<String> readResolvConfNameservers() {
        List<String> servers = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of("/etc/resolv.conf"))) {
                line = line.trim();
                if (line.startsWith("nameserver ")) {
                    servers.add(line.substring("nameserver ".length()).trim());
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not read /etc/resolv.conf: {0}", e.getMessage());
        }
        return servers;
    }
}
