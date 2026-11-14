package io.floci.aws.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.account.model.AlternateContact;
import io.floci.aws.services.account.model.ContactInformation;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class AccountService implements Resettable, StoredResourceArns {
    private static final String ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL = ServicePrincipals.of("account");
    private static final Set<String> COUNTRIES_REQUIRING_STATE_OR_REGION =
            Set.of("US", "CA", "GB", "DE", "JP", "IN", "BR");
    private static final String PRIMARY_CONTACT_KEY = "primary";

    private final AccountAwareStorageBackend<AlternateContact> contacts;
    private final AccountAwareStorageBackend<ContactInformation> contactInformation;
    private final OrganizationsService organizationsService;

    @Inject
    public AccountService(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this.contacts = storageFactory.create("account", "account-alternate-contacts.json",
                new TypeReference<Map<String, AlternateContact>>() {});
        this.contactInformation = storageFactory.create("account", "account-contact-information.json",
                new TypeReference<Map<String, ContactInformation>>() {});
        this.organizationsService = organizationsService;
    }

    public void putAlternateContact(String callerAccountId, JsonNode request) {
        AccountRequestShapes.check("PutAlternateContact", request);
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = text(request, "AlternateContactType");
        AlternateContact contact = new AlternateContact(
                type,
                text(request, "EmailAddress"),
                text(request, "Name"),
                text(request, "PhoneNumber"),
                text(request, "Title"));
        contacts.putForAccount(targetAccountId, type, contact);
    }

    public AlternateContact getAlternateContact(String callerAccountId, JsonNode request) {
        AccountRequestShapes.check("GetAlternateContact", request);
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = text(request, "AlternateContactType");
        return contacts.getForAccount(targetAccountId, type)
                .orElseThrow(AccountService::notFound);
    }

    public synchronized void deleteAlternateContact(String callerAccountId, JsonNode request) {
        AccountRequestShapes.check("DeleteAlternateContact", request);
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = text(request, "AlternateContactType");
        if (contacts.getForAccount(targetAccountId, type).isEmpty()) {
            throw notFound();
        }
        contacts.deleteForAccount(targetAccountId, type);
    }

    public void putContactInformation(String callerAccountId, JsonNode request) {
        AccountRequestShapes.check("PutContactInformation", request);
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        JsonNode contact = request.get("ContactInformation");
        ContactInformation information = new ContactInformation();
        information.setFullName(text(contact, "FullName"));
        information.setAddressLine1(text(contact, "AddressLine1"));
        information.setAddressLine2(text(contact, "AddressLine2"));
        information.setAddressLine3(text(contact, "AddressLine3"));
        information.setCity(text(contact, "City"));
        information.setStateOrRegion(text(contact, "StateOrRegion"));
        information.setDistrictOrCounty(text(contact, "DistrictOrCounty"));
        information.setPostalCode(text(contact, "PostalCode"));
        information.setCountryCode(text(contact, "CountryCode"));
        information.setPhoneNumber(text(contact, "PhoneNumber"));
        information.setCompanyName(text(contact, "CompanyName"));
        information.setWebsiteUrl(text(contact, "WebsiteUrl"));
        // Only the API reference states this rule, so the vendored model cannot check it.
        if (information.getStateOrRegion() == null && COUNTRIES_REQUIRING_STATE_OR_REGION
                .contains(information.getCountryCode().toUpperCase(Locale.ROOT))) {
            throw validation("StateOrRegion is required when CountryCode is "
                    + information.getCountryCode() + ".");
        }
        contactInformation.putForAccount(targetAccountId, PRIMARY_CONTACT_KEY, information);
    }

    public ContactInformation getContactInformation(String callerAccountId, JsonNode request) {
        AccountRequestShapes.check("GetContactInformation", request);
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        return contactInformation.getForAccount(targetAccountId, PRIMARY_CONTACT_KEY)
                .orElseThrow(AccountService::contactInformationNotFound);
    }

    private String resolveTargetAccount(String callerAccountId, JsonNode request) {
        String requestedAccountId = text(request, "AccountId");
        if (requestedAccountId == null) {
            return callerAccountId;
        }

        Organization organization;
        OrganizationAccount caller;
        try {
            organization = organizationsService.describeOrganization(callerAccountId);
            caller = organizationsService.describeAccount(callerAccountId, callerAccountId);
            organizationsService.describeAccount(callerAccountId, requestedAccountId);
        } catch (AwsException e) {
            throw new AwsException("AccessDeniedException",
                    "The specified account cannot be accessed by the calling account.", 403);
        }

        if (!"ALL".equals(organization.getFeatureSet())) {
            throw new AwsException("AccessDeniedException",
                    "The organization must have all features enabled.", 403);
        }
        if (!organization.getEnabledServicePrincipals().containsKey(ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL)) {
            throw new AwsException("AccessDeniedException",
                    "Trusted access for AWS Account Management is not enabled for the organization.", 403);
        }

        boolean managementAccount = callerAccountId.equals(organization.getMasterAccountId());
        boolean delegatedAdministrator = caller.getDelegatedServices()
                .containsKey(ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL);
        if (!managementAccount && !delegatedAdministrator) {
            throw new AwsException("AccessDeniedException",
                    "The calling account is not the management account or a delegated administrator.", 403);
        }
        if (requestedAccountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "AccountId must name a member account, not the management account of the organization.", 403);
        }
        return requestedAccountId;
    }

    @Override
    public void clear() {
        contacts.clear();
        contactInformation.clear();
    }

    /** The namespace of the resource types this service finds, as the request-resources table names it. */
    @Override
    public String namespace() {
        return "account";
    }

    /**
     * The ARN of the member account of the caller's organization a request names by its {@code AccountId}, which
     * Account Management acts on: {@code arn:aws:account::<management account>:account/<organization>/<member>}, its
     * organization and management account being ones no request gives. Empty for a caller in no organization or an
     * account its organization does not hold, which the handler refuses.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"accountInOrganization".equals(type) || names.size() != 1 || names.getFirst() == null) {
            return Optional.empty();
        }
        String callerAccountId = contacts.accountId();
        Organization organization;
        try {
            organization = organizationsService.describeOrganization(callerAccountId);
            organizationsService.describeAccount(callerAccountId, names.getFirst());
        } catch (AwsException e) {
            return Optional.empty();
        }
        return Optional.of(AwsArnUtils.Arn.global(AwsArnUtils.parse(organization.getArn()).partition(), "account",
                organization.getMasterAccountId(), "account/" + organization.getId() + "/" + names.getFirst())
                .toString());
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException",
                "The alternate contact does not exist for the specified account and contact type.", 404);
    }

    private static AwsException contactInformationNotFound() {
        return new AwsException("ResourceNotFoundException",
                "The contact information does not exist for the specified account.", 404);
    }
}
