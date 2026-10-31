package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.DelegationSet;
import software.amazon.awssdk.services.route53.model.ListHostedZonesResponse;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Exception;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53DelegationSetHostedZonesIntegrationTest {
    @Test
    void zonesReuseTheSetInResponsesAndApexRecordsUntilAllZonesAreDeleted() {
        try (Route53Client client = client("741686000010")) {
            DelegationSet set = createSet(client);
            String first = createZone(client, "first.example.", set.id()).hostedZone().id();
            String second = createZone(client, "second.example.", set.id()).hostedZone().id();
            String firstZone = first;
            try {
                assertEquals(set, client.getHostedZone(r -> r.id(firstZone)).delegationSet());
                assertEquals(set, client.getHostedZone(r -> r.id(second)).delegationSet());
                List<ResourceRecordSet> records = client.listResourceRecordSets(r -> r.hostedZoneId(firstZone))
                        .resourceRecordSets();
                assertEquals(set.nameServers().stream().map(ns -> ns + ".").sorted().toList(), records.stream()
                        .filter(r -> r.type() == RRType.NS).flatMap(r -> r.resourceRecords().stream())
                        .map(ResourceRecord::value).sorted().toList());
                assertTrue(records.stream().filter(r -> r.type() == RRType.SOA).findFirst().orElseThrow()
                        .resourceRecords().getFirst().value().startsWith(set.nameServers().getFirst() + ". "));
                assertError("DelegationSetInUse", () -> client.deleteReusableDelegationSet(r -> r.id(set.id())));
                client.deleteHostedZone(r -> r.id(firstZone));
                first = null;
                assertError("DelegationSetInUse", () -> client.deleteReusableDelegationSet(r -> r.id(set.id())));
            } finally {
                if (first != null) {
                    String remaining = first;
                    client.deleteHostedZone(r -> r.id(remaining));
                }
                client.deleteHostedZone(r -> r.id(second));
                client.deleteReusableDelegationSet(r -> r.id(set.id()));
            }
        }
    }

    @Test
    void createHostedZoneReturnsTheSelectedDelegationSet() {
        try (Route53Client client = client("741686000011")) {
            DelegationSet set = createSet(client);
            CreateHostedZoneResponse zone = createZone(client, "created.example.", set.id());
            try {
                assertEquals(set, zone.delegationSet());
            } finally {
                client.deleteHostedZone(r -> r.id(zone.hostedZone().id()));
                client.deleteReusableDelegationSet(r -> r.id(set.id()));
            }
        }
    }

    @Test
    void anExistingPublicZoneCanBePromotedOnlyOnceAndRemainsInUse() {
        try (Route53Client client = client("741686000012")) {
            CreateHostedZoneResponse zone = createZone(client, "promoted.example.", null);
            String reference = UUID.randomUUID().toString();
            DelegationSet set = client.createReusableDelegationSet(r ->
                    r.callerReference(reference).hostedZoneId(zone.hostedZone().id())).delegationSet();
            try {
                assertEquals(zone.delegationSet().nameServers(), set.nameServers());
                assertEquals(reference, set.callerReference());
                assertEquals(set, client.getHostedZone(r -> r.id(zone.hostedZone().id())).delegationSet());
                assertError("DelegationSetAlreadyReusable", () -> client.createReusableDelegationSet(r ->
                        r.callerReference(UUID.randomUUID().toString()).hostedZoneId(zone.hostedZone().id())));
                assertError("DelegationSetInUse", () -> client.deleteReusableDelegationSet(r -> r.id(set.id())));
            } finally {
                client.deleteHostedZone(r -> r.id(zone.hostedZone().id()));
                client.deleteReusableDelegationSet(r -> r.id(set.id()));
            }
        }
    }

    @Test
    void relatedPublicNamesCannotShareNameServersButSiblingDomainsCan() {
        try (Route53Client client = client("741686000013")) {
            DelegationSet set = createSet(client);
            String zone = createZone(client, "a.example.com.", null).hostedZone().id();
            try {
                for (String name : List.of("A.EXAMPLE.COM", "child.a.example.com.", "example.com.")) {
                    assertError("ConflictingDomainExists", () -> createZone(client, name, set.id()));
                }
                String sibling = createZone(client, "b.example.com.", set.id()).hostedZone().id();
                client.deleteHostedZone(r -> r.id(sibling));
            } finally {
                client.deleteHostedZone(r -> r.id(zone));
                client.deleteReusableDelegationSet(r -> r.id(set.id()));
            }
        }
    }

    @Test
    void privateZonesCannotUseOrPromoteAReusableSet() {
        try (Route53Client client = client("741686000014")) {
            DelegationSet set = createSet(client);
            String zone = client.createHostedZone(r -> r.name("private.example.")
                    .callerReference(UUID.randomUUID().toString())
                    .vpc(v -> v.vpcId("vpc-delegation-private").vpcRegion("us-east-1"))).hostedZone().id();
            try {
                assertError("InvalidArgument", () -> client.createReusableDelegationSet(r ->
                        r.callerReference(UUID.randomUUID().toString()).hostedZoneId(zone)));
                assertError("InvalidInput", () -> client.createHostedZone(r -> r.name("other.private.example.")
                        .callerReference(UUID.randomUUID().toString()).delegationSetId(set.id())
                        .vpc(v -> v.vpcId("vpc-delegation-private").vpcRegion("us-east-1"))));
                assertEquals(1, client.getHostedZoneCount().hostedZoneCount());
            } finally {
                client.deleteHostedZone(r -> r.id(zone));
                client.deleteReusableDelegationSet(r -> r.id(set.id()));
            }
        }
    }

    @Test
    void missingAndForeignResourcesFailWithoutCreatingZonesOrSets() {
        try (Route53Client owner = client("741686000015"); Route53Client stranger = client("741686000016")) {
            DelegationSet set = createSet(owner);
            String zone = createZone(owner, "owned.example.", null).hostedZone().id();
            try {
                assertError("NoSuchDelegationSet", () -> createZone(stranger, "stranger.example.", set.id()));
                assertError("NoSuchDelegationSet", () -> createZone(owner, "missing.example.", "NMissing"));
                assertError("HostedZoneNotFound", () -> stranger.createReusableDelegationSet(r ->
                        r.callerReference(UUID.randomUUID().toString()).hostedZoneId(zone)));
                assertError("HostedZoneNotFound", () -> owner.createReusableDelegationSet(r ->
                        r.callerReference(UUID.randomUUID().toString()).hostedZoneId("ZMissing")));
                assertEquals(0, stranger.getHostedZoneCount().hostedZoneCount());
                assertTrue(stranger.listReusableDelegationSets().delegationSets().isEmpty());
                assertEquals(1, owner.getHostedZoneCount().hostedZoneCount());
            } finally {
                owner.deleteHostedZone(r -> r.id(zone));
                owner.deleteReusableDelegationSet(r -> r.id(set.id()));
            }
        }
    }

    @Test
    void hostedZoneFilteringByDelegationSetHappensBeforePagination() {
        try (Route53Client client = client("741686000040")) {
            DelegationSet selected = createSet(client);
            DelegationSet other = createSet(client);
            String alpha = createZone(client, "alpha.example.", selected.id()).hostedZone().id();
            String beta = createZone(client, "beta.example.", selected.id()).hostedZone().id();
            String excluded = createZone(client, "excluded.example.", other.id()).hostedZone().id();
            String ordinary = createZone(client, "aardvark.example.", null).hostedZone().id();
            try {
                ListHostedZonesResponse first = client.listHostedZones(r -> r.delegationSetId(selected.id()).maxItems("1"));
                assertEquals(List.of(alpha), first.hostedZones().stream().map(zone -> zone.id()).toList());
                assertTrue(first.isTruncated());
                ListHostedZonesResponse second = client.listHostedZones(r -> r.delegationSetId(selected.id())
                        .marker(first.nextMarker()).maxItems("1"));
                assertEquals(List.of(beta), second.hostedZones().stream().map(zone -> zone.id()).toList());
                assertFalse(second.isTruncated());
                assertEquals(List.of(excluded), client.listHostedZones(r -> r.delegationSetId(other.id()))
                        .hostedZones().stream().map(zone -> zone.id()).toList());
                assertEquals(4, client.listHostedZones().hostedZones().size());
            } finally {
                for (String id : List.of(alpha, beta, excluded, ordinary)) {
                    client.deleteHostedZone(r -> r.id(id));
                }
                client.deleteReusableDelegationSet(r -> r.id(selected.id()));
                client.deleteReusableDelegationSet(r -> r.id(other.id()));
            }
        }
    }

    @Test
    void hostedZoneFilteringValidatesTheSetEvenWhenNoZonesExist() {
        try (Route53Client owner = client("741686000041"); Route53Client stranger = client("741686000042")) {
            DelegationSet empty = createSet(owner);
            try {
                assertTrue(owner.listHostedZones(r -> r.delegationSetId(empty.id())).hostedZones().isEmpty());
                assertError("NoSuchDelegationSet", () -> owner.listHostedZones(r -> r.delegationSetId("NMissing")));
                assertError("NoSuchDelegationSet", () -> stranger.listHostedZones(r -> r.delegationSetId(empty.id())));
            } finally {
                owner.deleteReusableDelegationSet(r -> r.id(empty.id()));
            }
        }
    }

    private Route53Client client(String account) {
        return AwsTestClients.create(Route53Client.builder(), account, Region.US_EAST_1);
    }

    private DelegationSet createSet(Route53Client client) {
        return client.createReusableDelegationSet(r -> r.callerReference(UUID.randomUUID().toString())).delegationSet();
    }

    private CreateHostedZoneResponse createZone(Route53Client client, String name, String delegationSetId) {
        return client.createHostedZone(r -> r.name(name).callerReference(UUID.randomUUID().toString())
                .delegationSetId(delegationSetId));
    }

    private void assertError(String code, Runnable action) {
        Route53Exception error = assertThrows(Route53Exception.class, action::run);
        assertEquals(400, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }
}
