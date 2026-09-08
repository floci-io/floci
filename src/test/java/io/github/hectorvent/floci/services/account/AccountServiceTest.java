package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<AlternateContact> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("account"), eq("account-alternate-contacts.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);
        service = new AccountService(storageFactory, mock(OrganizationsService.class));
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
