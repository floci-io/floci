package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Contact;
import software.amazon.awssdk.services.sesv2.model.CreateContactListRequest;
import software.amazon.awssdk.services.sesv2.model.CreateContactRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteContactListRequest;
import software.amazon.awssdk.services.sesv2.model.ListContactsFilter;
import software.amazon.awssdk.services.sesv2.model.ListContactsRequest;
import software.amazon.awssdk.services.sesv2.model.SubscriptionStatus;
import software.amazon.awssdk.services.sesv2.model.Topic;
import software.amazon.awssdk.services.sesv2.model.TopicFilter;
import software.amazon.awssdk.services.sesv2.model.TopicPreference;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

// Runs as its own account, so the one contact list it needs leaves every other test's list alone.
@DisplayName("SES v2 ListContacts filter")
class SesContactFilterTest {

    private static final String ACCOUNT_ID = "777788880000";
    private static final String LIST = "compat-filter-list";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator account picked by the access key");
        sesV2 = TestFixtures.sesV2Client(ACCOUNT_ID);
        deleteList();
        sesV2.createContactList(CreateContactListRequest.builder().contactListName(LIST)
                .topics(Topic.builder().topicName("news").displayName("News")
                        .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN).build())
                .build());
        sesV2.createContact(CreateContactRequest.builder().contactListName(LIST)
                .emailAddress("opted-out@example.com")
                .topicPreferences(TopicPreference.builder().topicName("news")
                        .subscriptionStatus(SubscriptionStatus.OPT_OUT).build())
                .build());
        sesV2.createContact(CreateContactRequest.builder().contactListName(LIST)
                .emailAddress("no-preference@example.com").build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            deleteList();
            sesV2.close();
        }
    }

    private static void deleteList() {
        try {
            sesV2.deleteContactList(DeleteContactListRequest.builder().contactListName(LIST).build());
        } catch (Exception ignored) {
            // Absent on a clean run; a list left by an interrupted run is the only other case.
        }
    }

    @Test
    @DisplayName("Topic filter falls back to the topic default only when asked to")
    void topicFilterUsesTheDefaultOnlyWhenAsked() {
        assertThat(optedIn(false)).isEmpty();
        assertThat(optedIn(true)).containsExactly("no-preference@example.com");
    }

    private static List<String> optedIn(boolean useDefault) {
        return sesV2.listContacts(ListContactsRequest.builder().contactListName(LIST)
                        .filter(ListContactsFilter.builder().filteredStatus(SubscriptionStatus.OPT_IN)
                                .topicFilter(TopicFilter.builder().topicName("news")
                                        .useDefaultIfPreferenceUnavailable(useDefault).build())
                                .build())
                        .build())
                .contacts().stream().map(Contact::emailAddress).toList();
    }
}
