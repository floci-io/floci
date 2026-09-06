package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class AccountService implements Resettable {
    private static final String ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL = "account.amazonaws.com";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern EMAIL = Pattern.compile("\\s*[\\w+=.#|!&-]+@[\\w.-]+\\.[\\w]+\\s*");
    private static final Pattern PHONE = Pattern.compile("[\\s0-9()+-]+");
    private static final Set<String> CONTACT_TYPES = Set.of("BILLING", "OPERATIONS", "SECURITY");

    private final AccountAwareStorageBackend<AlternateContact> contacts;
    private final OrganizationsService organizationsService;

    @Inject
    public AccountService(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this.contacts = storageFactory.create("account", "account-alternate-contacts.json",
                new TypeReference<Map<String, AlternateContact>>() {});
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
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The alternate contact does not exist for the specified account and contact type.", 404));
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

    private static String requireLength(JsonNode request, String field, int min, int max) {
        String value = text(request, field);
        if (value == null || value.length() < min || value.length() > max) {
            throw validation(field + " must be between " + min + " and " + max + " characters.");
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
}
