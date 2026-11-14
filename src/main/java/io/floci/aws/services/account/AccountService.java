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
import java.util.regex.Pattern;

@ApplicationScoped
public class AccountService implements Resettable, StoredResourceArns {
    private static final String ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL = ServicePrincipals.of("account");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern EMAIL = Pattern.compile("\\s*[\\w+=.#|!&-]+@[\\w.-]+\\.[\\w]+\\s*");
    private static final Pattern PHONE = Pattern.compile("[\\s0-9()+-]+");
    private static final Pattern PRIMARY_CONTACT_PHONE = Pattern.compile("[+][\\s0-9()-]+");
    private static final Set<String> CONTACT_TYPES = Set.of("BILLING", "OPERATIONS", "SECURITY");
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
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = requireContactType(request);
        AlternateContact contact = new AlternateContact(
                type,
                requirePattern(request, "EmailAddress", 1, 254, EMAIL),
                requireLength(request, "Name", 1, 64),
                requirePattern(request, "PhoneNumber", 1, 25, PHONE),
                requireLength(request, "Title", 1, 50));
        contacts.putForAccount(targetAccountId, type, contact);
    }

    public AlternateContact getAlternateContact(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = requireContactType(request);
        return contacts.getForAccount(targetAccountId, type)
                .orElseThrow(AccountService::notFound);
    }

    public synchronized void deleteAlternateContact(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = requireContactType(request);
        if (contacts.getForAccount(targetAccountId, type).isEmpty()) {
            throw notFound();
        }
        contacts.deleteForAccount(targetAccountId, type);
    }

    public void putContactInformation(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        JsonNode contact = request == null ? null : request.get("ContactInformation");
        if (contact == null || contact.isNull()) {
            throw validation("ContactInformation is required.");
        }
        if (!contact.isObject()) {
            throw new AwsException("SerializationException",
                    "ContactInformation must be a structure.", 400);
        }
        ContactInformation information = new ContactInformation();
        information.setFullName(requireLength(contact, "FullName", 1, 50));
        information.setAddressLine1(requireLength(contact, "AddressLine1", 1, 60));
        information.setAddressLine2(optionalLength(contact, "AddressLine2", 1, 60));
        information.setAddressLine3(optionalLength(contact, "AddressLine3", 1, 60));
        information.setCity(requireLength(contact, "City", 1, 50));
        information.setStateOrRegion(optionalLength(contact, "StateOrRegion", 1, 50));
        information.setDistrictOrCounty(optionalLength(contact, "DistrictOrCounty", 1, 50));
        information.setPostalCode(requireLength(contact, "PostalCode", 1, 20));
        information.setCountryCode(requireLength(contact, "CountryCode", 2, 2));
        information.setPhoneNumber(requirePattern(contact, "PhoneNumber", 1, 20, PRIMARY_CONTACT_PHONE));
        information.setCompanyName(optionalLength(contact, "CompanyName", 1, 50));
        information.setWebsiteUrl(optionalLength(contact, "WebsiteUrl", 1, 256));
        if (information.getStateOrRegion() == null && COUNTRIES_REQUIRING_STATE_OR_REGION
                .contains(information.getCountryCode().toUpperCase(Locale.ROOT))) {
            throw validation("StateOrRegion is required when CountryCode is "
                    + information.getCountryCode() + ".");
        }
        contactInformation.putForAccount(targetAccountId, PRIMARY_CONTACT_KEY, information);
    }

    public ContactInformation getContactInformation(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        return contactInformation.getForAccount(targetAccountId, PRIMARY_CONTACT_KEY)
                .orElseThrow(AccountService::contactInformationNotFound);
    }

    private String resolveTargetAccount(String callerAccountId, JsonNode request) {
        JsonNode accountIdNode = request == null ? null : request.get("AccountId");
        if (accountIdNode == null || accountIdNode.isNull()) {
            return callerAccountId;
        }
        if (!accountIdNode.isTextual()) {
            throw new AwsException("SerializationException",
                    "AccountId must be a string.", 400);
        }
        String requestedAccountId = accountIdNode.textValue();
        if (!ACCOUNT_ID.matcher(requestedAccountId).matches()) {
            throw validation("AccountId must be a 12 digit account ID.");
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
        if (managementAccount && requestedAccountId.equals(callerAccountId)) {
            throw validation("The management account cannot specify its own AccountId.");
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

    private static String requireContactType(JsonNode request) {
        String value = requireLength(request, "AlternateContactType", 1, 32);
        if (!CONTACT_TYPES.contains(value)) {
            throw validation("AlternateContactType must be BILLING, OPERATIONS, or SECURITY.");
        }
        return value;
    }

    private static String requirePattern(JsonNode request, String field, int min, int max, Pattern pattern) {
        String value = requireLength(request, field, min, max);
        if (!pattern.matcher(value).matches()) {
            throw validation(field + " does not satisfy the required pattern.");
        }
        return value;
    }

    private static String optionalLength(JsonNode request, String field, int min, int max) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return requireLength(request, field, min, max);
    }

    private static String requireLength(JsonNode request, String field, int min, int max) {
        String value = text(request, field);
        if (value == null || value.length() < min || value.length() > max) {
            throw validation(min == max
                    ? field + " must be exactly " + min + " characters."
                    : field + " must be between " + min + " and " + max + " characters.");
        }
        return value;
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
