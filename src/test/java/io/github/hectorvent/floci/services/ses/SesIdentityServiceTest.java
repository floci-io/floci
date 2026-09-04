package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted identity domain: store ownership, key validation, verification
 * (email and domain), listing, MAIL FROM, the CVET pending registration, identity tags, and the
 * find/save escape hatches the facade's notification flows rely on. The DKIM state machine moved
 * here too and keeps its behavioral coverage in the existing facade-level unit and integration
 * tests, which now exercise it through the delegation; only its creation-time surface is pinned
 * here.
 */
class SesIdentityServiceTest {

    private static final String REGION = "us-east-1";
    private SesIdentityService service;

    @BeforeEach
    void setUp() {
        service = new SesIdentityService(new InMemoryStorage<>(), null, Clock.systemUTC());
    }

    @Test
    void verifyEmailIdentity_createsOnce_returnsExistingOnRepeat() {
        Identity first = service.verifyEmailIdentity("alice@example.com", REGION);
        assertEquals("EmailAddress", first.getIdentityType());
        first.setVerificationStatus("Success");
        service.save(first, REGION);
        Identity second = service.verifyEmailIdentity("alice@example.com", REGION);
        assertEquals("Success", second.getVerificationStatus());
    }

    @Test
    void verifyEmailIdentity_rejectsBlankAndWhitespace() {
        AwsException blank = assertThrows(AwsException.class,
                () -> service.verifyEmailIdentity(" ", REGION));
        assertEquals("Email address is required.", blank.getMessage());
        AwsException padded = assertThrows(AwsException.class,
                () -> service.verifyEmailIdentity(" alice@example.com", REGION));
        assertEquals("Email address must not contain leading or trailing whitespace.",
                padded.getMessage());
    }

    @Test
    void verifyDomainIdentity_generatesDkimTokens_reportsNotStarted() {
        Identity domain = service.verifyDomainIdentity("example.com", REGION);
        assertEquals("Domain", domain.getIdentityType());
        assertEquals("Pending", domain.getVerificationStatus());
        assertEquals("NotStarted", domain.getDkimVerificationStatus());
        assertEquals(3, domain.getDkimTokens().size());
        // Tokens are stable across repeated calls.
        assertEquals(domain.getDkimTokens(),
                service.verifyDomainIdentity("example.com", REGION).getDkimTokens());
        assertEquals(domain.getDkimTokens(), service.verifyDomainDkim("example.com", REGION));
    }

    @Test
    void listIdentities_filtersByType_andRegion() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.save(new Identity("example.com", "Domain"), REGION);
        service.verifyEmailIdentity("other@example.com", "eu-west-1");
        assertEquals(2, service.listIdentities(null, REGION).size());
        assertEquals(List.of("example.com"),
                service.listIdentities("Domain", REGION).stream().map(Identity::getIdentity).toList());
    }

    @Test
    void getVerifiedEmailAddresses_onlySuccessfulEmails() {
        Identity verified = service.verifyEmailIdentity("alice@example.com", REGION);
        verified.setVerificationStatus("Success");
        service.save(verified, REGION);
        service.markPendingEmailIdentity("pending@example.com", REGION);
        Identity domain = new Identity("example.com", "Domain");
        domain.setVerificationStatus("Success");
        service.save(domain, REGION);
        assertEquals(List.of("alice@example.com"), service.getVerifiedEmailAddresses(REGION));
    }

    @Test
    void delete_removesTheRecord() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.delete("alice@example.com", REGION);
        assertTrue(service.find("alice@example.com", REGION).isEmpty());
    }

    @Test
    void setMailFromDomain_setClearAndValidate() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.setMailFromDomain("alice@example.com", "mail.example.com", "RejectMessage", REGION);
        Identity attrs = service.getMailFromAttributes("alice@example.com", REGION);
        assertEquals("mail.example.com", attrs.getMailFromDomain());
        assertEquals("Success", attrs.getMailFromDomainStatus());
        assertEquals("RejectMessage", attrs.getBehaviorOnMxFailure());

        service.setMailFromDomain("alice@example.com", "", null, REGION);
        attrs = service.getMailFromAttributes("alice@example.com", REGION);
        assertEquals(null, attrs.getMailFromDomain());
        assertEquals("UseDefaultValue", attrs.getBehaviorOnMxFailure());

        assertThrows(AwsException.class, () -> service.setMailFromDomain(
                "alice@example.com", "mail.example.com", "BadEnum", REGION));
        AwsException missing = assertThrows(AwsException.class, () -> service.setMailFromDomain(
                "ghost@example.com", "mail.example.com", null, REGION));
        assertEquals("Identity <ghost@example.com> does not exist.", missing.getMessage());
    }

    @Test
    void markPendingEmailIdentity_registersOnce_neverDowngrades() {
        Identity verified = service.verifyEmailIdentity("alice@example.com", REGION);
        verified.setVerificationStatus("Success");
        service.save(verified, REGION);
        service.markPendingEmailIdentity("alice@example.com", REGION);
        assertEquals("Success",
                service.find("alice@example.com", REGION).orElseThrow().getVerificationStatus());

        service.markPendingEmailIdentity("new@example.com", REGION);
        assertEquals("Pending",
                service.find("new@example.com", REGION).orElseThrow().getVerificationStatus());
    }

    @Test
    void tags_lifecycle_andNotFoundMessage() {
        service.verifyEmailIdentity("alice@example.com", REGION);
        service.tag("alice@example.com", REGION, List.of(new Tag("team", "floci")));
        assertEquals(1, service.listTags("alice@example.com", REGION).size());

        service.setTags("alice@example.com", REGION,
                List.of(new Tag("team", "floci"), new Tag("env", "dev")));
        service.untag("alice@example.com", REGION, List.of("team"));
        assertEquals(List.of("env"),
                service.listTags("alice@example.com", REGION).stream().map(Tag::key).toList());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listTags("ghost@example.com", REGION));
        assertEquals("No EmailIdentity present with name: ghost@example.com", e.getMessage());
    }
}
