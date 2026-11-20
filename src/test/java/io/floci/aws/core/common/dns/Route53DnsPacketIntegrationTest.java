package io.floci.aws.core.common.dns;

import io.floci.aws.services.route53.Route53DnsRecordSource;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.floci.aws.services.route53.model.VpcAssociation;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53DnsPacketIntegrationTest {
    @Inject
    Route53Service route53;
    @Inject
    Route53DnsRecordSource source;
    @Inject
    Vertx vertx;

    private final List<String> zoneIds = new ArrayList<>();

    @Test
    void privateSrvPreservesEveryFieldAndTtlAcrossTheWire() throws Exception {
        String zone = uniqueName() + ".example";
        String id = route53.createHostedZone(zone, UUID.randomUUID().toString(), null,
                new VpcAssociation("vpc-srv", "us-east-1")).zone().getId();
        zoneIds.add(id);
        String name = "_api._tcp." + zone;
        change(id, "CREATE", record(name, "SRV", 17,
                "20 0 8080 first." + zone + ".", "10 100 65535 second." + zone));

        byte[] request = DnsTestQuery.query(name, 33, 0x1234);
        byte[] response = query(request);
        assertPrivateHeader(response, 0, 2);
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(request.length);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            assertEquals(name, EmbeddedDnsServer.readName(packet, response));
            assertEquals(33, packet.getShort());
            assertEquals(1, packet.getShort());
            assertEquals(17, packet.getInt());
            int length = Short.toUnsignedInt(packet.getShort());
            int start = packet.position();
            values.add(Short.toUnsignedInt(packet.getShort()) + " "
                    + Short.toUnsignedInt(packet.getShort()) + " "
                    + Short.toUnsignedInt(packet.getShort()) + " "
                    + EmbeddedDnsServer.readName(packet, response));
            assertEquals(start + length, packet.position());
        }
        assertEquals(List.of("10 100 65535 second." + zone, "20 0 8080 first." + zone),
                values.stream().sorted().toList());

        assertPrivateHeader(query(DnsTestQuery.query(name, 1, 0x1234)), 0, 0);
        assertPrivateHeader(query(DnsTestQuery.query("_tcp." + zone, 33, 0x1234)), 0, 0);
        assertPrivateHeader(query(DnsTestQuery.query("absent." + zone, 33, 0x1234)), 3, 0);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,1", "1,28", "2,28"})
    void privateCnamePreservesMissingTargetResponseAcrossTheWire(int depth, int type) throws Exception {
        String name = uniqueName() + ".example";
        String id = route53.createHostedZone(name, UUID.randomUUID().toString(), null,
                new VpcAssociation("vpc-cname-negative", "us-east-1")).zone().getId();
        zoneIds.add(id);
        String missing = "missing." + name;
        for (int hop = 0; hop < depth; hop++) {
            String target = hop + 1 == depth ? missing : "alias" + (hop + 1) + "." + name;
            change(id, "CREATE", record("alias" + hop + "." + name, "CNAME", 30, target + "."));
        }

        assertEquals(3, ByteBuffer.wrap(query(DnsTestQuery.query(missing, type, 0x1234))).getShort(2) & 15);
        byte[] response = query(DnsTestQuery.query("alias0." + name, type, 0x1234));
        assertEquals(3, ByteBuffer.wrap(response).getShort(2) & 15,
                "A private CNAME must preserve its target's NXDOMAIN response");
        assertEquals(0, ByteBuffer.wrap(response).getShort(6));

        ResourceRecordSet text = record(missing, "TXT", 60, "\"present\"");
        change(id, "CREATE", text);
        byte[] noData = query(DnsTestQuery.query("alias0." + name, type, 0x1234));
        assertEquals(0, ByteBuffer.wrap(noData).getShort(2) & 15);
        assertEquals(0, ByteBuffer.wrap(noData).getShort(6));
        change(id, "DELETE", text);
        assertEquals(3, ByteBuffer.wrap(query(DnsTestQuery.query("alias0." + name, type, 0x1234))).getShort(2) & 15);

        change(id, "CREATE", record(missing, "A", 600, "192.0.2.17"));
        byte[] request = DnsTestQuery.query("alias0." + name, 1, 0x1234);
        byte[] restored = query(request);
        assertEquals(0, ByteBuffer.wrap(restored).getShort(2) & 15);
        assertEquals(1, ByteBuffer.wrap(restored).getShort(6));
        assertEquals(30, ByteBuffer.wrap(restored).getInt(request.length + 6));
        assertArrayEquals(new DnsRecord.Address(1, "192.0.2.17").data(),
                Arrays.copyOfRange(restored, restored.length - 4, restored.length));
    }

    @AfterEach
    void deleteOwnedZones() {
        for (String id : zoneIds.reversed()) {
            String apex = route53.getHostedZone(id).getName();
            List<Map<String, Object>> deletes = route53.listResourceRecordSets(id, null, null, 100).stream()
                    .filter(record -> !(record.getName().equals(apex)
                            && ("NS".equals(record.getType()) || "SOA".equals(record.getType()))))
                    .map(record -> Map.<String, Object>of("action", "DELETE", "rrs", record)).toList();
            if (!deletes.isEmpty()) {
                route53.changeResourceRecordSets(id, deletes, null);
            }
            route53.deleteHostedZone(id);
        }
    }

    @Test
    void apiChangesAndDeletesReachAuthoritativeTxtDespiteAwsEndpointSpoofing() throws Exception {
        String name = uniqueName() + ".amazonaws.com";
        String id = createZone(name);
        String endpoint = "version." + name;
        for (String value : List.of("first", "second")) {
            given().contentType("application/xml").body("""
                    <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                      <ChangeBatch><Changes><Change><Action>UPSERT</Action><ResourceRecordSet>
                        <Name>%s</Name><Type>TXT</Type><TTL>17</TTL>
                        <ResourceRecords><ResourceRecord><Value>"%s"</Value></ResourceRecord></ResourceRecords>
                      </ResourceRecordSet></Change></Changes></ChangeBatch>
                    </ChangeResourceRecordSetsRequest>
                    """.formatted(endpoint, value))
                    .post("/2013-04-01/hostedzone/" + id + "/rrset").then().statusCode(200);
            byte[] request = DnsTestQuery.query(endpoint, 16, 0x1234);
            byte[] response = query(request);
            assertHeader(response, 0, true, 1, 0);
            assertEquals(17, ByteBuffer.wrap(response).getInt(request.length + 6));
            assertArrayEquals(new DnsRecord.Txt("\"" + value + "\"").data(),
                    Arrays.copyOfRange(response, request.length + 12, response.length));
        }
        change(id, "DELETE", record(endpoint, "TXT", 17, "\"second\""));
        assertNegative(query(DnsTestQuery.query(endpoint, 16, 0x1234)), name, 3, 900);
    }

    @ParameterizedTest
    @CsvSource({"A,1,192.0.2.7,192.0.2.8", "AAAA,28,2001:db8::7,2001:db8::8"})
    void sdkAddressChangesAndDeletesReachAuthoritativeDns(
            String recordType, int type, String first, String second) throws Exception {
        String name = uniqueName() + ".amazonaws.com";
        String id = createZone(name);
        String endpoint = "address." + name;
        try (Route53Client client = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
            for (String value : List.of(first, second)) {
                client.changeResourceRecordSets(request -> request.hostedZoneId(id).changeBatch(batch -> batch
                        .changes(change -> change.action("UPSERT").resourceRecordSet(record -> record
                                .name(endpoint).type(recordType).ttl(17L)
                                .resourceRecords(resource -> resource.value(value))))));
                byte[] request = DnsTestQuery.query(endpoint, type, 0x1234);
                byte[] response = query(request);
                assertHeader(response, 0, true, 1, 0);
                assertEquals(type, ByteBuffer.wrap(response).getShort(request.length + 2));
                assertEquals(17, ByteBuffer.wrap(response).getInt(request.length + 6));
                assertArrayEquals(new DnsRecord.Address(type, value).data(),
                        Arrays.copyOfRange(response, request.length + 12, response.length));
            }
            client.changeResourceRecordSets(request -> request.hostedZoneId(id).changeBatch(batch -> batch
                    .changes(change -> change.action("DELETE").resourceRecordSet(record -> record
                            .name(endpoint).type(recordType).ttl(17L)
                            .resourceRecords(resource -> resource.value(second))))));
        }
        assertNegative(query(DnsTestQuery.query(endpoint, type, 0x1234)), name, 3, 900);
    }

    @ParameterizedTest
    @CsvSource({"A,1,192.0.2.7,192.0.2.8", "AAAA,28,2001:db8::7,2001:db8::8"})
    void publicAddressSetsRetainValuesTtlAndNegativeAuthority(
            String recordType, int type, String first, String second) throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        String endpoint = "address." + name;
        change(id, "CREATE", record(endpoint, recordType, 23, first, second));
        byte[] request = DnsTestQuery.query(endpoint, type, 0x1234);
        byte[] response = query(request);
        assertHeader(response, 0, true, 2, 0);
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(request.length);
        for (String value : List.of(first, second)) {
            assertEquals(endpoint, EmbeddedDnsServer.readName(packet, response));
            assertEquals(type, packet.getShort());
            assertEquals(1, packet.getShort());
            assertEquals(23, packet.getInt());
            byte[] address = new byte[Short.toUnsignedInt(packet.getShort())];
            packet.get(address);
            assertArrayEquals(new DnsRecord.Address(type, value).data(), address);
        }
        assertEquals(0, packet.remaining());
        int otherAddressType = type == 1 ? 28 : 1;
        assertNegative(query(DnsTestQuery.query(endpoint, otherAddressType, 0x1234)), name, 0, 900);
        assertNegative(query(DnsTestQuery.query("missing." + name, type, 0x1234)), name, 3, 900);
    }

    @Test
    void delegationNamesAndSoaUseTheStoredZone() throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        List<String> expected = route53.getAssignedNameServers(id);
        byte[] request = DnsTestQuery.query(name, 2, 0x1234);
        byte[] response = query(request);
        assertHeader(response, 0, true, 4, 0);
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(request.length);
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            EmbeddedDnsServer.readName(packet, response);
            assertEquals(2, packet.getShort());
            assertEquals(1, packet.getShort());
            packet.getInt();
            packet.getShort();
            actual.add(EmbeddedDnsServer.readName(packet, response));
        }
        assertEquals(expected, actual);
        ResourceRecordSet soa = route53.listResourceRecordSets(id, null, null, 100).stream()
                .filter(record -> "SOA".equals(record.getType())).findFirst().orElseThrow();
        byte[] soaRequest = DnsTestQuery.query(name, 6, 0x1234);
        byte[] soaResponse = query(soaRequest);
        assertHeader(soaResponse, 0, true, 1, 0);
        assertArrayEquals(new DnsRecord.Soa(soa.getRecords().getFirst().getValue()).data(),
                Arrays.copyOfRange(soaResponse, soaRequest.length + 12, soaResponse.length));
    }

    @Test
    void negativeAnswersDistinguishNamesEmptyNonterminalsAndWildcardMatches() throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        change(id, "UPSERT", record(name, "SOA", 120,
                "ns.example. host.example. 1 7200 900 1209600 30"));
        change(id, "CREATE", record("address." + name, "A", 60, "192.0.2.1"));
        change(id, "CREATE", record("leaf.branch." + name, "TXT", 60, "\"leaf\""));
        assertNegative(query(DnsTestQuery.query("address." + name, 16, 0x1234)), name, 0, 30);
        assertNegative(query(DnsTestQuery.query("branch." + name, 16, 0x1234)), name, 0, 30);
        assertNegative(query(DnsTestQuery.query("absent." + name, 16, 0x1234)), name, 3, 30);
        change(id, "CREATE", record("*." + name, "TXT", 45, "\"wildcard\""));
        assertHeader(query(DnsTestQuery.query("other." + name, 16, 0x1234)), 0, true, 1, 0);
        assertNegative(query(DnsTestQuery.query("missing.branch." + name, 16, 0x1234)), name, 3, 30);
    }

    @Test
    void mostSpecificZoneWinsAndDuplicateZoneIdentityIsRefused() throws Exception {
        String parent = uniqueName() + ".example";
        String child = "child." + parent;
        String parentId = createZone(parent);
        change(parentId, "CREATE", record("marker." + child, "TXT", 60, "\"parent\""));
        createZone(child);
        assertNegative(query(DnsTestQuery.query("marker." + child, 16, 0x1234)), child, 3, 900);
        createZone(child);
        assertHeader(query(DnsTestQuery.query("marker." + child, 16, 0x1234)), 5, false, 0, 0);
    }

    @Test
    void unsupportedTypesRoutingAndDelegationDoNotBecomeAuthoritativeNoData() throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        ResourceRecordSet weighted = record("weighted." + name, "TXT", 60, "\"one\"");
        weighted.setSetIdentifier("one");
        weighted.setWeight(1L);
        change(id, "CREATE", weighted);
        change(id, "CREATE", record("alias." + name, "CNAME", 60, "target.example."));
        change(id, "CREATE", record("delegated." + name, "NS", 60, "other.example."));
        for (int type : List.of(15, 5)) {
            assertTrue(source.resolveAuthoritative(name, type).isEmpty());
        }
        String awsName = uniqueName() + ".amazonaws.com";
        createZone(awsName);
        byte[] address = query(DnsTestQuery.query(awsName, 1, 0x1234));
        assertNegative(address, awsName, 0, 900);
        for (String label : List.of("weighted", "alias", "leaf.delegated")) {
            assertHeader(query(DnsTestQuery.query(label + "." + name, 16, 0x1234)), 5, false, 0, 0);
        }
    }

    @Test
    void privateZonePrecedenceAndPublicQueryShapeRemainExplicit() throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        change(id, "CREATE", record("marker." + name, "TXT", 60, "\"public\""));
        for (int field : List.of(2, 4, -1, -2)) {
            byte[] request = DnsTestQuery.query("marker." + name, 16, 0x1234);
            ByteBuffer packet = ByteBuffer.wrap(request);
            if (field == -2) {
                packet.putShort(2, (short) 0x0140);
            } else if (field == -1) {
                packet.putShort(request.length - 2, (short) 3);
            } else {
                packet.putShort(field, (short) (field == 2 ? 0x0900 : 2));
            }
            assertHeader(query(request), 5, false, 0, 0);
        }
        String privateId = route53.createHostedZone(name, UUID.randomUUID().toString(), null,
                new VpcAssociation("vpc-dns-packet", "us-east-1")).zone().getId();
        zoneIds.add(privateId);
        change(privateId, "CREATE", record("address." + name, "A", 60, "192.0.2.9"));
        assertEquals(List.of("192.0.2.9"), source.resolve("address." + name, 1).orElseThrow().addresses());
        assertFalse(source.resolve("marker." + name, 16).orElseThrow().isPublicAuthorityAnswer());
    }

    @Test
    void malformedStoredValuesAndTtlsStayRefused() throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        for (String type : List.of("A", "AAAA", "TXT", "NS", "SOA")) {
            String owner = "bad-" + type.toLowerCase() + "." + name;
            change(id, "UPSERT", record(owner, type, 60, (String) null));
            int queryType = switch (type) {
                case "A" -> 1;
                case "AAAA" -> 28;
                case "NS" -> 2;
                case "SOA" -> 6;
                default -> 16;
            };
            assertHeader(query(DnsTestQuery.query(owner, queryType, 0x1234)), 5, false, 0, 0);
        }
        for (long ttl : List.of(-1L, 2147483648L)) {
            change(id, "UPSERT", record("ttl." + name, "TXT", ttl, "\"value\""));
            assertHeader(query(DnsTestQuery.query("ttl." + name, 16, 0x1234)), 5, false, 0, 0);
        }
        change(id, "UPSERT", record(name, "SOA", 60, (String) null));
        assertHeader(query(DnsTestQuery.query("missing." + name, 16, 0x1234)), 5, false, 0, 0);
    }

    @Test
    void txtSegmentsAndTruncationRetainCompleteWireRecords() throws Exception {
        String name = uniqueName() + ".example";
        String id = createZone(name);
        String value = "\"a b\" \"\\\"\\\\\\377\" \"\"";
        change(id, "CREATE", record("text." + name, "TXT", 90, value));
        byte[] request = DnsTestQuery.query("text." + name, 16, 0x1234);
        byte[] response = query(request);
        assertArrayEquals(new byte[]{3, 'a', ' ', 'b', 3, '"', '\\', (byte) 255, 0},
                Arrays.copyOfRange(response, request.length + 12, response.length));
        change(id, "UPSERT", record("text." + name, "TXT", 90,
                "\"" + "x".repeat(255) + "\" \"" + "y".repeat(255) + "\""));
        byte[] truncated = query(request);
        assertTrue(truncated.length <= 512);
        assertTrue((ByteBuffer.wrap(truncated).getShort(2) & 0x0200) != 0);
        assertEquals(0, ByteBuffer.wrap(truncated).getShort(6));
        ByteBuffer edns = ByteBuffer.allocate(request.length + 11).put(request);
        edns.putShort(10, (short) 1).put((byte) 0).putShort((short) 41).putShort((short) 1232)
                .putInt(0).putShort((short) 0);
        assertHeader(query(edns.array()), 0, true, 1, 0);
    }

    private String createZone(String name) {
        String id = route53.createHostedZone(name, UUID.randomUUID().toString(), null, null).zone().getId();
        zoneIds.add(id);
        return id;
    }

    private void change(String id, String action, ResourceRecordSet record) {
        route53.changeResourceRecordSets(id, List.of(Map.of("action", action, "rrs", record)), null);
    }

    private static ResourceRecordSet record(String name, String type, long ttl, String... values) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType(type);
        record.setTtl(ttl);
        record.setRecords(Arrays.stream(values).map(ResourceRecord::new).toList());
        return record;
    }

    private static String uniqueName() {
        return "dns-" + UUID.randomUUID().toString().substring(0, 8);
    }


    private static void assertPrivateHeader(byte[] response, int rcode, int answers) {
        ByteBuffer packet = ByteBuffer.wrap(response);
        assertEquals(rcode, packet.getShort(2) & 15);
        assertEquals(answers, packet.getShort(6));
        assertEquals(0, packet.getShort(8));
    }

    private static void assertHeader(byte[] response, int rcode, boolean authoritative, int answers, int authorities) {
        ByteBuffer packet = ByteBuffer.wrap(response);
        assertEquals(rcode, packet.getShort(2) & 15);
        assertEquals(authoritative, (packet.getShort(2) & 0x0400) != 0);
        assertEquals(0, packet.getShort(2) & 0x0080, "public authority does not advertise recursion");
        assertEquals(answers, packet.getShort(6));
        assertEquals(authorities, packet.getShort(8));
    }

    private static void assertNegative(byte[] response, String zone, int rcode, int ttl) {
        assertHeader(response, rcode, true, 0, 1);
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(12);
        EmbeddedDnsServer.readName(packet, response);
        packet.getInt();
        assertEquals(zone, EmbeddedDnsServer.readName(packet, response));
        assertEquals(6, packet.getShort());
        assertEquals(1, packet.getShort());
        assertEquals(ttl, packet.getInt());
        int length = Short.toUnsignedInt(packet.getShort());
        assertEquals(length, packet.remaining());
    }

    private byte[] query(byte[] request) throws Exception {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of("amazonaws.com"), List.of(source));
        DatagramSocket server = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        DatagramSocket client = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        try {
            server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.handler(packet -> dns.handleQuery(vertx, server, packet.data().getBytes(),
                    packet.sender().host(), packet.sender().port(), "127.0.0.1"));
            client.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            client.handler(packet -> received.complete(packet.data().getBytes()));
            client.send(Buffer.buffer(request), server.localAddress().port(), "127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            return received.get(5, TimeUnit.SECONDS);
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
