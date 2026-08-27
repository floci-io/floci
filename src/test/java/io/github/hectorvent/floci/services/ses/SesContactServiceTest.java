package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted contact domain. New facet: a multi-store service
 * (contact lists + contacts) constructed with just its two stores and a clock — the two SesService
 * constructor arguments collapse into one. Also exercises the list-management behaviour the send
 * path calls into.
 */
class SesContactServiceTest {

    private static final String REGION = "us-east-1";
    private static final String LIST = "news";
    private SesContactService service;

    @BeforeEach
    void setUp() {
        service = new SesContactService(new InMemoryStorage<>(), new InMemoryStorage<>(), Clock.systemUTC());
        service.createContactList(LIST, "desc",
                List.of(new Topic("Sports", "Sports", "OPT_OUT", "d")), List.of(), REGION);
    }

    @Test
    void secondContactList_hitsOneListLimit() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createContactList("other", "d", List.of(), List.of(), REGION));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void createContact_thenGet_roundTrips() {
        service.createContact(LIST, "a@example.com", List.of(), false, null, REGION);
        assertEquals("a@example.com", service.getContact(LIST, "a@example.com", REGION).contact().getEmailAddress());
    }

    @Test
    void deleteContactList_purgesContacts() {
        service.createContact(LIST, "a@example.com", List.of(), false, null, REGION);
        service.deleteContactList(LIST, REGION);
        // The list — and its contacts — are gone.
        assertThrows(AwsException.class, () -> service.listContacts(LIST, REGION));
    }

    @Test
    void getOrAutoCreateContact_createsOnSend() {
        ContactList list = service.getContactList(LIST, REGION);
        Contact created = service.getOrAutoCreateContact(list, "auto@example.com", REGION);
        assertEquals("auto@example.com", created.getEmailAddress());
        assertTrue(service.getContact(LIST, "auto@example.com", REGION).contact() != null);
    }

    @Test
    void unsubscribeAll_thenListManagementOptedOut() {
        ContactList list = service.getContactList(LIST, REGION);
        service.unsubscribeContact(LIST, "bye@example.com", null, REGION);
        Contact c = service.getContact(LIST, "bye@example.com", REGION).contact();
        assertTrue(service.isListManagementOptedOut(c, list, null));
    }
}
