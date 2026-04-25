package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Embedded UDP/53 DNS server that runs inside the Floci container and is injected
 * into every spawned container (Lambda, RDS, ElastiCache) as their DNS resolver.
 *
 * Resolves *.{floci.hostname} (and any configured extra-suffixes) to Floci's own
 * Docker network IP so virtual-hosted S3 URLs (my-bucket.floci:4566) work from
 * inside Lambda containers without requiring wildcard Docker aliases.
 *
 * All other queries are forwarded transparently to the upstream resolver read from
 * /etc/resolv.conf (Docker's embedded DNS at 127.0.0.11).
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
    public static final String DEFAULT_SUFFIX = "localhost.floci.io";

    private volatile String serverIp;
    private final List<String> suffixes = new ArrayList<>();
    private String upstreamDns;

    EmbeddedDnsServer(List<String> suffixes) {
        this.suffixes.addAll(suffixes);
    }

    @Inject
    public EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx) {
        if (!containerDetector.isRunningInContainer()) {
            return;
        }
        try {
            String myIp = InetAddress.getLocalHost().getHostAddress();
            upstreamDns = readUpstreamDns();

            suffixes.add(config.hostname().orElse(DEFAULT_SUFFIX));
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

            if (qtype == 1 && matchesSuffix(qname)) {
                byte[] response = buildAResponse(data, txId, questionOffset, questionEnd, myIp);
                socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {});
            } else {
                forwardAsync(vertx, socket, data, senderHost, senderPort);
            }
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

    byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd, String ip) {
        int questionLength = questionEnd - questionOffset;
        // header(12) + question + answer(name-ptr(2) + type(2) + class(2) + ttl(4) + rdlen(2) + rdata(4))
        ByteBuffer resp = ByteBuffer.allocate(12 + questionLength + 16);

        // header
        resp.putShort(txId);
        resp.putShort((short) 0x8180); // QR=1, AA=1, RD=1, RCODE=0
        resp.putShort((short) 1);      // qdcount
        resp.putShort((short) 1);      // ancount
        resp.putShort((short) 0);      // nscount
        resp.putShort((short) 0);      // arcount

        // question (copied verbatim from query)
        resp.put(query, questionOffset, questionLength);

        // answer
        resp.putShort((short) 0xC00C); // name pointer to offset 12 (start of question name)
        resp.putShort((short) 1);       // type A
        resp.putShort((short) 1);       // class IN
        resp.putInt(TTL);
        resp.putShort((short) 4);       // rdlength

        for (String octet : ip.split("\\.")) {
            resp.put((byte) Integer.parseInt(octet));
        }

        return resp.array();
    }

    private void forwardAsync(Vertx vertx, DatagramSocket socket, byte[] query,
                              String senderHost, int senderPort) {
        String upstream = upstreamDns;
        if (upstream == null) {
            return;
        }
        vertx.executeBlocking(() -> {
            try (java.net.DatagramSocket fwd = new java.net.DatagramSocket()) {
                fwd.setSoTimeout(2000);
                InetAddress addr = InetAddress.getByName(upstream);
                fwd.send(new DatagramPacket(query, query.length, addr, DNS_PORT));
                byte[] buf = new byte[512];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                fwd.receive(resp);
                return Arrays.copyOf(resp.getData(), resp.getLength());
            }
        }).onSuccess(response ->
            socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {})
        ).onFailure(e ->
            LOG.debugv("DNS forwarding to {0} failed: {1}", upstream, e.getMessage())
        );
    }

    private String readUpstreamDns() {
        try {
            for (String line : Files.readAllLines(Path.of("/etc/resolv.conf"))) {
                line = line.trim();
                if (line.startsWith("nameserver ")) {
                    String server = line.substring("nameserver ".length()).trim();
                    if (!server.equals("127.0.0.1")) {
                        return server;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not read /etc/resolv.conf: {0}", e.getMessage());
        }
        return FALLBACK_UPSTREAM;
    }
}
