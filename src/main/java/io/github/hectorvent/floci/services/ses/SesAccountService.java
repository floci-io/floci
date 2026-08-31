package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.AccountDetails;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Account-level SES settings, extracted from {@link SesService} as part of the store-based domain
 * split. Reached through the {@code SesService} facade, which delegates here.
 *
 * <p>Owns the account sending-enabled flag ({@code accountSettingsStore}), the VDM (Virtual
 * Deliverability Manager) attributes ({@code accountVdmStore}), and the provisioning details
 * ({@code accountDetailsStore}). Account suppression is account-level too, but it shares
 * {@code validateSuppressionReason} with the suppression list, so it lives in
 * {@link SesSuppressionService} instead.
 */
@ApplicationScoped
public class SesAccountService {

    private static final Logger LOG = Logger.getLogger(SesAccountService.class);

    private final StorageBackend<String, Boolean> accountSettingsStore;
    private final StorageBackend<String, AccountVdmAttributes> accountVdmStore;
    private final StorageBackend<String, AccountDetails> accountDetailsStore;

    @Inject
    public SesAccountService(StorageFactory storageFactory) {
        this.accountSettingsStore = storageFactory.create("ses", "ses-account-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
        this.accountVdmStore = storageFactory.create("ses", "ses-account-vdm.json",
                new TypeReference<Map<String, AccountVdmAttributes>>() {});
        this.accountDetailsStore = storageFactory.create("ses", "ses-account-details.json",
                new TypeReference<Map<String, AccountDetails>>() {});
    }

    SesAccountService(StorageBackend<String, Boolean> accountSettingsStore,
                      StorageBackend<String, AccountVdmAttributes> accountVdmStore,
                      StorageBackend<String, AccountDetails> accountDetailsStore) {
        this.accountSettingsStore = accountSettingsStore;
        this.accountVdmStore = accountVdmStore;
        this.accountDetailsStore = accountDetailsStore;
    }

    public boolean isAccountSendingEnabled(String region) {
        return accountSettingsStore.get("sending::" + region).orElse(true);
    }

    public void setAccountSendingEnabled(String region, boolean enabled) {
        accountSettingsStore.put("sending::" + region, enabled);
        LOG.infov("Updated account sending enabled for region {0}: {1}", region, enabled);
    }

    // VDM (Virtual Deliverability Manager) is opt-in and per region: GetAccount omits VdmAttributes
    // entirely until PutAccountVdmAttributes is called for the region, so this returns empty when the
    // region was never configured. The whole tuple is stored under one region key so GetAccount never
    // observes a partially updated state.
    public Optional<AccountVdmAttributes> findAccountVdmAttributes(String region) {
        return accountVdmStore.get(accountVdmKey(region));
    }

    public void putAccountVdmAttributes(String region, AccountVdmAttributes vdm) {
        accountVdmStore.put(accountVdmKey(region), vdm);
        LOG.infov("Updated account VDM attributes for region {0}: enabled={1}", region, vdm.vdmEnabled());
    }

    private static String accountVdmKey(String region) {
        return "account-vdm::" + region;
    }

    // Provisioning details are opt-in and per region, like VDM: GetAccount omits Details entirely
    // until PutAccountDetails is called for the region, so this returns empty for an unconfigured
    // region.
    public Optional<AccountDetails> findAccountDetails(String region) {
        return accountDetailsStore.get(accountDetailsKey(region));
    }

    // Owns the domain behavior for account details so it can't be bypassed by another caller: it
    // validates the modeled constraints, mints the synthetic review/case (Floci has no sandbox and
    // runs no production-access review, so the review is always GRANTED), and persists the record. The
    // controller only parses the REST JSON and rejects wrong JSON types before calling this.
    public AccountDetails putAccountDetails(String region, String mailType, String websiteUrl,
                                            String contactLanguage, String useCaseDescription,
                                            List<String> additionalContacts, boolean productionAccessEnabled) {
        validateAccountDetails(mailType, websiteUrl, contactLanguage, useCaseDescription, additionalContacts);
        AccountDetails details = new AccountDetails(mailType, websiteUrl, contactLanguage,
                useCaseDescription, additionalContacts, productionAccessEnabled, "GRANTED",
                syntheticCaseId(region, mailType, websiteUrl));
        accountDetailsStore.put(accountDetailsKey(region), details);
        LOG.infov("Updated account details for region {0}: mailType={1}, productionAccess={2}",
                region, mailType, productionAccessEnabled);
        return details;
    }

    private static String accountDetailsKey(String region) {
        return "account-details::" + region;
    }

    // A stable, region-scoped synthetic case id (kept constant across GetAccount reads for the same
    // input). Floci has no real review case; the value only matches AWS's numeric-string shape.
    private static String syntheticCaseId(String region, String mailType, String websiteUrl) {
        return String.valueOf(1_000_000_000L
                + Math.floorMod((region + "::" + mailType + "::" + websiteUrl).hashCode(), 1_000_000_000));
    }

    private static final Set<String> MAIL_TYPES = Set.of("MARKETING", "TRANSACTIONAL");
    private static final Set<String> CONTACT_LANGUAGES = Set.of("EN", "JA");
    // Modeled constraint bounds (from the SES v2 SDK model, probe-confirmed against real AWS).
    private static final int WEBSITE_URL_MAX = 1000;
    private static final int USE_CASE_DESCRIPTION_MAX = 5000;
    private static final int CONTACTS_MAX = 4;
    private static final int CONTACT_MIN = 6;
    private static final int CONTACT_MAX = 254;
    private static final Pattern CONTACT_PATTERN = Pattern.compile("^(.+)@(.+)$");

    // AWS aggregates every modeled-constraint violation (null / enum / length / list / element) into a
    // single "N validation errors detected: ..." response. Floci mirrors the message text and count;
    // the order across fields is deterministic here (declared order) rather than AWS's internal order.
    // The "Url contains invalid format" business check is separate and only runs once the modeled
    // constraints pass (probe-confirmed: a well-formed-length but non-URL value returns it standalone).
    private static void validateAccountDetails(String mailType, String websiteUrl, String contactLanguage,
                                               String useCaseDescription, List<String> additionalContacts) {
        List<String> errors = new ArrayList<>();
        if (mailType == null) {
            errors.add(constraint("mailType", "Member must not be null"));
        } else if (!MAIL_TYPES.contains(mailType)) {
            errors.add(constraint("mailType", "Member must satisfy enum value set: [MARKETING, TRANSACTIONAL]"));
        }
        if (websiteUrl == null) {
            errors.add(constraint("websiteURL", "Member must not be null"));
        } else if (websiteUrl.isEmpty()) {
            errors.add(constraint("websiteURL", "Member must have length greater than or equal to 1"));
        } else if (websiteUrl.length() > WEBSITE_URL_MAX) {
            errors.add(constraint("websiteURL", "Member must have length less than or equal to " + WEBSITE_URL_MAX));
        }
        if (contactLanguage != null && !CONTACT_LANGUAGES.contains(contactLanguage)) {
            errors.add(constraint("contactLanguage", "Member must satisfy enum value set: [EN, JA]"));
        }
        if (useCaseDescription != null && useCaseDescription.length() > USE_CASE_DESCRIPTION_MAX) {
            errors.add(constraint("useCaseDescription",
                    "Member must have length less than or equal to " + USE_CASE_DESCRIPTION_MAX));
        }
        if (additionalContacts != null) {
            if (additionalContacts.isEmpty()) {
                errors.add(constraint("additionalContactEmailAddresses",
                        "Member must have length greater than or equal to 1"));
            } else if (additionalContacts.size() > CONTACTS_MAX) {
                errors.add(constraint("additionalContactEmailAddresses",
                        "Member must have length less than or equal to " + CONTACTS_MAX));
            }
            for (String contact : additionalContacts) {
                if (contact == null || contact.length() < CONTACT_MIN || contact.length() > CONTACT_MAX
                        || !CONTACT_PATTERN.matcher(contact).matches()) {
                    // AWS reports all three element sub-constraints together regardless of which failed.
                    errors.add(constraint("additionalContactEmailAddresses",
                            "Member must satisfy constraint: [Member must have length less than or equal to "
                                    + CONTACT_MAX + ", Member must have length greater than or equal to "
                                    + CONTACT_MIN + ", Member must satisfy regular expression pattern: ^(.+)@(.+)$]"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new AwsException("BadRequestException",
                    errors.size() + " validation error" + (errors.size() > 1 ? "s" : "")
                            + " detected: " + String.join("; ", errors), 400);
        }
        if (!isValidUrl(websiteUrl)) {
            throw new AwsException("BadRequestException", "Url contains invalid format", 400);
        }
    }

    private static String constraint(String field, String detail) {
        return "Value at '" + field + "' failed to satisfy constraint: " + detail;
    }

    private static boolean isValidUrl(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
