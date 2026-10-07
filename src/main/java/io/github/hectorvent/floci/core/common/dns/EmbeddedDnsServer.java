package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.concurrent.ThreadLocalRandom;
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
 * When floci.dns.spoof-aws-endpoints is enabled, every AWS partition suffix and all of its
 * subdomains resolve to Floci's IP as well (transparent endpoint injection), so
 * clients built with explicit real-AWS endpoints land on the emulator.
 *
 * Also answers for any name a {@link DnsRecordSource} owns, which is how a service that holds
 * a private DNS zone (Cloud Map) gets real records rather than only API responses.
 *
 * A {@link DnsForwardingRuleSource} can steer a query away from all of that before it is
 * resolved, which is how a Route 53 Resolver forwarding rule associated with the querying
 * container's VPC sends matching names to the resolvers the rule names instead.
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
    static final int DNS_PORT = 53;
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
            Pattern.compile("^ip-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.(?:ec2|((?:[a-z0-9]+-)+\\d+)\\.compute)\\.internal$",
                    Pattern.CASE_INSENSITIVE);

    // Well-known emulator wildcard DNS domains that always resolve to Floci's IP.
    // The suffix "localhost.X" covers "localhost.X" itself and "*.localhost.X"; it does
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
    private final Iterable<DnsForwardingRuleSource> forwardingRuleSources;
    private final Iterable<DnsClientVpcSource> clientVpcSources;

    EmbeddedDnsServer(List<String> suffixes) {
        this(suffixes, List.of());
    }

    /**
     * The certificate generator cannot read application.yml, so DNS spoofing without AWS SANs on
     * the certificate only happens when the flag and TLS are on but the flag is not visible to it.
     */
    static boolean shouldWarnSpoofInvisibleToTls(boolean spoofConfigured, boolean tlsEnabled,
                                                 boolean spoofVisibleToTls) {
        return spoofConfigured && tlsEnabled && !spoofVisibleToTls;
    }

    EmbeddedDnsServer(List<String> suffixes, boolean spoofAwsEndpoints) {
        this(suffixes, List.of());
        if (spoofAwsEndpoints) {
            this.suffixes.addAll(TlsConfigSource.awsEndpointSuffixes());
        }
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources) {
        this(suffixes, recordSources, List.of(), List.of());
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources,
                      Iterable<DnsForwardingRuleSource> forwardingRuleSources,
                      Iterable<DnsClientVpcSource> clientVpcSources) {
        this.suffixes.addAll(BUILTIN_SUFFIXES);
        this.suffixes.addAll(suffixes);
        this.recordSources = recordSources;
        this.forwardingRuleSources = forwardingRuleSources;
        this.clientVpcSources = clientVpcSources;
    }

    @Inject
    public EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx,
                             Instance<DnsRecordSource> recordSources,
                             Instance<DnsForwardingRuleSource> forwardingRuleSources,
                             Instance<DnsClientVpcSource> clientVpcSources) {
        this.recordSources = recordSources;
        this.forwardingRuleSources = forwardingRuleSources;
        this.clientVpcSources = clientVpcSources;
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
            if (config.dns().spoofAwsEndpoints()) {
                suffixes.addAll(TlsConfigSource.awsEndpointSuffixes());
                if (shouldWarnSpoofInvisibleToTls(true, TlsConfigSource.tlsEnabledVisibleToTls(),
                        TlsConfigSource.spoofAwsEndpointsVisibleToTls())) {
                    LOG.warnv("floci.dns.spoof-aws-endpoints is set in application config but the TLS certificate only reads it from FLOCI_DNS_SPOOF_AWS_ENDPOINTS or -Dfloci.dns.spoof-aws-endpoints; https:// calls to {0} will fail the handshake until it is set there.", TlsConfigSource.awsEndpointSuffixes());
                }
            }

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

    void handleQuery(Vertx vertx, DatagramSocket socket, byte[] data,
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

            vertx.<QueryPlan>executeBlocking(() -> planQuery(qname, senderHost, myIp, qtype), false)
                    .onSuccess(plan -> {
                        if (plan.answer().isEmpty()) {
                            if (plan.ruleTargets().isEmpty()) {
                                forwardAsync(vertx, socket, data, senderHost, senderPort);
                            } else {
                                forwardToRuleTargets(vertx, socket, data, senderHost, senderPort,
                                        plan.ruleTargets(), qname, txId, questionOffset, questionEnd);
                            }
                            return;
                        }
                        DnsAnswer records = plan.answer().orElseThrow();
                        byte[] response = !records.isEmpty()
                                ? buildAResponse(data, txId, questionOffset, questionEnd, records)
                                : buildEmptyResponse(data, txId, questionOffset, questionEnd,
                                        records.nameExists() ? 0 : 3);
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
        return resolveARecordWithOwnership(name, myIp)
                .map(DnsAnswer::addresses).orElse(List.of());
    }

    Optional<DnsAnswer> resolveARecordWithOwnership(String name, String myIp) {
        return resolveRecordWithOwnership(name, myIp, 1);
    }

    private Optional<DnsAnswer> resolveRecordWithOwnership(String name, String myIp, int type) {
        Optional<DnsAnswer> emulatorName = resolveEmulatorName(name, myIp, type);
        return emulatorName.isPresent() ? emulatorName : resolveFromRecordSources(name, type);
    }

    /**
     * What happens to one query, decided on a worker thread because every step behind it reads
     * storage: an answer Floci owns, a set of resolvers a rule forwards the query to, or neither,
     * which leaves it to the upstream resolvers.
     */
    record QueryPlan(Optional<DnsAnswer> answer, List<DnsForwardingRule.Target> ruleTargets) {

        static final QueryPlan UPSTREAM = new QueryPlan(Optional.empty(), List.of());

        static QueryPlan answering(DnsAnswer answer) {
            return new QueryPlan(Optional.of(answer), List.of());
        }

        static QueryPlan forwardingTo(List<DnsForwardingRule.Target> targets) {
            return new QueryPlan(Optional.empty(), targets);
        }
    }

    /**
     * Names the emulator itself owns come first, whatever the rules say. Route 53 Resolver does
     * the same for the AWS-internal domains it autodefines system rules for, so that a rule for a
     * broad domain (or for "." itself) cannot send the platform's own names off to a resolver that
     * knows nothing about them.
     *
     * <p>A forwarding rule associated with the querying VPC is then applied before Floci resolves
     * the name from a record source, because a Resolver rule takes precedence over a private
     * hosted zone that matches the same name.
     */
    QueryPlan planQuery(String name, String clientAddress, String myIp, int type) {
        Optional<DnsAnswer> emulatorName = resolveEmulatorName(name, myIp, type);
        if (emulatorName.isPresent()) {
            return QueryPlan.answering(emulatorName.orElseThrow());
        }
        // A rule steers the name whatever the query asks about it, so this is deliberately not
        // gated on the record type: the raw query is relayed to the rule's resolvers as it stands.
        Optional<DnsForwardingRule> rule = matchingRule(name, clientAddress);
        if (rule.isPresent() && rule.orElseThrow().forwards()) {
            return QueryPlan.forwardingTo(rule.orElseThrow().targets());
        }
        return resolveFromRecordSources(name, type).map(QueryPlan::answering).orElse(QueryPlan.UPSTREAM);
    }

    private Optional<DnsAnswer> resolveEmulatorName(String name, String myIp, int type) {
        if (matchesSuffix(name)) {
            return Optional.of(type == 1 ? DnsAnswer.records(List.of(myIp), DnsAnswer.DEFAULT_TTL_SECONDS)
                    : DnsAnswer.noData());
        }
        return resolveEc2PrivateDnsName(name)
                .map(address -> type == 1 ? DnsAnswer.records(List.of(address), DnsAnswer.DEFAULT_TTL_SECONDS)
                        : DnsAnswer.noData());
    }

    /**
     * The rule that governs this query: the most specific one whose domain matches the name among
     * those associated with the VPC the query came from. A source that throws is skipped rather
     * than allowed to fail the query, which is how {@link #resolveFromRecordSources} treats one too.
     */
    Optional<DnsForwardingRule> matchingRule(String name, String clientAddress) {
        if (forwardingRuleSources == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<DnsClientVpcSource.ClientVpc> clientVpc = vpcForClient(clientAddress);
        if (clientVpc.isEmpty()) {
            return Optional.empty();
        }
        DnsClientVpcSource.ClientVpc origin = clientVpc.orElseThrow();
        DnsForwardingRule best = null;
        for (DnsForwardingRuleSource source : forwardingRuleSources) {
            try {
                for (DnsForwardingRule rule :
                        source.rulesFor(origin.accountId(), origin.region(), origin.vpcId())) {
                    if (rule == null || !rule.matches(name)) {
                        continue;
                    }
                    if (best == null || rule.specificity() > best.specificity()) {
                        best = rule;
                    }
                }
            } catch (Exception e) {
                LOG.debugv("DNS forwarding rule source {0} failed for {1}: {2}",
                        source.getClass().getSimpleName(), name, e.getMessage());
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<DnsClientVpcSource.ClientVpc> vpcForClient(String clientAddress) {
        if (clientVpcSources == null || clientAddress == null || clientAddress.isBlank()) {
            return Optional.empty();
        }
        for (DnsClientVpcSource source : clientVpcSources) {
            try {
                Optional<DnsClientVpcSource.ClientVpc> clientVpc =
                        source.vpcForClient(clientAddress.trim());
                if (clientVpc != null && clientVpc.isPresent() && isUsableOrigin(clientVpc.orElseThrow())) {
                    return clientVpc;
                }
            } catch (Exception e) {
                LOG.debugv("DNS client VPC source {0} failed for {1}: {2}",
                        source.getClass().getSimpleName(), clientAddress, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static boolean isUsableOrigin(DnsClientVpcSource.ClientVpc clientVpc) {
        return isPresent(clientVpc.accountId()) && isPresent(clientVpc.region())
                && isPresent(clientVpc.vpcId());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Answers from a service that owns a private DNS zone, Cloud Map being the one that does
     * today. A source that throws must not take the DNS server down with it: the query falls
     * through to the upstream resolvers, which is what happened before any source existed.
     */
    private Optional<DnsAnswer> resolveFromRecordSources(String name, int type) {
        if (recordSources == null) {
            return Optional.empty();
        }
        for (DnsRecordSource source : recordSources) {
            try {
                Optional<DnsAnswer> answer = source.resolve(name, type);
                if (answer != null && answer.isPresent()) {
                    return answer;
                }
            } catch (Exception e) {
                LOG.debugv("DNS record source {0} failed to resolve {1}: {2}",
                        source.getClass().getSimpleName(), name, e.getMessage());
            }
        }
        return Optional.empty();
    }

    byte[] buildEmptyResponse(byte[] query, short txId, int questionOffset, int questionEnd, int responseCode) {
        return buildAnswerlessResponse(query, txId, questionOffset, questionEnd,
                (short) (0x8580 | responseCode));
    }

    /**
     * The failure answer for a query a forwarding rule claimed and no target answered. Not
     * authoritative, unlike {@link #buildEmptyResponse}: Floci is not the authority for a name a
     * rule sends elsewhere, it only failed to reach the resolver that is.
     */
    byte[] buildServerFailureResponse(byte[] query, short txId, int questionOffset, int questionEnd) {
        return buildAnswerlessResponse(query, txId, questionOffset, questionEnd, (short) 0x8182);
    }

    private static byte[] buildAnswerlessResponse(byte[] query, short txId, int questionOffset,
                                                  int questionEnd, short flags) {
        UdpPayload payload = udpPayload(query, questionEnd);
        ByteBuffer response = ByteBuffer.allocate(12 + questionEnd - questionOffset + (payload.edns() ? 11 : 0));
        response.putShort(txId);
        response.putShort(flags);
        response.putShort((short) 1);
        response.putShort((short) 0);
        response.putShort((short) 0);
        response.putShort((short) (payload.edns() ? 1 : 0));
        response.put(query, questionOffset, questionEnd - questionOffset);
        appendOpt(response, payload);
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
        if (matcher.group(5) != null && !RegionResolver.isKnownRegion(matcher.group(5))) {
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

    static String readName(ByteBuffer buf, byte[] data) {
        return readName(buf, data, 0);
    }

    private static String readName(ByteBuffer buf, byte[] data, int depth) {
        if (depth > 16) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int safety = 0;
        while (buf.hasRemaining() && safety++ < 128) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                // compression pointer
                if (!buf.hasRemaining()) {
                    break;
                }
                int offset = ((len & 0x3F) << 8) | (buf.get() & 0xFF);
                if (offset >= data.length) {
                    break;
                }
                ByteBuffer ptr = ByteBuffer.wrap(data);
                ptr.position(offset);
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(readName(ptr, data, depth + 1));
                return sb.toString();
            }
            if (buf.remaining() < len) {
                buf.position(buf.limit());
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            byte[] label = new byte[len];
            buf.get(label);
            sb.append(new String(label, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd, DnsAnswer answer) {
        List<DnsRecord> records = answer.records();
        int questionLength = questionEnd - questionOffset;
        List<byte[]> data = records.stream().map(DnsRecord::data).toList();
        UdpPayload payload = udpPayload(query, questionEnd);
        int responseLength = 12 + questionLength + (payload.edns() ? 11 : 0);
        int answerCount = 0;
        for (byte[] recordData : data) {
            if (responseLength + 12 + recordData.length > payload.size()) {
                break;
            }
            responseLength += 12 + recordData.length;
            answerCount++;
        }
        boolean truncated = answerCount < records.size();
        ByteBuffer resp = ByteBuffer.allocate(responseLength);

        // header
        resp.putShort(txId);
        resp.putShort((short) (0x8180 | (truncated ? 0x0200 : 0))); // QR=1, RD=1, RCODE=0
        resp.putShort((short) 1);           // qdcount
        resp.putShort((short) answerCount); // ancount
        resp.putShort((short) 0);           // nscount
        resp.putShort((short) (payload.edns() ? 1 : 0)); // arcount

        // question (copied verbatim from query)
        resp.put(query, questionOffset, questionLength);

        // Each answer carries its own type and rdata; a CNAME can answer an address query.
        for (int i = 0; i < answerCount; i++) {
            resp.putShort((short) 0xC00C); // name pointer to offset 12 (start of question name)
            resp.putShort((short) records.get(i).type());
            resp.putShort((short) 1);       // class IN
            resp.putInt(answer.ttlSeconds());
            resp.putShort((short) data.get(i).length);
            resp.put(data.get(i));
        }
        appendOpt(resp, payload);
        return resp.array();
    }

    private record UdpPayload(int size, boolean edns) {}

    private static UdpPayload udpPayload(byte[] query, int questionEnd) {
        UdpPayload legacy = new UdpPayload(512, false);
        ByteBuffer packet = ByteBuffer.wrap(query);
        if (query.length < 12 || packet.getShort(4) != 1
                || packet.getShort(6) != 0 || packet.getShort(8) != 0) {
            return legacy;
        }
        int additionalCount = Short.toUnsignedInt(packet.getShort(10));
        packet.position(questionEnd);
        for (int i = 0; i < additionalCount; i++) {
            readName(packet, query);
            if (packet.remaining() < 10) {
                return legacy;
            }
            int type = Short.toUnsignedInt(packet.getShort());
            int size = Short.toUnsignedInt(packet.getShort());
            int ttl = packet.getInt();
            int dataLength = Short.toUnsignedInt(packet.getShort());
            if (packet.remaining() < dataLength) {
                return legacy;
            }
            packet.position(packet.position() + dataLength);
            if (type == 41 && ((ttl >>> 16) & 0xFF) == 0) {
                return new UdpPayload(Math.max(512, Math.min(size, MAX_DNS_UDP_RESPONSE)), true);
            }
        }
        return legacy;
    }

    private static void appendOpt(ByteBuffer response, UdpPayload payload) {
        if (payload.edns()) {
            response.put((byte) 0).putShort((short) 41).putShort((short) payload.size())
                    .putInt(0).putShort((short) 0);
        }
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
     * Sends the query to the resolvers a forwarding rule names instead of to the upstreams. The
     * order is shuffled because Route 53 Resolver picks a rule's target at random and retries
     * another one when it does not answer.
     *
     * <p>A rule whose targets are all unreachable fails this query with SERVFAIL rather than
     * falling back to the upstream resolvers. The rule said where the name lives, so an upstream
     * answer for it would be the wrong answer, and resolution of every other name is untouched.
     */
    private void forwardToRuleTargets(Vertx vertx, DatagramSocket socket, byte[] query,
                                      String senderHost, int senderPort,
                                      List<DnsForwardingRule.Target> targets, String qname,
                                      short txId, int questionOffset, int questionEnd) {
        vertx.executeBlocking(() -> forwardToTargets(query, shuffled(targets)))
                .onSuccess(response ->
                        socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {}))
                .onFailure(e -> {
                    LOG.warnv("Resolver rule forwarding for {0} failed on every target {1}: {2}",
                            qname, targets, e.getMessage());
                    socket.send(Buffer.buffer(
                                    buildServerFailureResponse(query, txId, questionOffset, questionEnd)),
                            senderPort, senderHost, v -> {});
                });
    }

    private static List<DnsForwardingRule.Target> shuffled(List<DnsForwardingRule.Target> targets) {
        if (targets.size() < 2) {
            return targets;
        }
        List<DnsForwardingRule.Target> order = new ArrayList<>(targets);
        Collections.shuffle(order, ThreadLocalRandom.current());
        return order;
    }

    /**
     * Forwards the query to each upstream in order and returns the first valid UDP response.
     * Throws if every upstream times out or errors, so the caller can log a single warning.
     * The {@code upstreamPort} is parameterised for tests; production always uses {@link #DNS_PORT}.
     */
    static byte[] forwardToUpstreams(byte[] query, List<String> upstreams, int upstreamPort) throws Exception {
        return forwardToTargets(query, upstreams.stream()
                .map(upstream -> new DnsForwardingRule.Target(upstream, upstreamPort)).toList());
    }

    /**
     * Forwards the query to each target in order and returns the first valid UDP response. Targets
     * carry their own port because a resolver rule may name a resolver that does not listen on 53.
     */
    static byte[] forwardToTargets(byte[] query, List<DnsForwardingRule.Target> targets) throws Exception {
        Exception last = null;
        for (DnsForwardingRule.Target target : targets) {
            try (java.net.DatagramSocket fwd = new java.net.DatagramSocket()) {
                fwd.setSoTimeout(FORWARD_TIMEOUT_MS);
                InetAddress addr = InetAddress.getByName(target.address());
                fwd.send(new DatagramPacket(query, query.length, addr, target.port()));
                byte[] buf = new byte[MAX_DNS_UDP_RESPONSE];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                fwd.receive(resp);
                return Arrays.copyOf(resp.getData(), resp.getLength());
            } catch (Exception e) {
                last = e;
                LOG.debugv("DNS forward to {0} failed: {1}", target.address(), e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no resolvers to forward to");
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

    static List<String> readResolvConfNameservers() {
        List<String> servers = new ArrayList<>();
        try {
            Path path = Path.of("/etc/resolv.conf");
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path)) {
                    line = line.trim();
                    if (line.startsWith("nameserver ")) {
                        servers.add(line.substring("nameserver ".length()).trim());
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not read /etc/resolv.conf: {0}", e.getMessage());
        }
        return servers;
    }
}
