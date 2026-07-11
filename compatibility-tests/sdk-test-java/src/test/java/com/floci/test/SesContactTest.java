package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateContactListRequest;
import software.amazon.awssdk.services.sesv2.model.CreateContactRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteContactListRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteContactRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SubscriptionStatus;
import software.amazon.awssdk.services.sesv2.model.Topic;
import software.amazon.awssdk.services.sesv2.model.TopicPreference;
import software.amazon.awssdk.services.sesv2.model.UpdateContactRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 Contact APIs (CreateContact / GetContact /
 * ListContacts / UpdateContact / DeleteContact) against a live Floci instance:
 * TopicDefaultPreferences derivation, duplicate/not-found/unknown-topic errors,
 * and the AWS-verified update merge/replace semantics.
 */
@DisplayName("SES v2 Contacts")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesContactTest {

    private static final String LIST = "compat-contacts-list";
    private static final String EMAIL = "alice@example.com";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        deleteAllContactLists();
        sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName(LIST)
                .topics(Topic.builder().topicName("weekly").displayName("Weekly")
                                .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN).build(),
                        Topic.builder().topicName("promos").displayName("Promos")
                                .defaultSubscriptionStatus(SubscriptionStatus.OPT_OUT).build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            deleteAllContactLists();
            sesV2.close();
        }
    }

    private static void deleteAllContactLists() {
        // Only one contact list may exist per account; clear whatever is present (and its contacts).
        sesV2.listContactLists(r -> {}).contactLists().forEach(cl -> {
            String name = cl.contactListName();
            sesV2.listContacts(r -> r.contactListName(name)).contacts().forEach(c ->
                    sesV2.deleteContact(DeleteContactRequest.builder()
                            .contactListName(name).emailAddress(c.emailAddress()).build()));
            sesV2.deleteContactList(DeleteContactListRequest.builder().contactListName(name).build());
        });
    }

    @Test
    @Order(1)
    void createContact_thenGet_derivesTopicDefaultPreferences() {
        sesV2.createContact(CreateContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL)
                .topicPreferences(TopicPreference.builder().topicName("weekly")
                        .subscriptionStatus(SubscriptionStatus.OPT_OUT).build())
                .attributesData("{\"name\":\"Alice\"}").unsubscribeAll(false).build());

        GetContactResponse r = sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL).build());
        assertThat(r.emailAddress()).isEqualTo(EMAIL);
        assertThat(r.topicPreferences()).hasSize(1);
        assertThat(r.topicPreferences().get(0).topicName()).isEqualTo("weekly");
        assertThat(r.topicPreferences().get(0).subscriptionStatus()).isEqualTo(SubscriptionStatus.OPT_OUT);
        // promos not set by the contact -> surfaced via TopicDefaultPreferences (list default OPT_OUT).
        assertThat(r.topicDefaultPreferences()).hasSize(1);
        assertThat(r.topicDefaultPreferences().get(0).topicName()).isEqualTo("promos");
        assertThat(r.topicDefaultPreferences().get(0).subscriptionStatus()).isEqualTo(SubscriptionStatus.OPT_OUT);
        assertThat(r.attributesData()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(r.unsubscribeAll()).isFalse();
    }

    @Test
    @Order(2)
    void createContact_duplicate_throwsAlreadyExists() {
        assertThatThrownBy(() -> sesV2.createContact(CreateContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL).build()))
                .isInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining(EMAIL + " already exists in List.");
    }

    @Test
    @Order(3)
    void getContact_unknown_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress("ghost@example.com").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ghost@example.com doesn't exist in List.");
    }

    @Test
    @Order(4)
    void createContact_unknownTopic_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createContact(CreateContactRequest.builder()
                .contactListName(LIST).emailAddress("bob@example.com")
                .topicPreferences(TopicPreference.builder().topicName("nope")
                        .subscriptionStatus(SubscriptionStatus.OPT_IN).build())
                .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("doesn't contain Topic: nope");
    }

    @Test
    @Order(5)
    void listContacts_includesContact() {
        assertThat(sesV2.listContacts(r -> r.contactListName(LIST)).contacts())
                .anyMatch(c -> EMAIL.equals(c.emailAddress()));
    }

    @Test
    @Order(6)
    void updateContact_mergesPreferences_replacesAttributes() {
        sesV2.updateContact(UpdateContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL)
                .topicPreferences(TopicPreference.builder().topicName("promos")
                        .subscriptionStatus(SubscriptionStatus.OPT_IN).build())
                .build());

        GetContactResponse r = sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL).build());
        // TopicPreferences merged (weekly kept + promos added); AttributesData cleared on omit.
        assertThat(r.topicPreferences()).hasSize(2);
        assertThat(r.attributesData()).isNull();
    }

    @Test
    @Order(7)
    void deleteContact_removesIt() {
        sesV2.deleteContact(DeleteContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL).build());

        assertThatThrownBy(() -> sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress(EMAIL).build()))
                .isInstanceOf(NotFoundException.class);
    }
}
