package io.github.hectorvent.floci.core.common.dns;

import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDnsServerTest {

    private EmbeddedDnsServer dns;

    @BeforeEach
    void setUp() {
        dns = new EmbeddedDnsServer(List.of("localhost.floci.io"));
    }

    // ── matchesSuffix — configured suffix ────────────────────────────────────

    @Test
    void matchesSuffix_exactMatch() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_singleSubdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_deeplyNested() {
        assertTrue(dns.matchesSuffix("deeply.nested.bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_caseInsensitive() {
        assertTrue(dns.matchesSuffix("My-Bucket.Localhost.Floci.IO"));
    }

    @Test
    void matchesSuffix_noMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.s3.amazonaws.com"));
    }

    @Test
    void matchesSuffix_partialSuffixNoMatch() {
        assertFalse(dns.matchesSuffix("floci.io"));
    }

    // bare *.floci.io (without localhost.) must NOT match — only *.localhost.floci.io is registered
    @Test
    void matchesSuffix_bareFlociIoSubdomainNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.floci.io"));
    }

    @Test
    void matchesSuffix_bareS3FlociIoNoMatch() {
        assertFalse(dns.matchesSuffix("s3.floci.io"));
    }

    // bare *.localstack.cloud (without localhost.) must NOT match either
    @Test
    void matchesSuffix_bareLocalstackCloudNoMatch() {
        assertFalse(dns.matchesSuffix("my-bucket.localstack.cloud"));
    }

    @Test
    void matchesSuffix_nullAndEmpty() {
        assertFalse(dns.matchesSuffix(null));
        assertFalse(dns.matchesSuffix(""));
    }

    // ── EC2 private DNS names ────────────────────────────────────────────────

    @Test
    void resolveEc2PrivateDnsName_decodesAwsIpName() {
        assertEquals(
                "172.16.128.9",
                dns.resolveEc2PrivateDnsName("ip-172-16-128-9.ec2.internal").orElseThrow());
    }

    @Test
    void resolveEc2PrivateDnsName_decodesRegionalIpName() {
        assertEquals("172.16.128.9", dns.resolveEc2PrivateDnsName(
                "ip-172-16-128-9.us-west-2.compute.internal").orElseThrow());
        assertEquals("10.42.32.17", dns.resolveEc2PrivateDnsName(
                "ip-10-42-32-17.eu-central-1.compute.internal").orElseThrow());
        assertTrue(dns.resolveEc2PrivateDnsName("ip-172-16-128-9.invalid.compute.internal").isEmpty());
        assertTrue(dns.resolveEc2PrivateDnsName("ip-172-16-128-9.my-cd-1.compute.internal").isEmpty());
    }

    @Test
    void resolveEc2PrivateDnsName_decodesUnpublishedRegionIpName() {
        assertEquals("10.0.0.5", dns.resolveEc2PrivateDnsName(
                "ip-10-0-0-5.us-new-1.compute.internal").orElseThrow());
    }

    @Test
    void resolveEc2PrivateDnsName_isCaseInsensitive() {
        assertEquals(
                "10.42.32.17",
                dns.resolveEc2PrivateDnsName("IP-10-42-32-17.EC2.INTERNAL").orElseThrow());
    }

    @Test
    void resolveEc2PrivateDnsName_rejectsInvalidOctets() {
        assertTrue(dns.resolveEc2PrivateDnsName("ip-172-16-128-300.ec2.internal").isEmpty());
    }

    @Test
    void resolveARecord_prefersEc2PrivateDnsAddressOverFlociWildcard() {
        assertEquals(
                "172.16.128.9",
                dns.resolveARecord("ip-172-16-128-9.ec2.internal", "172.16.128.5").getFirst());
    }

    @Test
    void resolveARecord_answersRegionalEc2PrivateDnsName() {
        assertEquals("172.16.128.9", dns.resolveARecord(
                "ip-172-16-128-9.us-west-2.compute.internal", "172.16.128.5").getFirst());
    }

    // ── resolveARecord: DnsRecordSource ───────────────────────────────────────

    @Test
    void resolveARecord_answersFromARecordSource() {
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(
                List.of("localhost.floci.io"),
                List.of(source("valkey.sapphire.internal", List.of("172.31.0.6"))));

        assertEquals(List.of("172.31.0.6"),
                withSource.resolveARecord("valkey.sapphire.internal", "172.31.0.2"));
    }

    @Test
    void resolveARecord_returnsEveryAddressARecordSourceOwns() {
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(
                List.of("localhost.floci.io"),
                List.of(source("api.sapphire.internal", List.of("172.31.0.6", "172.31.0.7"))));

        assertEquals(List.of("172.31.0.6", "172.31.0.7"),
                withSource.resolveARecord("api.sapphire.internal", "172.31.0.2"));
    }

    @Test
    void resolveARecord_forwardsWhenNoRecordSourceOwnsTheName() {
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(
                List.of("localhost.floci.io"),
                List.of(source("valkey.sapphire.internal", List.of("172.31.0.6"))));

        assertTrue(withSource.resolveARecord("example.com", "172.31.0.2").isEmpty());
        assertTrue(withSource.resolveARecordWithOwnership("example.com", "172.31.0.2").isEmpty());
    }

    @Test
    void ownedNameWithNoAddressesDoesNotFallThrough() {
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(
                List.of(), List.of(source("empty.sapphire.internal", List.of())));

        assertEquals(List.of(), withSource.resolveARecordWithOwnership(
                "empty.sapphire.internal", "172.31.0.2").orElseThrow().addresses());
    }

    @Test
    void emptyOwnedNameProducesAuthoritativeNegativeResponse() {
        byte[] query = buildQuery("empty.sapphire.internal", (short) 0x1234);
        byte[] response = dns.buildEmptyResponse(query, (short) 0x1234, 12, query.length, 3);
        ByteBuffer header = ByteBuffer.wrap(response);

        assertEquals((short) 0x1234, header.getShort(0));
        assertEquals(3, header.getShort(2) & 0x000F);
        assertTrue((header.getShort(2) & 0x0400) != 0, "the answer must be authoritative");
        assertEquals(0, header.getShort(6));
    }

    @Test
    void resolveARecord_keepsAnsweringWhenARecordSourceThrows() {
        DnsRecordSource failing = name -> { throw new IllegalStateException("storage is down"); };
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(
                List.of("localhost.floci.io"),
                List.of(failing, source("valkey.sapphire.internal", List.of("172.31.0.6"))));

        assertEquals(List.of("172.31.0.6"),
                withSource.resolveARecord("valkey.sapphire.internal", "172.31.0.2"));
    }

    @Test
    void resolveARecord_prefersTheFlociWildcardOverARecordSource() {
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(
                List.of("localhost.floci.io"),
                List.of(source("bucket.localhost.floci.io", List.of("172.31.0.6"))));

        assertEquals(List.of("172.31.0.2"),
                withSource.resolveARecord("bucket.localhost.floci.io", "172.31.0.2"));
    }

    private static DnsRecordSource source(String owned, List<String> addresses) {
        return source(owned, addresses, DnsAnswer.DEFAULT_TTL_SECONDS);
    }

    private static DnsRecordSource source(String owned, List<String> addresses, int ttlSeconds) {
        return name -> owned.equals(name)
                ? Optional.of(DnsAnswer.records(addresses, ttlSeconds)) : Optional.empty();
    }

    private static DnsAnswer answer(String... addresses) {
        return DnsAnswer.records(List.of(addresses), DnsAnswer.DEFAULT_TTL_SECONDS);
    }

    // ── planQuery: Route 53 Resolver rules ────────────────────────────────────

    private static final String CLIENT = "172.31.0.9";
    private static final String MY_IP = "172.31.0.2";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Test
    void forwardingRuleMatchesOnlyItsDomainAndTheSubdomainsBeneathIt() {
        DnsForwardingRule rule = forward("acme.example.com", "10.1.0.53");

        assertTrue(rule.matches("acme.example.com"));
        assertTrue(rule.matches("zenith.acme.example.com."));
        assertFalse(rule.matches("example.com"));
        assertFalse(rule.matches("nadir.example.com"));
        assertFalse(rule.matches("notacme.example.com"));
        assertEquals(3, rule.specificity());
        assertTrue(forward(".", "10.1.0.53").matches("anything.example.com"));
        assertEquals(0, forward(".", "10.1.0.53").specificity());
    }

    @Test
    void planQuery_forwardsToTheTargetsOfARuleAssociatedWithTheQueryingVpc() {
        EmbeddedDnsServer dns = withRules(List.of(),
                rules("vpc-a", forward("corp.internal", "10.1.0.53")), clientVpc("vpc-a"));

        EmbeddedDnsServer.QueryPlan plan = dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1);

        assertTrue(plan.answer().isEmpty());
        assertEquals(List.of(new DnsForwardingRule.Target("10.1.0.53", 53)), plan.ruleTargets());
    }

    @Test
    void planQuery_ignoresARuleThatIsNotAssociatedWithTheQueryingVpc() {
        EmbeddedDnsServer dns = withRules(List.of(),
                rules("vpc-other", forward("corp.internal", "10.1.0.53")), clientVpc("vpc-a"));

        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1));
    }

    @Test
    void planQuery_appliesNoRuleWhenNoSourceClaimsTheQueryAddress() {
        EmbeddedDnsServer dns = withRules(List.of(),
                rules("vpc-a", forward("corp.internal", "10.1.0.53")),
                clientAddress -> Optional.empty());

        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1));
    }

    @Test
    void planQuery_appliesNoRuleWhenTheQueryOriginIsIncomplete() {
        DnsForwardingRuleSource anyOrigin = (accountId, region, vpcId) ->
                List.of(forward("corp.internal", "10.1.0.53"));

        for (DnsClientVpcSource.ClientVpc partial : List.of(
                new DnsClientVpcSource.ClientVpc(ACCOUNT, REGION, ""),
                new DnsClientVpcSource.ClientVpc(ACCOUNT, "", "vpc-a"),
                new DnsClientVpcSource.ClientVpc("", REGION, "vpc-a"))) {
            EmbeddedDnsServer dns = withRules(List.of(), anyOrigin,
                    clientAddress -> Optional.of(partial));

            assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM,
                    dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1), partial.toString());
        }
    }

    @Test
    void planQuery_appliesTheMoreSpecificOfTwoMatchingRules() {
        EmbeddedDnsServer dns = withRules(List.of(),
                rules("vpc-a", forward("corp.internal", "10.1.0.53"),
                        forward("db.corp.internal", "10.2.0.53")),
                clientVpc("vpc-a"));

        assertEquals(List.of(new DnsForwardingRule.Target("10.2.0.53", 53)),
                dns.planQuery("replica.db.corp.internal", CLIENT, MY_IP, 1).ruleTargets());
        assertEquals(List.of(new DnsForwardingRule.Target("10.1.0.53", 53)),
                dns.planQuery("web.corp.internal", CLIENT, MY_IP, 1).ruleTargets());
    }

    @Test
    void planQuery_aSystemRuleCarvesASubdomainOutOfABroaderForwardRule() {
        EmbeddedDnsServer dns = withRules(
                List.of(source("acme.corp.internal", List.of("10.9.9.9"))),
                rules("vpc-a", forward("corp.internal", "10.1.0.53"),
                        DnsForwardingRule.system("acme.corp.internal")),
                clientVpc("vpc-a"));

        assertEquals(List.of("10.9.9.9"),
                dns.planQuery("acme.corp.internal", CLIENT, MY_IP, 1).answer().orElseThrow().addresses());
        assertEquals(List.of(new DnsForwardingRule.Target("10.1.0.53", 53)),
                dns.planQuery("other.corp.internal", CLIENT, MY_IP, 1).ruleTargets());
    }

    @Test
    void planQuery_aMatchingRuleTakesPrecedenceOverAPrivateHostedZoneRecord() {
        EmbeddedDnsServer dns = withRules(
                List.of(source("db.corp.internal", List.of("10.0.0.7"))),
                rules("vpc-a", forward("corp.internal", "10.1.0.53")), clientVpc("vpc-a"));

        EmbeddedDnsServer.QueryPlan plan = dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1);

        assertTrue(plan.answer().isEmpty(), "the rule decides the name, not the zone record");
        assertEquals(List.of(new DnsForwardingRule.Target("10.1.0.53", 53)), plan.ruleTargets());
    }

    @Test
    void planQuery_aPrivateHostedZoneRecordAnswersWhenNoRuleIsAssociated() {
        EmbeddedDnsServer dns = withRules(
                List.of(source("db.corp.internal", List.of("10.0.0.7"))),
                rules("vpc-other", forward("corp.internal", "10.1.0.53")), clientVpc("vpc-a"));

        assertEquals(List.of("10.0.0.7"),
                dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1).answer().orElseThrow().addresses());
    }

    @Test
    void planQuery_aNameMatchingNoRuleStillGoesToTheUpstreamResolvers() {
        EmbeddedDnsServer dns = withRules(
                List.of(source("db.corp.internal", List.of("10.0.0.7"))),
                rules("vpc-a", forward("corp.internal", "10.1.0.53")), clientVpc("vpc-a"));

        assertEquals(EmbeddedDnsServer.QueryPlan.UPSTREAM, dns.planQuery("example.com", CLIENT, MY_IP, 1));
    }

    @Test
    void planQuery_neverForwardsTheEmulatorsOwnNames() {
        EmbeddedDnsServer dns = withRules(List.of(), rules("vpc-a", forward(".", "10.1.0.53")),
                clientVpc("vpc-a"));

        assertEquals(List.of(MY_IP),
                dns.planQuery("bucket.localhost.floci.io", CLIENT, MY_IP, 1).answer().orElseThrow().addresses());
        assertEquals(List.of("172.16.128.9"),
                dns.planQuery("ip-172-16-128-9.ec2.internal", CLIENT, MY_IP, 1).answer().orElseThrow().addresses());
        assertEquals(List.of(new DnsForwardingRule.Target("10.1.0.53", 53)),
                dns.planQuery("example.com", CLIENT, MY_IP, 1).ruleTargets());
    }

    @Test
    void planQuery_keepsResolvingWhenARuleSourceThrows() {
        DnsForwardingRuleSource failing = (accountId, region, vpcId) -> {
            throw new IllegalStateException("storage is down");
        };
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of("localhost.floci.io"),
                List.of(source("db.corp.internal", List.of("10.0.0.7"))),
                List.of(failing), List.of(clientVpc("vpc-a")));

        assertEquals(List.of("10.0.0.7"),
                dns.planQuery("db.corp.internal", CLIENT, MY_IP, 1).answer().orElseThrow().addresses());
    }

    private static EmbeddedDnsServer withRules(List<DnsRecordSource> recordSources,
                                               DnsForwardingRuleSource ruleSource,
                                               DnsClientVpcSource clientVpcSource) {
        return new EmbeddedDnsServer(List.of("localhost.floci.io"), recordSources,
                List.of(ruleSource), List.of(clientVpcSource));
    }

    /** Returns the rules most specific first, as {@link DnsForwardingRuleSource} asks sources to. */
    private static DnsForwardingRuleSource rules(String associatedVpcId, DnsForwardingRule... rules) {
        List<DnsForwardingRule> ordered = Arrays.stream(rules)
                .sorted(Comparator.comparingInt(DnsForwardingRule::specificity).reversed())
                .toList();
        return (accountId, region, vpcId) -> ACCOUNT.equals(accountId) && REGION.equals(region)
                && associatedVpcId.equals(vpcId) ? ordered : List.of();
    }

    private static DnsClientVpcSource clientVpc(String vpcId) {
        return clientAddress -> CLIENT.equals(clientAddress)
                ? Optional.of(new DnsClientVpcSource.ClientVpc(ACCOUNT, REGION, vpcId))
                : Optional.empty();
    }

    private static DnsForwardingRule forward(String domainName, String... targets) {
        return DnsForwardingRule.forwardTo(domainName,
                Arrays.stream(targets).map(DnsForwardingRule.Target::new).toList());
    }

    @Test
    void buildAResponse_usesTheDefaultTtlForFlociNames() {
        DnsAnswer answer = dns.resolveARecordWithOwnership(
                "bucket.localhost.floci.io", "172.31.0.2").orElseThrow();
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 10);
        byte[] response = dns.buildAResponse(query, (short) 10, 12, query.length, answer);

        assertEquals(DnsAnswer.DEFAULT_TTL_SECONDS,
                ByteBuffer.wrap(response).getInt(query.length + 6));
    }

    @Test
    void buildAResponse_writesTheAnswerTtlOnEveryRecord() {
        EmbeddedDnsServer withSource = new EmbeddedDnsServer(List.of(),
                List.of(source("api.sapphire.internal", List.of("172.31.0.6", "172.31.0.7"), 15)));
        DnsAnswer answer = withSource.resolveARecordWithOwnership(
                "api.sapphire.internal", "172.31.0.2").orElseThrow();
        byte[] query = buildQuery("api.sapphire.internal", (short) 9);
        byte[] response = dns.buildAResponse(query, (short) 9, 12, query.length, answer);
        int firstTtl = 12 + (query.length - 12) + 6;
        ByteBuffer buffer = ByteBuffer.wrap(response);

        assertEquals(15, buffer.getInt(firstTtl));
        assertEquals(15, buffer.getInt(firstTtl + 16));
    }

    // ── spoof-aws-endpoints: transparent endpoint injection ──────────────────

    @Test
    void spoofAwsEndpoints_answersAmazonawsAQueriesWithFlociIp() {
        EmbeddedDnsServer spoofing = new EmbeddedDnsServer(List.of(), true);
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("sts.amazonaws.com", "172.19.0.2"));
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("sts.us-east-1.amazonaws.com", "172.19.0.2"));
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("organizations.us-east-1.amazonaws.com", "172.19.0.2"));
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("my-bucket.s3.us-east-1.amazonaws.com", "172.19.0.2"));
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("amazonaws.com", "172.19.0.2"));
    }

    @Test
    void spoofAwsEndpoints_coversEveryPartitionSuffix() {
        // Catches: interception limited to the commercial suffix, leaving China, EUSC and ISO endpoints on real AWS
        EmbeddedDnsServer spoofing = new EmbeddedDnsServer(List.of(), true);
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("sts.cn-north-1.amazonaws.com.cn", "172.19.0.2"));
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("sts.eusc-de-east-1.amazonaws.eu", "172.19.0.2"));
        assertEquals(List.of("172.19.0.2"), spoofing.resolveARecord("sts.us-iso-east-1.c2s.ic.gov", "172.19.0.2"));
    }

    @Test
    void spoofAwsEndpoints_offByDefault_amazonawsQueriesAreForwarded() {
        assertTrue(dns.resolveARecord("sts.amazonaws.com", "172.19.0.2").isEmpty());
        assertTrue(dns.resolveARecord("sts.us-east-1.amazonaws.com", "172.19.0.2").isEmpty());
        assertTrue(dns.resolveARecord("my-bucket.s3.us-east-1.amazonaws.com", "172.19.0.2").isEmpty());
    }

    @Test
    void spoofAwsEndpoints_doesNotCaptureOtherDomains() {
        EmbeddedDnsServer spoofing = new EmbeddedDnsServer(List.of(), true);
        assertFalse(spoofing.matchesSuffix("business-api.tiktok.com"));
        assertFalse(spoofing.matchesSuffix("myamazonaws.com"));
        assertFalse(spoofing.matchesSuffix("amazonaws.com.evil.example"));
    }

    @Test
    void spoofAwsEndpoints_flociDomainsStillResolve() {
        EmbeddedDnsServer spoofing = new EmbeddedDnsServer(List.of(), true);
        assertTrue(spoofing.matchesSuffix("my-bucket.s3.localhost.floci.io"));
        assertTrue(spoofing.matchesSuffix("my-bucket.localhost.localstack.cloud"));
    }

    // ── matchesSuffix — built-in emulator domains ─────────────────────────────

    @Test
    void matchesSuffix_localhostFlociIo_exact() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostFlociIo_subdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_s3LocalhostFlociIo() {
        assertTrue(dns.matchesSuffix("s3.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_bucketS3LocalhostFlociIo() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostLocalstackCloud_exact() {
        assertTrue(dns.matchesSuffix("localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_localhostLocalstackCloud_subdomain() {
        assertTrue(dns.matchesSuffix("my-bucket.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_s3LocalhostLocalstackCloud() {
        assertTrue(dns.matchesSuffix("s3.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_bucketS3LocalhostLocalstackCloud() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.localhost.localstack.cloud"));
    }

    @Test
    void matchesSuffix_bucketS3RegionLocalstackCloud() {
        assertTrue(dns.matchesSuffix("my-bucket.s3.us-east-1.localhost.localstack.cloud"));
    }

    // ── readName ──────────────────────────────────────────────────────────────

    @Test
    void readName_simple() {
        // my-bucket.localhost.floci.io encoded as DNS labels
        byte[] encoded = encodeName("my-bucket.localhost.floci.io");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("my-bucket.localhost.floci.io", dns.readName(buf, encoded));
    }

    @Test
    void readName_singleLabel() {
        byte[] encoded = encodeName("floci");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("floci", dns.readName(buf, encoded));
    }

    @Test
    void readName_withCompressionPointer() {
        // Build a buffer where the name at offset 12 is "floci.io" and
        // a pointer at offset 0 points to it.
        byte[] data = new byte[20];
        // pointer at offset 0 → offset 4
        data[0] = (byte) 0xC0;
        data[1] = 0x04;
        // "floci.io" at offset 4
        byte[] name = encodeName("floci.io");
        System.arraycopy(name, 0, data, 4, name.length);

        ByteBuffer buf = ByteBuffer.wrap(data);
        assertEquals("floci.io", dns.readName(buf, data));
    }

    // ── buildAResponse ────────────────────────────────────────────────────────

    @Test
    void buildAResponse_hasCorrectTransactionId() {
        byte[] query = buildQuery("my-bucket.localhost.floci.io", (short) 0x1234);
        byte[] response = dns.buildAResponse(query, (short) 0x1234, 12, query.length, answer("172.19.0.2"));
        short txId = ByteBuffer.wrap(response).getShort(0);
        assertEquals((short) 0x1234, txId);
    }

    @Test
    void buildAResponse_flagsIndicateResponse() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 1);
        byte[] response = dns.buildAResponse(query, (short) 1, 12, query.length, answer("10.0.0.1"));
        short flags = ByteBuffer.wrap(response).getShort(2);
        assertTrue((flags & 0x8000) != 0, "QR bit must be set");
    }

    @Test
    void buildAResponse_answerCountMatchesTheAddressCount() {
        byte[] query = buildQuery("api.sapphire.internal", (short) 9);
        byte[] response = dns.buildAResponse(query, (short) 9, 12, query.length,
                answer("172.31.0.6", "172.31.0.7"));
        int questionLength = query.length - 12;
        ByteBuffer resp = ByteBuffer.wrap(response);

        assertEquals(2, resp.getShort(6));
        assertEquals(12 + questionLength + 32, response.length);
        // second answer: name-ptr(2) + type(2) + class(2) + ttl(4) + rdlen(2) = 12 bytes of header
        resp.position(12 + questionLength + 16 + 12);
        assertEquals((byte) 172, resp.get());
        assertEquals((byte) 31, resp.get());
        assertEquals((byte) 0, resp.get());
        assertEquals((byte) 7, resp.get());
    }

    @Test
    void buildAResponse_answerCountIsOne() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 2);
        byte[] response = dns.buildAResponse(query, (short) 2, 12, query.length, answer("10.0.0.1"));
        short ancount = ByteBuffer.wrap(response).getShort(6);
        assertEquals(1, ancount);
    }

    @Test
    void buildAResponse_ipAddressIsCorrect() {
        byte[] query = buildQuery("bucket.localhost.floci.io", (short) 3);
        byte[] response = dns.buildAResponse(query, (short) 3, 12, query.length, answer("172.19.0.42"));
        // IP starts at offset: 12 (header) + questionLength + 2+2+2+4+2 = questionLength + 24
        int questionLength = query.length - 12;
        ByteBuffer resp = ByteBuffer.wrap(response);
        resp.position(12 + questionLength + 10); // skip header + question + name-ptr(2) + type(2) + class(2) + ttl(4)
        short rdlen = resp.getShort();
        assertEquals(4, rdlen);
        assertEquals((byte) 172, resp.get());
        assertEquals((byte) 19, resp.get());
        assertEquals((byte) 0, resp.get());
        assertEquals((byte) 42, resp.get());
    }

    // ── composeUpstreams — forwarder upstream ordering ────────────────────────

    @Test
    void composeUpstreams_resolvConfFirstThenFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("192.168.65.7"), List.of("8.8.8.8", "8.8.4.4"));
        assertEquals(List.of("192.168.65.7", "8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_usesDockerResolverBaselineWhenResolvConfEmpty() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of(), List.of("8.8.8.8"));
        // Docker's embedded resolver is the baseline, then the configured fallback.
        assertEquals(List.of("127.0.0.11", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_skipsLoopbackAndBlankEntries() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("127.0.0.1", "  ", "10.0.0.2"), List.of("", "8.8.8.8"));
        assertEquals(List.of("10.0.0.2", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_dedupesAcrossResolvConfAndFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("8.8.8.8"), List.of("8.8.8.8", "8.8.4.4"));
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_toleratesNullFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(List.of("10.0.0.2"), null);
        assertEquals(List.of("10.0.0.2"), upstreams);
    }

    // ── forwarding — response buffer size (issue #1110 regression) ────────────

    @Test
    void forwardToUpstreams_returnsResponseLargerThan512BytesIntact() throws Exception {
        // Regression guard: a 512-byte receive buffer silently truncated EDNS0 responses from
        // CDN-backed public hosts, corrupting the answer forwarded back to the Lambda container.
        byte[] bigResponse = new byte[1500];
        for (int i = 0; i < bigResponse.length; i++) {
            bigResponse[i] = (byte) (i & 0xFF);
        }

        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            startResponder(responder, bigResponse);
            byte[] query = buildQuery("business-api.tiktok.com", (short) 0x1234);

            byte[] response = dns.forwardToUpstreams(
                    query, List.of("127.0.0.1"), responder.getLocalPort());

            assertEquals(bigResponse.length, response.length,
                    "response larger than 512 bytes must be forwarded without truncation");
            assertArrayEquals(bigResponse, response);
        }
    }

    @Test
    void forwardToTargets_throwsWhenEveryTargetIsUnreachable() {
        // Nothing listens on port 1 of the loopback, so every target fails and the caller decides
        // what a failed rule means rather than silently getting an upstream answer.
        assertThrows(Exception.class, () -> EmbeddedDnsServer.forwardToTargets(
                buildQuery("db.corp.internal", (short) 8),
                List.of(new DnsForwardingRule.Target("127.0.0.1", 1))));
    }

    @Test
    void submitForward_slowForwardDoesNotBlockTheNextOneOnTheSameContext() throws Exception {
        // Catches: forwards submitted from one event-loop context run one at a time, so a single
        // unresponsive upstream stalls every other query's forwarding until it times out.
        Vertx vertx = Vertx.vertx();
        WorkerExecutor pool = vertx.createSharedWorkerExecutor("dns-forward-test", 2);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        try {
            vertx.getOrCreateContext().runOnContext(v -> {
                EmbeddedDnsServer.submitForward(pool, () -> releaseSlow.await(10, TimeUnit.SECONDS));
                EmbeddedDnsServer.submitForward(pool, () -> {
                    fastDone.countDown();
                    return null;
                });
            });
            assertTrue(fastDone.await(5, TimeUnit.SECONDS));
        } finally {
            releaseSlow.countDown();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Replies to the first datagram received with a fixed payload, on a daemon thread. */
    private void startResponder(DatagramSocket responder, byte[] responsePayload) {
        Thread t = new Thread(() -> {
            try {
                DatagramPacket req = new DatagramPacket(new byte[4096], 4096);
                responder.receive(req);
                responder.send(new DatagramPacket(
                        responsePayload, responsePayload.length, req.getAddress(), req.getPort()));
            } catch (Exception ignored) {
                // socket closed when the test completes
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int len = 1; // trailing zero
        for (String l : labels) len += 1 + l.length();
        byte[] buf = new byte[len];
        int pos = 0;
        for (String label : labels) {
            buf[pos++] = (byte) label.length();
            for (char c : label.toCharArray()) buf[pos++] = (byte) c;
        }
        buf[pos] = 0;
        return buf;
    }

    private byte[] buildQuery(String name, short txId) {
        byte[] encodedName = encodeName(name);
        // header(12) + name + type(2) + class(2)
        ByteBuffer buf = ByteBuffer.allocate(12 + encodedName.length + 4);
        buf.putShort(txId);
        buf.putShort((short) 0x0100); // standard query, RD=1
        buf.putShort((short) 1);       // qdcount
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.put(encodedName);
        buf.putShort((short) 1); // type A
        buf.putShort((short) 1); // class IN
        return buf.array();
    }
}
