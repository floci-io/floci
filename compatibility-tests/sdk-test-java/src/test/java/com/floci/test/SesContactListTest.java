package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateContactListRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteContactListRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactListRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactListResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SubscriptionStatus;
import software.amazon.awssdk.services.sesv2.model.Topic;
import software.amazon.awssdk.services.sesv2.model.UpdateContactListRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 ContactList APIs. Verifies the AWS Java
 * SDK v2 marshalling of CreateContactList / GetContactList / ListContactLists /
 * UpdateContactList / DeleteContactList, the AWS-confirmed one-list-per-account
 * limit and input constraints (BadRequestException), and NotFoundException for
 * unknown lists, against a live Floci instance.
 */
@DisplayName("SES v2 Contact Lists")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesContactListTest {

    private static final String LIST = "compat-contact-list";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        deleteAllContactLists();
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            deleteAllContactLists();
            sesV2.close();
        }
    }

    private static void deleteAllContactLists() {
        // Only one contact list may exist per account, so clear whatever is present. Let failures
        // surface — a stuck list here would otherwise make later tests fail with a confusing
        // one-list-per-account error.
        sesV2.listContactLists(r -> {}).contactLists().forEach(cl ->
                sesV2.deleteContactList(DeleteContactListRequest.builder()
                        .contactListName(cl.contactListName()).build()));
    }

    @Test
    @Order(1)
    void createContactList_thenGetReturnsTopicsAndDescription() {
        sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName(LIST)
                .description("newsletter")
                .topics(Topic.builder()
                        .topicName("weekly")
                        .displayName("Weekly")
                        .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN)
                        .description("weekly digest")
                        .build())
                .build());

        GetContactListResponse response = sesV2.getContactList(
                GetContactListRequest.builder().contactListName(LIST).build());
        assertThat(response.contactListName()).isEqualTo(LIST);
        assertThat(response.description()).isEqualTo("newsletter");
        assertThat(response.topics()).hasSize(1);
        assertThat(response.topics().get(0).topicName()).isEqualTo("weekly");
        assertThat(response.topics().get(0).defaultSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.OPT_IN);
        assertThat(response.createdTimestamp()).isNotNull();
        assertThat(response.lastUpdatedTimestamp()).isNotNull();
    }

    @Test
    @Order(2)
    void createSecondContactList_throwsBadRequestPerAccountLimit() {
        assertThatThrownBy(() -> sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName("compat-contact-list-2").build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A maximum of 1 Lists allowed per account");
    }

    @Test
    @Order(3)
    void createDuplicateName_throwsPerAccountLimit() {
        // A duplicate name hits the one-list-per-account limit before any "already exists" check,
        // so AWS returns BadRequestException here, not AlreadyExistsException.
        assertThatThrownBy(() -> sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName(LIST).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A maximum of 1 Lists allowed per account");
    }

    @Test
    @Order(4)
    void getContactList_unknown_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.getContactList(
                GetContactListRequest.builder().contactListName("compat-contact-ghost").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("List with name: compat-contact-ghost doesn't exist.");
    }

    @Test
    @Order(5)
    void listContactLists_includesCreatedList() {
        assertThat(sesV2.listContactLists(r -> {}).contactLists())
                .anyMatch(cl -> LIST.equals(cl.contactListName()));
    }

    @Test
    @Order(6)
    void updateContactList_replacesTopicsAndDescription() {
        sesV2.updateContactList(UpdateContactListRequest.builder()
                .contactListName(LIST)
                .description("updated")
                .topics(Topic.builder()
                        .topicName("monthly")
                        .displayName("Monthly")
                        .defaultSubscriptionStatus(SubscriptionStatus.OPT_OUT)
                        .build())
                .build());

        GetContactListResponse response = sesV2.getContactList(
                GetContactListRequest.builder().contactListName(LIST).build());
        assertThat(response.description()).isEqualTo("updated");
        assertThat(response.topics()).hasSize(1);
        assertThat(response.topics().get(0).topicName()).isEqualTo("monthly");
        assertThat(response.topics().get(0).defaultSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.OPT_OUT);
    }

    @Test
    @Order(7)
    void deleteContactList_removesIt() {
        sesV2.deleteContactList(DeleteContactListRequest.builder().contactListName(LIST).build());

        assertThatThrownBy(() -> sesV2.getContactList(
                GetContactListRequest.builder().contactListName(LIST).build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(8)
    void deleteContactList_unknown_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.deleteContactList(
                DeleteContactListRequest.builder().contactListName("compat-contact-ghost").build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(9)
    void createContactList_tooManyTopics_throwsBadRequest() {
        List<Topic> manyTopics = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            manyTopics.add(Topic.builder().topicName("t" + i).displayName("D" + i)
                    .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN).build());
        }
        assertThatThrownBy(() -> sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName("compat-many").topics(manyTopics).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Maximum of <20> topics allowed per ContactList");
    }

    @Test
    @Order(10)
    void createContactList_duplicateTopicNames_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName("compat-dup")
                .topics(Topic.builder().topicName("x").displayName("A")
                                .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN).build(),
                        Topic.builder().topicName("x").displayName("B")
                                .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN).build())
                .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate topic names are not allowed within a List.");
    }

    @Test
    @Order(11)
    void createContactList_invalidNameChars_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName("bad name!").build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ContactListName can contain up to 64 characters");
    }

    @Test
    @Order(12)
    void createContactList_descriptionTooLong_throwsBadRequest() {
        assertThatThrownBy(() -> sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName("compat-desc").description("a".repeat(501)).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("List description can contain up to 500 characters.");
    }

    @Test
    @Order(13)
    void getContactList_invalidName_throwsBadRequestNotNotFound() {
        // Real AWS validates the name on read/delete too, returning 400 (not 404).
        assertThatThrownBy(() -> sesV2.getContactList(
                GetContactListRequest.builder().contactListName("a".repeat(65)).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ContactListName can contain up to 64 characters");
    }
}
