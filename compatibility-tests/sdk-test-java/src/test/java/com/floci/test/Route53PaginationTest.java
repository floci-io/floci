package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneResponse;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByNameResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Route53 ListHostedZonesByName Pagination")
class Route53PaginationTest {

    private static Route53Client route53;
    private static String firstZoneId;
    private static String secondZoneId;

    @BeforeAll
    static void setup() {
        route53 = TestFixtures.route53Client();

        CreateHostedZoneResponse firstZone = route53.createHostedZone(CreateHostedZoneRequest.builder()
                .name("zzz.com")
                .callerReference(TestFixtures.uniqueName("route53-pagination-1"))
                .build());
        firstZoneId = stripHostedZonePrefix(firstZone.hostedZone().id());

        CreateHostedZoneResponse secondZone = route53.createHostedZone(CreateHostedZoneRequest.builder()
                .name("aaa.net")
                .callerReference(TestFixtures.uniqueName("route53-pagination-2"))
                .build());
        secondZoneId = stripHostedZonePrefix(secondZone.hostedZone().id());
    }

    @AfterAll
    static void cleanup() {
        try {
            route53.deleteHostedZone(r -> r.id(firstZoneId));
        } catch (Exception ignored) {
        }
        try {
            route53.deleteHostedZone(r -> r.id(secondZoneId));
        } catch (Exception ignored) {
        }
        route53.close();
    }

    @Test
    void listHostedZonesByName_returnsAndResumesPaginationCursors() {
        ListHostedZonesByNameResponse firstPage = route53.listHostedZonesByName(r -> r
                .maxItems("1"));

        assertThat(firstPage.isTruncated()).isTrue();
        assertThat(firstPage.hostedZones()).hasSize(1);
        assertThat(firstPage.hostedZones().get(0).name()).isEqualTo("zzz.com.");
        assertThat(firstPage.nextDNSName()).isEqualTo("aaa.net.");
        assertThat(firstPage.nextHostedZoneId()).isEqualTo(secondZoneId);

        ListHostedZonesByNameResponse secondPage = route53.listHostedZonesByName(r -> r
                .dnsName(firstPage.nextDNSName())
                .hostedZoneId(firstPage.nextHostedZoneId())
                .maxItems("1"));

        assertThat(secondPage.isTruncated()).isFalse();
        assertThat(secondPage.hostedZones()).hasSize(1);
        assertThat(secondPage.hostedZones().get(0).name()).isEqualTo("aaa.net.");
        assertThat(secondPage.hostedZones().get(0).id()).isEqualTo("/hostedzone/" + secondZoneId);
    }

    private static String stripHostedZonePrefix(String hostedZoneId) {
        String prefix = "/hostedzone/";
        return hostedZoneId != null && hostedZoneId.startsWith(prefix)
                ? hostedZoneId.substring(prefix.length())
                : hostedZoneId;
    }
}
