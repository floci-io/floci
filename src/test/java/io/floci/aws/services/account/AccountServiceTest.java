package io.floci.aws.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.account.model.AlternateContact;
import io.floci.aws.services.account.model.ContactInformation;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        AccountAwareStorageBackend<ContactInformation> primaryStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("account"), eq("account-contact-information.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) primaryStore);
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

    @Test
    void contactInformationPutAndGetRoundTrip() {
        ObjectNode request = contactInformationRequest();
        ObjectNode contact = contactOf(request);
        contact.put("AddressLine2", "Suite 500");
        contact.put("CompanyName", "Example Corp");
        contact.put("WebsiteUrl", "https://example.com");

        service.putContactInformation(ACCOUNT_ID, request);
        ContactInformation stored = service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode());

        assertEquals("Jane Doe", stored.getFullName());
        assertEquals("1 Main St", stored.getAddressLine1());
        assertEquals("Suite 500", stored.getAddressLine2());
        assertNull(stored.getAddressLine3());
        assertEquals("Seattle", stored.getCity());
        assertEquals("WA", stored.getStateOrRegion());
        assertNull(stored.getDistrictOrCounty());
        assertEquals("98101", stored.getPostalCode());
        assertEquals("US", stored.getCountryCode());
        assertEquals("+12065550100", stored.getPhoneNumber());
        assertEquals("Example Corp", stored.getCompanyName());
        assertEquals("https://example.com", stored.getWebsiteUrl());
    }

    @Test
    void putContactInformationReplacesThePreviousContact() {
        ObjectNode first = contactInformationRequest();
        contactOf(first).put("CompanyName", "Example Corp");
        service.putContactInformation(ACCOUNT_ID, first);

        ObjectNode second = contactInformationRequest();
        contactOf(second).put("FullName", "John Roe");
        service.putContactInformation(ACCOUNT_ID, second);

        ContactInformation stored = service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode());
        assertEquals("John Roe", stored.getFullName());
        assertNull(stored.getCompanyName());
    }

    @Test
    void getContactInformationBeforePutThrowsResourceNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void putContactInformationWithoutContactInformationThrowsValidation() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void putContactInformationWithNonStructureContactInformationThrowsSerialization() {
        ObjectNode request = mapper.createObjectNode();
        request.put("ContactInformation", "Jane Doe");
        AwsException error = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request));
        assertEquals("SerializationException", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FullName", "AddressLine1", "City", "PostalCode", "CountryCode", "PhoneNumber"})
    void putContactInformationRejectsAMissingRequiredMember(String member) {
        ObjectNode request = contactInformationRequest();
        contactOf(request).remove(member);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains(member), error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"FullName,50", "AddressLine1,60", "AddressLine2,60", "AddressLine3,60", "City,50",
            "StateOrRegion,50", "DistrictOrCounty,50", "PostalCode,20", "CompanyName,50", "WebsiteUrl,256"})
    void putContactInformationEnforcesTheMaximumLengthOfEachMember(String member, int maximum) {
        ObjectNode request = contactInformationRequest();
        contactOf(request).put(member, "1".repeat(maximum));
        service.putContactInformation(ACCOUNT_ID, request);

        assertContactInformationRejected(member, "1".repeat(maximum + 1));
    }

    @Test
    void putContactInformationRejectsAnEmptyOptionalMember() {
        assertContactInformationRejected("AddressLine2", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"U", "USA"})
    void putContactInformationRequiresATwoCharacterCountryCode(String countryCode) {
        assertContactInformationRejected("CountryCode", countryCode);
    }

    @Test
    void putContactInformationAcceptsSpacesParenthesesAndHyphensInThePhoneNumber() {
        ObjectNode request = contactInformationRequest();
        contactOf(request).put("PhoneNumber", "+1 (206) 555-0100");

        service.putContactInformation(ACCOUNT_ID, request);

        assertEquals("+1 (206) 555-0100",
                service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()).getPhoneNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12065550100", "+", "1+2065550100", "+1 206 555 ext 7"})
    void putContactInformationRejectsAPhoneNumberOutsideThePattern(String phoneNumber) {
        assertContactInformationRejected("PhoneNumber", phoneNumber);
    }

    @Test
    void putContactInformationLimitsThePhoneNumberToTwentyCharacters() {
        ObjectNode request = contactInformationRequest();
        contactOf(request).put("PhoneNumber", "+" + "1".repeat(19));
        service.putContactInformation(ACCOUNT_ID, request);

        assertContactInformationRejected("PhoneNumber", "+" + "1".repeat(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "CA", "GB", "DE", "JP", "IN", "BR"})
    void putContactInformationRequiresStateOrRegionForTheDocumentedCountries(String countryCode) {
        ObjectNode request = contactInformationRequest();
        ObjectNode contact = contactOf(request);
        contact.put("CountryCode", countryCode);
        contact.remove("StateOrRegion");
        AwsException error = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("StateOrRegion"), error.getMessage());
    }

    @Test
    void putContactInformationAllowsNoStateOrRegionElsewhere() {
        ObjectNode request = contactInformationRequest();
        ObjectNode contact = contactOf(request);
        contact.put("CountryCode", "FR");
        contact.remove("StateOrRegion");

        service.putContactInformation(ACCOUNT_ID, request);

        assertNull(service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()).getStateOrRegion());
    }

    @Test
    void contactInformationForAnotherAccountNeedsAnOrganization() {
        when(organizations.describeOrganization(ACCOUNT_ID))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException", "no organization", 400));
        ObjectNode request = contactInformationRequest();
        request.put("AccountId", "111122223333");

        AwsException putError = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request));
        assertEquals("AccessDeniedException", putError.getErrorCode());
        AwsException getError = assertThrows(AwsException.class,
                () -> service.getContactInformation(ACCOUNT_ID, request));
        assertEquals("AccessDeniedException", getError.getErrorCode());
    }

    @Test
    void managementAccountPutsContactInformationForAMemberAccount() {
        String member = "111122223333";
        Organization organization = new Organization();
        organization.setFeatureSet("ALL");
        organization.setMasterAccountId(ACCOUNT_ID);
        organization.setEnabledServicePrincipals(Map.of(ServicePrincipals.of("account"), Instant.EPOCH));
        when(organizations.describeOrganization(ACCOUNT_ID)).thenReturn(organization);
        when(organizations.describeAccount(ACCOUNT_ID, ACCOUNT_ID)).thenReturn(new OrganizationAccount());
        ObjectNode request = contactInformationRequest();
        request.put("AccountId", member);

        service.putContactInformation(ACCOUNT_ID, request);

        ObjectNode memberRequest = mapper.createObjectNode();
        memberRequest.put("AccountId", member);
        assertEquals("Jane Doe", service.getContactInformation(ACCOUNT_ID, memberRequest).getFullName());
        assertEquals("Jane Doe", service.getContactInformation(member, mapper.createObjectNode()).getFullName());
        AwsException error = assertThrows(AwsException.class,
                () -> service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void clearRemovesContactInformation() {
        service.putContactInformation(ACCOUNT_ID, contactInformationRequest());
        service.clear();
        AwsException error = assertThrows(AwsException.class,
                () -> service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    private void assertContactInformationRejected(String member, String value) {
        ObjectNode request = contactInformationRequest();
        contactOf(request).put(member, value);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request),
                member + "=" + value);
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains(member), error.getMessage());
    }

    private ObjectNode contactInformationRequest() {
        ObjectNode contact = mapper.createObjectNode();
        contact.put("FullName", "Jane Doe");
        contact.put("AddressLine1", "1 Main St");
        contact.put("City", "Seattle");
        contact.put("StateOrRegion", "WA");
        contact.put("PostalCode", "98101");
        contact.put("CountryCode", "US");
        contact.put("PhoneNumber", "+12065550100");
        ObjectNode request = mapper.createObjectNode();
        request.set("ContactInformation", contact);
        return request;
    }

    private static ObjectNode contactOf(ObjectNode request) {
        return (ObjectNode) request.get("ContactInformation");
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
