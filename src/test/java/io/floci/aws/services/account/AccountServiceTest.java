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
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.floci.aws.services.account.AlternateContactRequests.alternateContactRequest;
import static io.floci.aws.services.account.ContactInformationRequests.contactInformationRequest;
import static io.floci.aws.services.account.ContactInformationRequests.contactOf;
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
    private static final String MANAGEMENT_ACCOUNT_ID = "444455556666";
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
        ObjectNode request = alternateContactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        AlternateContact contact = service.getAlternateContact(ACCOUNT_ID, request);
        assertEquals("security@example.com", contact.getEmailAddress());
        assertEquals("SECURITY", contact.getAlternateContactType());
    }

    @Test
    void nonStringAccountIdReturnsSerializationException() {
        ObjectNode request = alternateContactRequest();
        request.put("AccountId", 123456789012L);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAlternateContact(ACCOUNT_ID, request));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals("AccountId must be a string.", error.getMessage());
    }

    @Test
    void clearRemovesContacts() {
        ObjectNode request = alternateContactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        service.clear();
        AwsException error = assertThrows(AwsException.class,
                () -> service.getAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void deleteRemovesContactAndDeletingUnsetThrows() {
        ObjectNode request = alternateContactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        service.deleteAlternateContact(ACCOUNT_ID, request);
        AwsException getError = assertThrows(AwsException.class,
                () -> service.getAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", getError.getErrorCode());
        AwsException deleteError = assertThrows(AwsException.class,
                () -> service.deleteAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", deleteError.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AlternateContactType", "Name", "Title", "EmailAddress", "PhoneNumber"})
    void putAlternateContactRejectsAMissingRequiredMember(String member) {
        ObjectNode request = alternateContactRequest();
        request.remove(member);
        assertAlternateContactRejected(() -> service.putAlternateContact(ACCOUNT_ID, request), member);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BILLING", "OPERATIONS", "SECURITY"})
    void putAlternateContactAcceptsEachContactType(String type) {
        ObjectNode request = alternateContactRequest();
        request.put("AlternateContactType", type);
        service.putAlternateContact(ACCOUNT_ID, request);

        assertEquals(type, service.getAlternateContact(ACCOUNT_ID, request).getAlternateContactType());
    }

    @Test
    void putAlternateContactAcceptsAPhoneNumberWithoutAPlus() {
        ObjectNode request = alternateContactRequest();
        request.put("PhoneNumber", "12025550123");
        service.putAlternateContact(ACCOUNT_ID, request);

        assertEquals("12025550123", service.getAlternateContact(ACCOUNT_ID, request).getPhoneNumber());
    }

    @ParameterizedTest
    @CsvSource({"Name,64", "Title,50", "PhoneNumber,25"})
    void putAlternateContactEnforcesTheMaximumLengthOfEachMember(String member, int maximum) {
        ObjectNode request = alternateContactRequest();
        request.put(member, "1".repeat(maximum));
        service.putAlternateContact(ACCOUNT_ID, request);

        request.put(member, "1".repeat(maximum + 1));
        assertAlternateContactRejected(() -> service.putAlternateContact(ACCOUNT_ID, request), member);
    }

    @Test
    void putAlternateContactLimitsTheEmailAddressTo254Characters() {
        ObjectNode request = alternateContactRequest();
        request.put("EmailAddress", "a".repeat(242) + "@example.com");
        service.putAlternateContact(ACCOUNT_ID, request);

        request.put("EmailAddress", "a".repeat(243) + "@example.com");
        assertAlternateContactRejected(() -> service.putAlternateContact(ACCOUNT_ID, request), "EmailAddress");
    }

    @ParameterizedTest
    @CsvSource({"EmailAddress,security.example.com", "EmailAddress,security@example", "PhoneNumber,555-CALL",
            "Name,''"})
    void putAlternateContactRejectsAMemberOutsideItsConstraints(String member, String value) {
        ObjectNode request = alternateContactRequest();
        request.put(member, value);
        assertAlternateContactRejected(() -> service.putAlternateContact(ACCOUNT_ID, request), member);
    }

    @Test
    void putAlternateContactWithANonStringMemberThrowsSerialization() {
        ObjectNode request = alternateContactRequest();
        request.put("PhoneNumber", 15550100);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAlternateContact(ACCOUNT_ID, request));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals("PhoneNumber must be a string.", error.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OTHER", "security"})
    void getAndDeleteAlternateContactRequireAKnownContactType(String type) {
        ObjectNode request = mapper.createObjectNode().put("AlternateContactType", type);

        assertAlternateContactRejected(() -> service.getAlternateContact(ACCOUNT_ID, request), "AlternateContactType");
        assertAlternateContactRejected(() -> service.deleteAlternateContact(ACCOUNT_ID, request),
                "AlternateContactType");
    }

    @Test
    void alternateContactChecksTheRequestBeforeTheTargetAccount() {
        when(organizations.describeOrganization(ACCOUNT_ID))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException", "no organization", 400));
        ObjectNode request = mapper.createObjectNode();
        request.put("AccountId", "111122223333");
        request.put("AlternateContactType", "OTHER");

        assertAlternateContactRejected(() -> service.getAlternateContact(ACCOUNT_ID, request), "AlternateContactType");
    }

    @Test
    void alternateContactRejectsAMalformedAccountId() {
        ObjectNode request = alternateContactRequest();
        request.put("AccountId", "1234");

        assertAlternateContactRejected(() -> service.putAlternateContact(ACCOUNT_ID, request), "AccountId");
        assertAlternateContactRejected(() -> service.getAlternateContact(ACCOUNT_ID, request), "AccountId");
        assertAlternateContactRejected(() -> service.deleteAlternateContact(ACCOUNT_ID, request), "AccountId");
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

    @Test
    void putContactInformationWithANonStringMemberThrowsSerialization() {
        ObjectNode request = contactInformationRequest();
        contactOf(request).put("PostalCode", 98101);
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
    void putContactInformationRequiresStateOrRegionForALowercaseCountryCode() {
        ObjectNode request = contactInformationRequest();
        ObjectNode contact = contactOf(request);
        contact.put("CountryCode", "us");
        contact.remove("StateOrRegion");
        AwsException error = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request));
        assertEquals("ValidationException", error.getErrorCode());
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
    void contactInformationRejectsAMalformedAccountId() {
        ObjectNode request = contactInformationRequest();
        request.put("AccountId", "1234");

        AwsException putError = assertThrows(AwsException.class,
                () -> service.putContactInformation(ACCOUNT_ID, request));
        assertEquals("ValidationException", putError.getErrorCode());
        AwsException getError = assertThrows(AwsException.class,
                () -> service.getContactInformation(ACCOUNT_ID, request));
        assertEquals("ValidationException", getError.getErrorCode());
    }

    @Test
    void managementAccountPutsContactInformationForAMemberAccount() {
        String member = "111122223333";
        callerIsInAnOrganizationManagedBy(ACCOUNT_ID, new OrganizationAccount());
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
    void delegatedAdministratorCannotNameTheManagementAccount() {
        callerIsInAnOrganizationManagedBy(MANAGEMENT_ACCOUNT_ID, delegatedAdministrator());
        ObjectNode alternateContact = alternateContactRequest();
        alternateContact.put("AccountId", MANAGEMENT_ACCOUNT_ID);
        ObjectNode contactInformation = contactInformationRequest();
        contactInformation.put("AccountId", MANAGEMENT_ACCOUNT_ID);

        assertManagementAccountRefused(() -> service.putAlternateContact(ACCOUNT_ID, alternateContact));
        assertManagementAccountRefused(() -> service.getAlternateContact(ACCOUNT_ID, alternateContact));
        assertManagementAccountRefused(() -> service.deleteAlternateContact(ACCOUNT_ID, alternateContact));
        assertManagementAccountRefused(() -> service.putContactInformation(ACCOUNT_ID, contactInformation));
        assertManagementAccountRefused(() -> service.getContactInformation(ACCOUNT_ID, contactInformation));
    }

    @Test
    void managementAccountCannotNameItself() {
        callerIsInAnOrganizationManagedBy(ACCOUNT_ID, new OrganizationAccount());
        ObjectNode request = alternateContactRequest();
        request.put("AccountId", ACCOUNT_ID);

        assertManagementAccountRefused(() -> service.putAlternateContact(ACCOUNT_ID, request));
    }

    @Test
    void delegatedAdministratorCanNameItsOwnAccount() {
        callerIsInAnOrganizationManagedBy(MANAGEMENT_ACCOUNT_ID, delegatedAdministrator());
        ObjectNode request = alternateContactRequest();
        request.put("AccountId", ACCOUNT_ID);

        service.putAlternateContact(ACCOUNT_ID, request);

        assertEquals("security@example.com",
                service.getAlternateContact(ACCOUNT_ID, alternateContactRequest()).getEmailAddress());
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

    private static void assertAlternateContactRejected(Executable call, String member) {
        AwsException error = assertThrows(AwsException.class, call, member);
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().startsWith("Invalid parameter at '" + member + "'"), error.getMessage());
    }

    private void callerIsInAnOrganizationManagedBy(String managementAccountId, OrganizationAccount caller) {
        Organization organization = new Organization();
        organization.setFeatureSet("ALL");
        organization.setMasterAccountId(managementAccountId);
        organization.setEnabledServicePrincipals(Map.of(ServicePrincipals.of("account"), Instant.EPOCH));
        when(organizations.describeOrganization(ACCOUNT_ID)).thenReturn(organization);
        when(organizations.describeAccount(ACCOUNT_ID, ACCOUNT_ID)).thenReturn(caller);
    }

    private static OrganizationAccount delegatedAdministrator() {
        OrganizationAccount account = new OrganizationAccount();
        account.setDelegatedServices(Map.of(ServicePrincipals.of("account"), Instant.EPOCH));
        return account;
    }

    private static void assertManagementAccountRefused(Executable call) {
        AwsException error = assertThrows(AwsException.class, call);
        assertEquals("AccessDeniedException", error.getErrorCode());
        assertEquals(403, error.getHttpStatus());
        assertTrue(error.getMessage().contains("management account of the organization"), error.getMessage());
    }
}
