package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Account-level SES settings, extracted from {@link SesService} as part of the store-based domain
 * split. Reached through the {@code SesService} facade, which delegates here.
 *
 * <p>Owns the account sending-enabled flag ({@code accountSettingsStore}) and the VDM (Virtual
 * Deliverability Manager) attributes ({@code accountVdmStore}). Account suppression is account-level
 * too, but it shares {@code validateSuppressionReason} with the suppression list, so it lives in
 * {@link SesSuppressionService} instead.
 */
@ApplicationScoped
public class SesAccountService {

    private static final Logger LOG = Logger.getLogger(SesAccountService.class);

    private final StorageBackend<String, Boolean> accountSettingsStore;
    private final StorageBackend<String, AccountVdmAttributes> accountVdmStore;

    @Inject
    public SesAccountService(StorageFactory storageFactory) {
        this.accountSettingsStore = storageFactory.create("ses", "ses-account-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
        this.accountVdmStore = storageFactory.create("ses", "ses-account-vdm.json",
                new TypeReference<Map<String, AccountVdmAttributes>>() {});
    }

    SesAccountService(StorageBackend<String, Boolean> accountSettingsStore,
                      StorageBackend<String, AccountVdmAttributes> accountVdmStore) {
        this.accountSettingsStore = accountSettingsStore;
        this.accountVdmStore = accountVdmStore;
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
}
