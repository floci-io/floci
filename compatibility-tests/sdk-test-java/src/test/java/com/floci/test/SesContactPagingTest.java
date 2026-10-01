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
import software.amazon.awssdk.services.sesv2.model.DeleteContactRequest;
import software.amazon.awssdk.services.sesv2.model.ListContactListsRequest;
import software.amazon.awssdk.services.sesv2.model.ListContactsRequest;
import software.amazon.awssdk.services.sesv2.model.ListContactsResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SES v2 contact and contact list paging")
class SesContactPagingTest {

    private static final String LIST = "compat-paging-list";
    private static final List<String> ADDRESSES =
            List.of("page-b@example.com", "page-c@example.com", "page-a@example.com");

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        deleteAllContactLists();
        sesV2.createContactList(CreateContactListRequest.builder().contactListName(LIST).build());
        for (String address : ADDRESSES) {
            sesV2.createContact(CreateContactRequest.builder()
                    .contactListName(LIST).emailAddress(address).build());
        }
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
        sesV2.listContactListsPaginator(ListContactListsRequest.builder().build()).stream()
                .flatMap(page -> page.contactLists().stream()).toList().forEach(cl -> {
                    String name = cl.contactListName();
                    sesV2.listContactsPaginator(ListContactsRequest.builder().contactListName(name).build())
                            .stream().flatMap(page -> page.contacts().stream()).toList().forEach(c ->
                                    sesV2.deleteContact(DeleteContactRequest.builder()
                                            .contactListName(name).emailAddress(c.emailAddress()).build()));
                    sesV2.deleteContactList(DeleteContactListRequest.builder().contactListName(name).build());
                });
    }

    @Test
    @DisplayName("Paginator walks one-item pages over every contact once")
    void paginatorWalksEveryContactOnce() {
        List<String> listed = new ArrayList<>();
        for (ListContactsResponse page : sesV2.listContactsPaginator(
                ListContactsRequest.builder().contactListName(LIST).pageSize(1).build())) {
            assertThat(page.contacts()).hasSizeLessThanOrEqualTo(1);
            page.contacts().stream().map(Contact::emailAddress).forEach(listed::add);
        }

        assertThat(listed).containsExactlyInAnyOrderElementsOf(ADDRESSES);
    }

    @Test
    @DisplayName("Contact list paginator returns the one list")
    void contactListPaginatorReturnsTheList() {
        assertThat(sesV2.listContactListsPaginator(ListContactListsRequest.builder().pageSize(1).build())
                .stream().flatMap(page -> page.contactLists().stream()).map(cl -> cl.contactListName()))
                .containsExactly(LIST);
    }
}
