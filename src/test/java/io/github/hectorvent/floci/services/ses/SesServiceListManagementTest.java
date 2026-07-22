package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.ListManagementOptions;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for SES V2 {@code SendEmail} {@code ListManagementOptions} suppression: opted-out
 * contacts on the named list are dropped from the SMTP relay (surfaced here via the relay mock),
 * absent recipients are auto-created, and an unknown contact list fails the send. Uses the relay
 * mock to observe exactly which recipients survive suppression.
 */
@ExtendWith(MockitoExtension.class)
class SesServiceListManagementTest {

    private static final String REGION = "us-east-1";
    private static final String LIST = "newsletter";
    private static final String FROM = "from@example.com";

    @Mock SmtpRelay smtpRelay;

    private SesService service;
    private InMemoryStorage<String, Contact> contactStore;

    @BeforeEach
    void setUp() {
        contactStore = new InMemoryStorage<>();
        service = new SesService(
                new InMemoryStorage<String, Identity>(),
                new InMemoryStorage<String, SentEmail>(),
                new InMemoryStorage<String, Boolean>(),
                new InMemoryStorage<String, EmailTemplate>(),
                new InMemoryStorage<String, ConfigurationSet>(),
                new InMemoryStorage<String, SuppressedDestination>(),
                new InMemoryStorage<String, AccountSuppressionAttributes>(),
                new InMemoryStorage<String, DedicatedIpPool>(),
                new InMemoryStorage<String, ContactList>(),
                contactStore,
                smtpRelay,
                new ObjectMapper(),
                Clock.systemUTC());

        // Sports defaults OPT_IN, Promos defaults OPT_OUT.
        service.createContactList(LIST, "desc", List.of(
                new Topic("Sports", "Sports", "OPT_IN", "d"),
                new Topic("Promos", "Promos", "OPT_OUT", "d")), List.of(), REGION);
        service.createContact(LIST, "unsub@example.com", List.of(), true, null, REGION);
        service.createContact(LIST, "sportsout@example.com",
                List.of(new TopicPreference("Sports", "OPT_OUT")), false, null, REGION);
        service.createContact(LIST, "sportsin@example.com",
                List.of(new TopicPreference("Sports", "OPT_IN")), false, null, REGION);
        service.createContact(LIST, "noprefs@example.com", List.of(), false, null, REGION);
    }

    private void send(List<String> to, String topicName) {
        service.sendEmail(FROM, to, null, null, null, "Subject", "body", null,
                null, List.of(), List.of(), new ListManagementOptions(LIST, topicName), REGION);
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedRelayTo() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(smtpRelay).relay(any(), captor.capture(), any(), any(), any(), any(), any(), any(), any());
        return captor.getValue();
    }

    @Test
    void unsubscribeAll_isSuppressed_evenWithNoTopic() {
        send(List.of("unsub@example.com", "sportsin@example.com"), null);
        assertEquals(List.of("sportsin@example.com"), capturedRelayTo());
    }

    @Test
    void noTopic_onlyUnsubscribeAllSuppressed_topicOptOutStillSent() {
        // Without a topic, a contact opted out of a single topic (but not UnsubscribeAll) still gets it.
        send(List.of("unsub@example.com", "sportsout@example.com"), null);
        assertEquals(List.of("sportsout@example.com"), capturedRelayTo());
    }

    @Test
    void topicExplicitOptOut_isSuppressed() {
        send(List.of("sportsout@example.com", "sportsin@example.com"), "Sports");
        assertEquals(List.of("sportsin@example.com"), capturedRelayTo());
    }

    @Test
    void topicDefaultOptOut_suppressesContactWithNoExplicitPreference() {
        // Promos defaults OPT_OUT and noprefs has no explicit Promos preference -> suppressed.
        send(List.of("noprefs@example.com"), "Promos");
        verify(smtpRelay, never()).relay(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void topicDefaultOptIn_sendsContactWithNoExplicitPreference() {
        send(List.of("noprefs@example.com"), "Sports");
        assertEquals(List.of("noprefs@example.com"), capturedRelayTo());
    }

    @Test
    void recipientNotAContact_isAutoCreatedAndSent() {
        send(List.of("brandnew@example.com"), null);
        assertEquals(List.of("brandnew@example.com"), capturedRelayTo());
        assertTrue(contactStore.get("contact::" + REGION + "::" + LIST + "::brandnew@example.com").isPresent(),
                "an absent recipient must be auto-created as a contact on the list");
    }

    @Test
    void nonExistentContactList_failsTheSend() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.sendEmail(FROM, List.of("sportsin@example.com"), null, null, null,
                        "Subject", "body", null, null, List.of(), List.of(),
                        new ListManagementOptions("ghost-list", null), REGION));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void unknownTopic_failsTheSend() {
        // A TopicName not defined on the list is rejected rather than silently skipping suppression.
        AwsException ex = assertThrows(AwsException.class, () ->
                service.sendEmail(FROM, List.of("sportsin@example.com"), null, null, null,
                        "Subject", "body", null, null, List.of(), List.of(),
                        new ListManagementOptions(LIST, "GhostTopic"), REGION));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void noListManagementOptions_leavesRecipientsUntouched() {
        // Without ListManagementOptions the contact list is never consulted: an unsubscribed contact
        // is not suppressed and no contact is auto-created.
        service.sendEmail(FROM, List.of("unsub@example.com"), null, null, null,
                "Subject", "body", null, null, List.of(), List.of(), null, REGION);
        assertEquals(List.of("unsub@example.com"), capturedRelayTo());
    }

    @Test
    void singleRecipient_replacesUnsubscribePlaceholder() {
        // newbie is not a contact (auto-created, Sports defaults OPT_IN) so the send reaches the relay.
        service.sendEmail(FROM, List.of("newbie@example.com"), null, null, null,
                "Subject", "text", "<p>Unsub: {{amazonSESUnsubscribeUrl}}</p>",
                null, List.of(), List.of(), new ListManagementOptions(LIST, "Sports"), REGION);
        String html = capturedRelayBodyHtml();
        assertTrue(html.contains("http://localhost:4566/_aws/ses/unsubscribe?"), html);
        assertTrue(html.contains("contactList=" + LIST), html);
        assertTrue(html.contains("topic=Sports"), html);
        assertFalse(html.contains("{{amazonSESUnsubscribeUrl}}"), "placeholder must be replaced");
    }

    @Test
    void multiRecipient_doesNotReplacePlaceholder() {
        service.sendEmail(FROM, List.of("newbie@example.com", "sportsin@example.com"), null, null, null,
                "Subject", "text", "<p>Unsub: {{amazonSESUnsubscribeUrl}}</p>",
                null, List.of(), List.of(), new ListManagementOptions(LIST, "Sports"), REGION);
        assertTrue(capturedRelayBodyHtml().contains("{{amazonSESUnsubscribeUrl}}"),
                "multi-recipient send must not inject the unsubscribe link");
    }

    @Test
    void unsubscribeContact_withTopic_setsOptOut() {
        service.unsubscribeContact(LIST, "sportsin@example.com", "Sports", REGION);
        Contact c = contact("sportsin@example.com");
        assertTrue(c.getTopicPreferences().stream()
                .anyMatch(p -> "Sports".equals(p.getTopicName()) && "OPT_OUT".equals(p.getSubscriptionStatus())));
    }

    @Test
    void unsubscribeContact_withoutTopic_setsUnsubscribeAll() {
        service.unsubscribeContact(LIST, "sportsin@example.com", null, REGION);
        assertTrue(contact("sportsin@example.com").isUnsubscribeAll());
    }

    @Test
    void unsubscribeContact_absentContact_autoCreatesAndOptsOut() {
        service.unsubscribeContact(LIST, "ghost@example.com", null, REGION);
        assertTrue(contact("ghost@example.com").isUnsubscribeAll());
    }

    @Test
    void unsubscribeContact_withImmutablePreferences_addsTopicWithoutThrowing() {
        Contact c = new Contact("immutable@example.com");
        c.setTopicPreferences(List.of(new TopicPreference("Sports", "OPT_IN")));
        contactStore.put("contact::" + REGION + "::" + LIST + "::immutable@example.com", c);

        service.unsubscribeContact(LIST, "immutable@example.com", "Promos", REGION);

        assertTrue(contact("immutable@example.com").getTopicPreferences().stream()
                .anyMatch(p -> "Promos".equals(p.getTopicName()) && "OPT_OUT".equals(p.getSubscriptionStatus())));
    }

    private Contact contact(String email) {
        return contactStore.get("contact::" + REGION + "::" + LIST + "::" + email).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private String capturedRelayBodyHtml() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(smtpRelay).relay(any(), any(), any(), any(), any(), any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<MessageHeader> capturedRelayHeaders() {
        ArgumentCaptor<List<MessageHeader>> captor = ArgumentCaptor.forClass(List.class);
        verify(smtpRelay).relay(any(), any(), any(), any(), any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void singleRecipient_addsListUnsubscribeHeadersToRelay() {
        service.sendEmail(FROM, List.of("newbie@example.com"), null, null, null,
                "Subject", "text", "<p>x</p>", null, List.of(), List.of(),
                new ListManagementOptions(LIST, "Sports"), REGION);
        List<MessageHeader> headers = capturedRelayHeaders();
        assertTrue(headers.stream().anyMatch(h -> "List-Unsubscribe".equals(h.name())
                && h.value().contains("/_aws/ses/unsubscribe")), "List-Unsubscribe header must reach the relay");
        assertTrue(headers.stream().anyMatch(h -> "List-Unsubscribe-Post".equals(h.name())
                && "List-Unsubscribe=One-Click".equals(h.value())));
    }

    @Test
    void callerSuppliedUnsubscribeHeader_isOverriddenNotDuplicated() {
        service.sendEmail(FROM, List.of("newbie@example.com"), null, null, null,
                "Subject", "text", "<p>x</p>", null, List.of(),
                List.of(new MessageHeader("List-Unsubscribe", "<https://caller.example/u>")),
                new ListManagementOptions(LIST, "Sports"), REGION);
        long count = capturedRelayHeaders().stream()
                .filter(h -> "List-Unsubscribe".equalsIgnoreCase(h.name())).count();
        assertEquals(1L, count);
    }

    @Test
    void applyTemplateData_leavesUnsubscribePlaceholderIntact() {
        String rendered = SesService.applyTemplateData("<p>{{amazonSESUnsubscribeUrl}} {{name}}</p>",
                new ObjectMapper().createObjectNode().put("name", "Bob"));
        assertEquals("<p>{{amazonSESUnsubscribeUrl}} Bob</p>", rendered);
    }
}
