package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesServiceDkimLookupCacheTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "example.com";

    private SesService service;
    private InMemoryStorage<String, Identity> identityStore;
    private Route53Service route53Service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        identityStore = new InMemoryStorage<>();
        route53Service = mock(Route53Service.class);
        clock = new MutableClock();
        clock.reset();
        service = new SesService(
                identityStore,
                new InMemoryStorage<String, SentEmail>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, EmailTemplate>(),
                new InMemoryStorage<String, ConfigurationSet>(),
                new InMemoryStorage<String, SuppressedDestination>(),
                new InMemoryStorage<String, AccountSuppressionAttributes>(),
                new InMemoryStorage<String, DedicatedIpPool>(),
                new InMemoryStorage<String, ContactList>(),
                mock(SmtpRelay.class),
                new ObjectMapper(),
                route53Service,
                clock);
    }

    @Test
    void pendingIdentity_reusesCachedNegativeResultWithinTtl() {
        Identity identity = storePendingDomainIdentity();
        stubRoute53(identity, List.of());

        assertEquals("Pending", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());
        assertEquals("Pending", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());

        verify(route53Service, times(1)).listHostedZones(null, Integer.MAX_VALUE);
        verify(route53Service, times(1)).listResourceRecordSets(eq("zone-1"), eq(null), eq(null), eq(Integer.MAX_VALUE));
    }

    @Test
    void pendingIdentity_rechecksAfterTtlExpires() {
        Identity identity = storePendingDomainIdentity();
        stubRoute53(identity, List.of());

        assertEquals("Pending", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());
        clock.advance(Duration.ofSeconds(5));
        assertEquals("Pending", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());

        verify(route53Service, times(2)).listHostedZones(null, Integer.MAX_VALUE);
        verify(route53Service, times(2)).listResourceRecordSets(eq("zone-1"), eq(null), eq(null), eq(Integer.MAX_VALUE));
    }

    @Test
    void successTransition_invalidatesCacheAfterRecordsAppear() {
        Identity identity = storePendingDomainIdentity();
        List<ResourceRecordSet> matchingRecords = buildMatchingRecords(identity);
        stubRoute53(identity, List.of());

        assertEquals("Pending", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());

        when(route53Service.listResourceRecordSets("zone-1", null, null, Integer.MAX_VALUE))
                .thenReturn(matchingRecords);

        clock.advance(Duration.ofSeconds(5));
        assertEquals("Success", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());
        assertEquals("Success", service.getIdentityVerificationAttributes(DOMAIN, REGION).getVerificationStatus());

        verify(route53Service, times(4)).listHostedZones(null, Integer.MAX_VALUE);
        verify(route53Service, times(4)).listResourceRecordSets(eq("zone-1"), eq(null), eq(null), eq(Integer.MAX_VALUE));
    }

    private Identity storePendingDomainIdentity() {
        Identity identity = new Identity(DOMAIN, "Domain");
        identity.setVerificationStatus("Pending");
        identity.setDkimEnabled(true);
        identity.setDkimVerificationStatus("Pending");
        identity.setDkimTokens(List.of("token-a", "token-b", "token-c"));
        identityStore.put("identity::" + REGION + "::" + DOMAIN, identity);
        return identity;
    }

    private void stubRoute53(Identity identity, List<ResourceRecordSet> records) {
        when(route53Service.listHostedZones(null, Integer.MAX_VALUE))
                .thenReturn(List.of(new HostedZone("zone-1", DOMAIN + ".", "ref", null, false)));
        when(route53Service.listResourceRecordSets("zone-1", null, null, Integer.MAX_VALUE))
                .thenReturn(records.isEmpty() ? List.of(nonMatchingRecordSet(identity)) : records);
    }

    private List<ResourceRecordSet> buildMatchingRecords(Identity identity) {
        List<ResourceRecordSet> records = new ArrayList<>();
        for (String token : identity.getDkimTokens()) {
            ResourceRecordSet recordSet = new ResourceRecordSet();
            recordSet.setName(token + "._domainkey." + DOMAIN + ".");
            recordSet.setType("CNAME");
            recordSet.setRecords(List.of(new ResourceRecord(token + ".dkim.amazonses.com.")));
            records.add(recordSet);
        }
        return records;
    }

    private ResourceRecordSet nonMatchingRecordSet(Identity identity) {
        ResourceRecordSet recordSet = new ResourceRecordSet();
        recordSet.setName("other._domainkey." + DOMAIN + ".");
        recordSet.setType("CNAME");
        recordSet.setRecords(List.of(new ResourceRecord(identity.getDkimTokens().get(0) + ".dkim.amazonses.com.")));
        return recordSet;
    }
}
