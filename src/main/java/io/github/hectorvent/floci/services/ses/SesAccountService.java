package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Account-level SES settings, extracted from {@link SesService} as the second step of the
 * store-based domain split. Reached through the {@code SesService} facade, which
 * delegates here.
 *
 * <p>Scope for now is the account sending-enabled flag ({@code accountSettingsStore}), a clean leaf
 * with no internal callers. Account suppression and VDM belong to this domain too, but they share
 * the {@code validateSuppressionReason} helper with the suppression-list domain, so they extract
 * together with {@code SesSuppressionService} in a later step rather than being split off here.
 */
@ApplicationScoped
public class SesAccountService {

    private static final Logger LOG = Logger.getLogger(SesAccountService.class);

    private final StorageBackend<String, Boolean> accountSettingsStore;

    @Inject
    public SesAccountService(StorageFactory storageFactory) {
        this.accountSettingsStore = storageFactory.create("ses", "ses-account-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
    }

    SesAccountService(StorageBackend<String, Boolean> accountSettingsStore) {
        this.accountSettingsStore = accountSettingsStore;
    }

    public boolean isAccountSendingEnabled(String region) {
        return accountSettingsStore.get("sending::" + region).orElse(true);
    }

    public void setAccountSendingEnabled(String region, boolean enabled) {
        accountSettingsStore.put("sending::" + region, enabled);
        LOG.infov("Updated account sending enabled for region {0}: {1}", region, enabled);
    }
}
