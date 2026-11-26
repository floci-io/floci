package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.cloudmap.CloudMapDnsRecordSource;
import io.floci.aws.services.cloudmap.CloudMapService;
import io.floci.aws.services.cloudmap.model.Service;
import io.floci.aws.services.route53.Route53DnsRecordSource;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.floci.aws.services.route53.model.VpcAssociation;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PrivateHostedZoneVpcScopeIntegrationTest {

    @Inject
    Route53Service route53;

    @Inject
    Route53DnsRecordSource records;

    @Inject
    EmulatorConfig config;

    @Inject
    CloudMapService cloudMap;

    @Inject
    CloudMapDnsRecordSource cloudMapRecords;

    @TestHTTPResource
    URI endpoint;

    private final List<String> zones = new ArrayList<>();
    private final List<String> namespaces = new ArrayList<>();
    private final List<String> services = new ArrayList<>();
    private Ec2Client sdk;
    private String firstVpc;
    private String secondVpc;

    @BeforeEach
    void createVpcIdentities() {
        sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        firstVpc = sdk.createVpc(request -> request.cidrBlock("10.73.0.0/16")).vpc().vpcId();
        secondVpc = sdk.createVpc(request -> request.cidrBlock("10.74.0.0/16")).vpc().vpcId();
    }

    @AfterEach
    void deleteOwnedResources() {
        try {
            for (String service : services.reversed()) {
                cloudMap.deregisterInstance(service, "task-1", "us-east-1");
                cloudMap.deleteService(service);
            }
            for (String namespace : namespaces.reversed()) {
                cloudMap.deleteNamespace(namespace, "us-east-1");
            }
            for (String id : zones.reversed()) {
                List<Map<String, Object>> deletes = route53.listResourceRecordSets(id, null, null, 100).stream()
                        .filter(record -> !"NS".equals(record.getType()) && !"SOA".equals(record.getType()))
                        .map(record -> Map.<String, Object>of("action", "DELETE", "rrs", record)).toList();
                if (!deletes.isEmpty()) {
                    route53.changeResourceRecordSets(id, deletes, null);
                }
                route53.deleteHostedZone(id);
            }
            if (firstVpc != null) {
                sdk.deleteVpc(request -> request.vpcId(firstVpc));
            }
            if (secondVpc != null) {
                sdk.deleteVpc(request -> request.vpcId(secondVpc));
            }
        } finally {
            if (sdk != null) {
                sdk.close();
            }
        }
    }

    @Test
    void associatedVpcGetsPrivateAnswerAndOtherVpcGetsPublicAnswer() {
        String name = uniqueName();
        addAddress(zone(name, firstVpc), "api." + name, "10.73.1.7");
        addAddress(zone(name, null), "api." + name, "203.0.113.7");

        assertEquals(List.of("10.73.1.7"), answer("api." + name, "192.0.2.10").orElseThrow().addresses());
        assertEquals(List.of("203.0.113.7"), answer("api." + name, "192.0.2.20").orElseThrow().addresses());
    }

    @Test
    void unknownCallerRetainsPrivateAnswerAheadOfPublicRecords() {
        String name = uniqueName();
        addAddress(zone(name, firstVpc), "api." + name, "10.73.1.7");
        addAddress(zone(name, null), "api." + name, "203.0.113.7");

        assertEquals(List.of("10.73.1.7"), answer("api." + name, null).orElseThrow().addresses());
    }

    @Test
    void unassociatedPrivateZoneDoesNotClaimTheName() {
        String name = uniqueName();
        addAddress(zone(name, firstVpc), "api." + name, "10.73.1.7");

        assertTrue(answer("api." + name, "192.0.2.20").isEmpty(),
                "An unassociated private zone must leave the name to public resolution");
    }

    @Test
    void moreSpecificZoneInAnotherVpcDoesNotHideTheAssociatedParent() {
        String parent = uniqueName();
        String child = "child." + parent;
        addAddress(zone(parent, firstVpc), "api." + child, "10.73.1.7");
        addAddress(zone(child, secondVpc), "api." + child, "10.74.1.7");

        assertEquals(List.of("10.73.1.7"), answer("api." + child, "192.0.2.10").orElseThrow().addresses());
        assertEquals(List.of("10.74.1.7"), answer("api." + child, "192.0.2.20").orElseThrow().addresses());
    }

    @Test
    void associatedPrivateNegativeAnswerDoesNotFallBackToPublicRecords() {
        String name = uniqueName();
        zone(name, firstVpc);
        addAddress(zone(name, null), "api." + name, "203.0.113.7");

        assertEquals(3, answer("api." + name, "192.0.2.10").orElseThrow().responseCode());
    }

    @Test
    void associationAndDisassociationChangeSubsequentAnswers() {
        String name = uniqueName();
        String privateZone = zone(name, firstVpc);
        addAddress(privateZone, "api." + name, "10.73.1.7");
        addAddress(zone(name, null), "api." + name, "203.0.113.7");
        assertEquals(List.of("203.0.113.7"), answer("api." + name, "192.0.2.20").orElseThrow().addresses());

        route53.associateVpcWithHostedZone(privateZone, new VpcAssociation(secondVpc, "us-east-1"), null);
        assertEquals(List.of("10.73.1.7"), answer("api." + name, "192.0.2.20").orElseThrow().addresses());

        route53.disassociateVpcFromHostedZone(privateZone, new VpcAssociation(secondVpc, "us-east-1"), null);
        assertEquals(List.of("203.0.113.7"), answer("api." + name, "192.0.2.20").orElseThrow().addresses());
    }

    @Test
    void vpcIdWithoutMatchingAccountAndRegionDoesNotGrantVisibility() {
        String name = uniqueName();
        addAddress(zone(name, firstVpc), "api." + name, "10.73.1.7");
        for (DnsClientVpcSource.ClientVpc origin : List.of(
                new DnsClientVpcSource.ClientVpc("222222222222", "us-east-1", firstVpc),
                new DnsClientVpcSource.ClientVpc(config.defaultAccountId(), "us-west-2", firstVpc))) {
            EmbeddedDnsServer server = new EmbeddedDnsServer(List.of(), List.of(records),
                    List.of(), List.of(address -> Optional.of(origin)));
            assertTrue(server.planQuery("api." + name, "192.0.2.10", "127.0.0.1", 1).answer().isEmpty());
        }
    }

    @Test
    void cloudMapPrivateAnswersFollowBackingZoneAssociationsInEitherSourceOrder() {
        String name = uniqueName();
        String namespace = cloudMapNamespace(name, firstVpc);
        register(namespace, "api", "10.73.1.7");
        for (List<DnsRecordSource> sources : sourceOrders()) {
            assertEquals(List.of("10.73.1.7"),
                    answer("api." + name, "192.0.2.10", sources).orElseThrow().addresses());
            assertTrue(answer("api." + name, "192.0.2.20", sources).isEmpty());
            assertEquals(List.of("10.73.1.7"), answer("api." + name, null, sources).map(DnsAnswer::addresses).orElse(List.of()));
        }

        String backingZone = cloudMap.getNamespace(namespace).getHostedZoneId();
        route53.associateVpcWithHostedZone(backingZone, new VpcAssociation(secondVpc, "us-east-1"), null);
        for (List<DnsRecordSource> sources : sourceOrders()) {
            assertEquals(List.of("10.73.1.7"),
                    answer("api." + name, "192.0.2.20", sources).orElseThrow().addresses());
        }
        route53.disassociateVpcFromHostedZone(backingZone, new VpcAssociation(secondVpc, "us-east-1"), null);
        assertTrue(answer("api." + name, "192.0.2.20", List.of(cloudMapRecords)).isEmpty());
    }

    @Test
    void associatedChildZoneNegativeAnswerCannotFallThroughToParentCloudMapNamespace() {
        String parent = uniqueName();
        register(cloudMapNamespace(parent, firstVpc), "api.child", "10.73.1.7");
        zone("child." + parent, firstVpc);

        for (List<DnsRecordSource> sources : sourceOrders()) {
            assertEquals(3, answer("api.child." + parent, "192.0.2.10", sources).orElseThrow().responseCode());
        }
    }

    @Test
    void associatedPrivateZoneNegativeAnswerCannotFallThroughToPublicCloudMapNamespace() {
        String name = uniqueName();
        register(cloudMapNamespace(name, null), "api", "203.0.113.7");
        zone(name, firstVpc);

        for (List<DnsRecordSource> sources : sourceOrders()) {
            assertEquals(3, answer("api." + name, "192.0.2.10", sources).orElseThrow().responseCode());
        }
        assertEquals(List.of("203.0.113.7"),
                answer("api." + name, "192.0.2.20", List.of(cloudMapRecords)).orElseThrow().addresses());
    }

    private List<List<DnsRecordSource>> sourceOrders() {
        return List.of(List.of(records, cloudMapRecords), List.of(cloudMapRecords, records));
    }

    private String cloudMapNamespace(String name, String vpc) {
        String id = (vpc == null
                ? cloudMap.createPublicDnsNamespace(name, null, null, Map.of(), "us-east-1")
                : cloudMap.createPrivateDnsNamespace(name, vpc, null, null, Map.of(), "us-east-1"))
                .getTargets().get("NAMESPACE");
        namespaces.add(id);
        return id;
    }

    private void register(String namespace, String name, String address) {
        Service service = cloudMap.createService(name, namespace, null, null,
                "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}", null, null, null, Map.of(), "us-east-1");
        services.add(service.getId());
        cloudMap.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV4", address), "us-east-1");
    }

    private Optional<DnsAnswer> answer(String name, String sourceAddress) {
        return answer(name, sourceAddress, List.of(records));
    }

    private Optional<DnsAnswer> answer(String name, String sourceAddress, List<DnsRecordSource> sources) {
        DnsClientVpcSource clients = address -> switch (address) {
            case "192.0.2.10" -> Optional.of(new DnsClientVpcSource.ClientVpc(
                    config.defaultAccountId(), "us-east-1", firstVpc));
            case "192.0.2.20" -> Optional.of(new DnsClientVpcSource.ClientVpc(
                    config.defaultAccountId(), "us-east-1", secondVpc));
            default -> Optional.empty();
        };
        EmbeddedDnsServer server = new EmbeddedDnsServer(
                List.of(), sources, List.of(), List.of(clients));
        return server.planQuery(name, sourceAddress, "127.0.0.1", 1).answer();
    }

    private String zone(String name, String vpc) {
        VpcAssociation association = vpc == null ? null : new VpcAssociation(vpc, "us-east-1");
        String id = route53.createHostedZone(name, UUID.randomUUID().toString(), null, association).zone().getId();
        zones.add(id);
        return id;
    }

    private void addAddress(String zone, String name, String address) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType("A");
        record.setTtl(60L);
        record.setRecords(List.of(new ResourceRecord(address)));
        route53.changeResourceRecordSets(zone, List.of(Map.of("action", "CREATE", "rrs", record)), null);
    }

    private static String uniqueName() {
        return "vpc-scope-" + UUID.randomUUID() + ".example";
    }
}
