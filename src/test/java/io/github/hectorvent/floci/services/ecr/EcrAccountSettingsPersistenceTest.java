package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.RegistrySettings;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class EcrAccountSettingsPersistenceTest {
    private static final String REGION = "eu-west-2";
    private static final String ACCOUNT = "741682900099";
    private static final String KEY = REGION + "::" + ACCOUNT;

    @Test
    void legacySettingsLoadAndAccountValuesSurviveSerializationAndOtherUpdates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryStorage<String, RegistrySettings> store = new InMemoryStorage<>();
        store.put(KEY, mapper.readValue("{\"scanType\":\"ENHANCED\",\"policyText\":\"{}\"}", RegistrySettings.class));
        EcrService service = service(store);
        assertEquals("DISABLED", service.getAccountSetting("BLOB_MOUNTING", REGION));
        service.putAccountSetting("BLOB_MOUNTING", "ENABLED", REGION);
        assertEquals("ENHANCED", service.registryScanningConfiguration(REGION).getScanType());
        assertEquals("{}", service.getRegistryPolicy(REGION));

        String saved = mapper.writeValueAsString(store.get(KEY).orElseThrow());
        InMemoryStorage<String, RegistrySettings> reloadedStore = new InMemoryStorage<>();
        reloadedStore.put(KEY, mapper.readValue(saved, RegistrySettings.class));
        EcrService reloaded = service(reloadedStore);
        assertEquals("ENABLED", reloaded.getAccountSetting("BLOB_MOUNTING", REGION));
        reloaded.putRegistryScanningConfiguration("BASIC", List.of(), REGION);
        reloaded.deleteRegistryPolicy(REGION);
        assertEquals("ENABLED", reloaded.getAccountSetting("BLOB_MOUNTING", REGION));
    }

    private EcrService service(InMemoryStorage<String, RegistrySettings> settings) {
        return new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                settings, new InMemoryStorage<>(), mock(EcrRegistryManager.class), mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
    }
}
