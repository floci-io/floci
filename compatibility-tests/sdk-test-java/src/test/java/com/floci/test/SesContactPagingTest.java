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
import software.amazon.awssdk.services.sesv2.model.ListContactListsRequest;
import software.amazon.awssdk.services.sesv2.model.ListContactsRequest;
import software.amazon.awssdk.services.sesv2.model.ListContactsResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

// Runs as its own account, so the one contact list it needs leaves every other test's list alone.
@DisplayName("SES v2 contact and contact list paging")
class SesContactPagingTest {

    private static final String ACCOUNT_ID = "777788889999";
    private static final String LIST = "compat-paging-list";
    private static final List<String> ADDRESSES =
            List.of("page-b@example.com", "page-c@example.com", "page-a@example.com");

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator account picked by the access key");
        sesV2 = TestFixtures.sesV2Client(ACCOUNT_ID);
        deleteList();
        sesV2.createContactList(CreateContactListRequest.builder().contactListName(LIST).build());
        for (String address : ADDRESSES) {
            sesV2.createContact(CreateContactRequest.builder()
                    .contactListName(LIST).emailAddress(address).build());
        }
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
