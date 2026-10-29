package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How SES reads Route53 in place of public DNS: zone by zone, and past a zone deleted mid-scan. */
class SesIdentityRoute53ScanTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "example.com";

    private Route53Service route53Service;
    private SesIdentityService identities;

    @BeforeEach
    void setUp() {
        route53Service = mock(Route53Service.class);
        identities = new SesIdentityService(new InMemoryStorage<>(), route53Service, new MutableClock());
        when(route53Service.listHostedZones(null, Integer.MAX_VALUE))
                .thenReturn(List.of(zone("zone-1"), zone("zone-2")));
    }

    @Test
    void dkimLookupStopsAtTheZoneHoldingTheRecord() {
        Identity identity = new Identity(DOMAIN, "Domain");
        identity.setVerificationStatus("Pending");
        identity.setDkimTokens(List.of("token-a"));
        identities.save(identity, REGION);
        when(route53Service.listResourceRecordSets("zone-1", null, null, Integer.MAX_VALUE))
                .thenReturn(List.of(recordSet("token-a._domainkey." + DOMAIN + ".", "CNAME",
                        "token-a.dkim.amazonses.com.")));

        assertEquals("Success", identities.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());
        verify(route53Service, never()).listResourceRecordSets("zone-2", null, null, Integer.MAX_VALUE);
    }

    @Test
    void txtRecordNamesSkipAZoneDeletedMidScan() {
        when(route53Service.listResourceRecordSets("zone-1", null, null, Integer.MAX_VALUE))
                .thenThrow(new AwsException("NoSuchHostedZone", "No hosted zone found with ID: zone-1", 404));
        when(route53Service.listResourceRecordSets("zone-2", null, null, Integer.MAX_VALUE))
                .thenReturn(List.of(recordSet("_dmarc." + DOMAIN + ".", "TXT", "\"v=DMARC1; p=none\"")));

        assertEquals(Set.of("_dmarc." + DOMAIN), identities.txtRecordNames());
    }

    @Test
    void txtRecordNamesPropagateOtherRoute53Errors() {
        when(route53Service.listResourceRecordSets("zone-1", null, null, Integer.MAX_VALUE))
                .thenThrow(new AwsException("Throttling", "Rate exceeded", 400));

        AwsException e = assertThrows(AwsException.class, () -> identities.txtRecordNames());
        assertEquals("Throttling", e.getErrorCode());
    }

    @Test
    void recommendationsReadNoZonesWithoutAVerifiedDomain() {
        identities.save(new Identity("alice@example.com", "EmailAddress"), REGION);
        SesAccountService accounts =
                new SesAccountService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        accounts.putAccountVdmAttributes(REGION, new AccountVdmAttributes(true, false, false));
        SesRecommendationService recommendations = new SesRecommendationService(identities, accounts,
                new RegionResolver(REGION, "000000000000"), new MutableClock());

        assertEquals(List.of(), recommendations.list(Map.of(), null, null, REGION).items());
        verify(route53Service, never()).listHostedZones(null, Integer.MAX_VALUE);
    }

    private static HostedZone zone(String id) {
        return new HostedZone(id, DOMAIN + ".", "ref-" + id, null, false);
    }

    private static ResourceRecordSet recordSet(String name, String type, String value) {
        ResourceRecordSet recordSet = new ResourceRecordSet();
        recordSet.setName(name);
        recordSet.setType(type);
        recordSet.setRecords(List.of(new ResourceRecord(value)));
        return recordSet;
    }
}
