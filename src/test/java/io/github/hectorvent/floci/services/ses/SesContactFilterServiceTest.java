package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.SesContactService.ContactFilter;
import io.github.hectorvent.floci.services.ses.SesContactService.TopicFilter;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.services.ses.model.TopicPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ListContacts {@code Filter}. The fixture is the one the filter was probed with on real SES v2:
 * topic {@code tin} defaults to OPT_IN and {@code tout} to OPT_OUT, and every expected list below is
 * what SES returned for the same contacts and filter.
 */
class SesContactFilterServiceTest {

    private static final String REGION = "us-east-1";
    private static final String LIST = "filter-list";
    private static final String IN = "OPT_IN";
    private static final String OUT = "OPT_OUT";

    private SesContactService service;

    @BeforeEach
    void setUp() {
        service = new SesContactService(new InMemoryStorage<>(), new InMemoryStorage<>(), Clock.systemUTC());
        service.createContactList(LIST, "d", List.of(new Topic("tin", "In", IN, "d"),
                new Topic("tout", "Out", OUT, "d")), List.of(), REGION);
        contact("c1", false, new TopicPreference("tin", OUT), new TopicPreference("tout", IN));
        contact("c2", false, new TopicPreference("tin", IN), new TopicPreference("tout", OUT));
        contact("c3", false);
        contact("c4", true);
        contact("c5", true, new TopicPreference("tin", IN), new TopicPreference("tout", IN));
        contact("c6", false, new TopicPreference("tin", OUT));
        contact("c7", false, new TopicPreference("tout", IN));
    }

    @Test
    void filteredStatusAlone_matchesUnsubscribeAllOnly() {
        assertEquals(List.of("c1", "c2", "c3", "c6", "c7"), names(new ContactFilter(IN, null)));
        assertEquals(List.of("c4", "c5"), names(new ContactFilter(OUT, null)));
    }

    @Test
    void topicFilter_usesTheTopicDefaultOnlyWhenAskedTo() {
        assertEquals(List.of("c2"), names(topic(IN, "tin", false)));
        assertEquals(List.of("c2", "c3", "c7"), names(topic(IN, "tin", true)));
        assertEquals(List.of("c1", "c4", "c5", "c6"), names(topic(OUT, "tin", false)));
        assertEquals(List.of("c1", "c4", "c5", "c6"), names(topic(OUT, "tin", true)));
        assertEquals(List.of("c1", "c7"), names(topic(IN, "tout", false)));
        assertEquals(List.of("c1", "c7"), names(topic(IN, "tout", true)));
        assertEquals(List.of("c2", "c4", "c5"), names(topic(OUT, "tout", false)));
        assertEquals(List.of("c2", "c3", "c4", "c5", "c6"), names(topic(OUT, "tout", true)));
    }

    @Test
    void filteredPages_areExactAndTheTokenIsNotBoundToTheFilter() {
        PaginatedResult<Contact> first = page(new ContactFilter(IN, null), 2, null);
        assertEquals(List.of("c1", "c2"), names(first.items()));
        PaginatedResult<Contact> second = page(new ContactFilter(IN, null), 2, first.nextToken());
        assertEquals(List.of("c3", "c6"), names(second.items()));
        // The token is a position, so another filter (or none) continues from the same place.
        assertEquals(List.of("c3", "c4"), names(page(null, 2, first.nextToken()).items()));
        assertEquals(List.of("c4", "c5"), names(page(new ContactFilter(OUT, null), 2, first.nextToken()).items()));
    }

    @Test
    void lastFilteredPage_hasNoToken() {
        PaginatedResult<Contact> only = page(new ContactFilter(OUT, null), 2, null);
        assertEquals(List.of("c4", "c5"), names(only.items()));
        assertNull(only.nextToken());
    }

    @Test
    void invalidStatus_isAValidationError() {
        assertError("BadRequestException", "1 validation error detected: Value at 'filter.filteredStatus' "
                + "failed to satisfy constraint: Member must satisfy enum value set: [OPT_OUT, OPT_IN]",
                () -> service.listContacts(LIST, REGION, SesListPaging.V2_LIST_CONTACTS, null, null,
                        new ContactFilter("opt_in", null)));
    }

    @Test
    void missingStatusOrBlankTopic_isRejected() {
        assertError("BadRequestException", "Invalid FilteredStatus <null>",
                () -> list(LIST, null, null, new ContactFilter(null, new TopicFilter("tin", false))));
        assertError("BadRequestException", "TopicName can't be blank in TopicFilter.",
                () -> list(LIST, null, null, topic(IN, "", false)));
        assertError("BadRequestException", "TopicName can't be blank in TopicFilter.",
                () -> list(LIST, null, null, topic(IN, null, false)));
    }

    @Test
    void unknownTopic_isNotFoundAfterTheList() {
        assertError("NotFoundException", "List: filter-list doesn't contain Topic: nope",
                () -> list(LIST, null, null, topic(IN, "nope", true)));
        assertError("NotFoundException", "List with name: ghost doesn't exist.",
                () -> list("ghost", null, null, topic(IN, "nope", false)));
    }

    // Probed precedence: enum, page size, filter shape, token, list, topic.
    @Test
    void errors_followTheProbedPrecedence() {
        assertError("BadRequestException", "1 validation error detected: Value at 'filter.filteredStatus' "
                + "failed to satisfy constraint: Member must satisfy enum value set: [OPT_OUT, OPT_IN]",
                () -> list("ghost", 0, "garbage", new ContactFilter("BOGUS", new TopicFilter("", false))));
        assertError("BadRequestException", "The page size must be between 1 and 1000",
                () -> list(LIST, 0, null, new ContactFilter(null, null)));
        assertError("BadRequestException", "TopicName can't be blank in TopicFilter.",
                () -> list("ghost", null, "garbage", topic(IN, "", false)));
        assertError("BadRequestException", "Provided NextToken is invalid",
                () -> list(LIST, null, "garbage", topic(IN, "nope", false)));
    }

    private void contact(String name, boolean unsubscribeAll, TopicPreference... preferences) {
        service.createContact(LIST, name + "@example.com", List.of(preferences), unsubscribeAll, null, REGION);
    }

    private static ContactFilter topic(String status, String topicName, boolean useDefault) {
        return new ContactFilter(status, new TopicFilter(topicName, useDefault));
    }

    private List<String> names(ContactFilter filter) {
        return names(page(filter, 1000, null).items());
    }

    private PaginatedResult<Contact> page(ContactFilter filter, Integer pageSize, String token) {
        return list(LIST, pageSize, token, filter).contacts();
    }

    private SesContactService.ContactPage list(String listName, Integer pageSize, String token,
                                               ContactFilter filter) {
        return service.listContacts(listName, REGION, SesListPaging.V2_LIST_CONTACTS, pageSize, token, filter);
    }

    private static List<String> names(List<Contact> contacts) {
        List<String> names = new ArrayList<>();
        for (Contact contact : contacts) {
            names.add(contact.getEmailAddress().substring(0, contact.getEmailAddress().indexOf('@')));
        }
        return names;
    }

    private static void assertError(String code, String message, Executable call) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals(code, e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
