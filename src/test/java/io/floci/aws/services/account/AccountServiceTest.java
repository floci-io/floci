package io.floci.aws.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.account.model.AlternateContact;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountService service;
    private OrganizationsService organizations;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<AlternateContact> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("account"), eq("account-alternate-contacts.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);
        organizations = mock(OrganizationsService.class);
        service = new AccountService(storageFactory, organizations);
    }

    @Test
    void ownAccountPutAndGetRoundTrip() {
        ObjectNode request = contactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        AlternateContact contact = service.getAlternateContact(ACCOUNT_ID, request);
        assertEquals("security@example.com", contact.getEmailAddress());
        assertEquals("SECURITY", contact.getAlternateContactType());
    }

    @Test
    void nonStringAccountIdReturnsSerializationException() {
        ObjectNode request = contactRequest();
        request.put("AccountId", 123456789012L);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAlternateContact(ACCOUNT_ID, request));
        assertEquals("SerializationException", error.getErrorCode());
    }

    @Test
    void clearRemovesContacts() {
        ObjectNode request = contactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        service.clear();
        AwsException error = assertThrows(AwsException.class,
                () -> service.getAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void deleteRemovesContactAndDeletingUnsetThrows() {
        ObjectNode request = contactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        service.deleteAlternateContact(ACCOUNT_ID, request);
        AwsException getError = assertThrows(AwsException.class,
                () -> service.getAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", getError.getErrorCode());
        AwsException deleteError = assertThrows(AwsException.class,
                () -> service.deleteAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", deleteError.getErrorCode());
    }

    @Test
    void findsTheMemberAccountOfTheCallersOrganizationARequestNames() {
        Organization organization = new Organization();
        organization.setId("o-abcdefghij");
        organization.setArn("arn:aws:organizations::" + ACCOUNT_ID + ":organization/o-abcdefghij");
        organization.setMasterAccountId(ACCOUNT_ID);
        when(organizations.describeOrganization(ACCOUNT_ID)).thenReturn(organization);
        when(organizations.describeAccount(ACCOUNT_ID, "999988887777"))
                .thenThrow(new AwsException("AccountNotFoundException", "not a member", 400));

        assertEquals(Optional.of("arn:aws:account::" + ACCOUNT_ID + ":account/o-abcdefghij/111122223333"),
                service.find("accountInOrganization", List.of("111122223333"), "us-east-1"));
        // An account the organization does not hold, and another type, find nothing.
        assertEquals(Optional.empty(), service.find("accountInOrganization", List.of("999988887777"), "us-east-1"));
        assertEquals(Optional.empty(), service.find("account", List.of("111122223333"), "us-east-1"));
    }

    @Test
    void findsNoMemberAccountForACallerInNoOrganization() {
        when(organizations.describeOrganization(ACCOUNT_ID))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException", "no organization", 400));
        assertEquals(Optional.empty(), service.find("accountInOrganization", List.of("111122223333"), "us-east-1"));
    }

    private ObjectNode contactRequest() {
        ObjectNode request = mapper.createObjectNode();
        request.put("AlternateContactType", "SECURITY");
        request.put("EmailAddress", "security@example.com");
        request.put("Name", "Security Team");
        request.put("PhoneNumber", "+1 555 0100");
        request.put("Title", "Security");
        return request;
    }
}
