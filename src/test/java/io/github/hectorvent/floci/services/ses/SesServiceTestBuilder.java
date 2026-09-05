package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountDetails;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.TenantResourceAssociation;
import io.github.hectorvent.floci.services.route53.Route53Service;

import java.security.SecureRandom;
import java.time.Clock;

import static org.mockito.Mockito.mock;

/**
 * Builds a {@link SesService} for unit tests without the fourteen-argument constructor. Every domain
 * is backed by an in-memory store by default; tests override only the collaborators they care about
 * ({@link #smtpRelay}, {@link #clock}, {@link #route53Service}) and reach the underlying stores they
 * need to seed or inspect through the getters. Adding a store to a domain service only touches this
 * builder rather than every facade test.
 */
final class SesServiceTestBuilder {

    private final InMemoryStorage<String, Identity> identityStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, SentEmail> emailStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, Boolean> accountSettingsStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, AccountVdmAttributes> accountVdmStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, AccountDetails> accountDetailsStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, EmailTemplate> templateStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, ConfigurationSet> configSetStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, SuppressedDestination> suppressionStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, AccountSuppressionAttributes> accountSuppressionStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, DedicatedIpPool> dedicatedIpPoolStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, ContactList> contactListStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, Contact> contactStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, String> policyStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, ReceiptRuleSet> receiptRuleStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, CustomVerificationEmailTemplate> cvetStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, Tenant> tenantStore = new InMemoryStorage<>();
    private final InMemoryStorage<String, TenantResourceAssociation> tenantAssociationStore =
            new InMemoryStorage<>();

    private SmtpRelay smtpRelay = mock(SmtpRelay.class);
    // Default null, matching the production null-Route53 case: SesIdentityService treats a null
    // Route53Service as "DNS lookup disabled" for DKIM record checks. Tests that exercise the lookup
    // opt in via route53Service(...).
    private Route53Service route53Service = null;
    private ObjectMapper objectMapper = new ObjectMapper();
    private Clock clock = Clock.systemUTC();

    static SesServiceTestBuilder create() {
        return new SesServiceTestBuilder();
    }

    SesServiceTestBuilder smtpRelay(SmtpRelay smtpRelay) {
        this.smtpRelay = smtpRelay;
        return this;
    }

    SesServiceTestBuilder route53Service(Route53Service route53Service) {
        this.route53Service = route53Service;
        return this;
    }

    SesServiceTestBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    // Shared by the contact, receipt-rule, and facade timestamps — pass a MutableClock to drive
    // time-dependent behavior (e.g. the DKIM lookup cache TTL).
    SesServiceTestBuilder clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    InMemoryStorage<String, Identity> identityStore() {
        return identityStore;
    }

    InMemoryStorage<String, SentEmail> emailStore() {
        return emailStore;
    }

    InMemoryStorage<String, ConfigurationSet> configSetStore() {
        return configSetStore;
    }

    InMemoryStorage<String, SuppressedDestination> suppressionStore() {
        return suppressionStore;
    }

    InMemoryStorage<String, AccountSuppressionAttributes> accountSuppressionStore() {
        return accountSuppressionStore;
    }

    InMemoryStorage<String, Contact> contactStore() {
        return contactStore;
    }

    InMemoryStorage<String, ContactList> contactListStore() {
        return contactListStore;
    }

    SesService build() {
        return new SesService(
                new SesIdentityService(identityStore, route53Service, clock),
                new SesSentEmailService(emailStore),
                new SesAccountService(accountSettingsStore, accountVdmStore, accountDetailsStore),
                new SesTemplateService(templateStore, objectMapper, new SecureRandom()),
                new SesConfigurationSetService(configSetStore),
                new SesSuppressionService(suppressionStore, accountSuppressionStore, new InMemoryStorage<>()),
                new SesDedicatedIpService(dedicatedIpPoolStore),
                new SesContactService(contactListStore, contactStore, clock),
                new SesPolicyService(policyStore, objectMapper),
                new SesReceiptRuleService(receiptRuleStore, clock),
                new SesCvetService(cvetStore),
                new SesTenantService(tenantStore, tenantAssociationStore, clock, new SecureRandom()),
                smtpRelay);
    }
}
