package io.floci.aws.services.ecs;

import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ecs.model.CapacityProvider;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity providers stored under their bare name, and account settings stored without a region, from
 * before their keys carried the region: each stays in the region it belongs to and is re-filed on its
 * next write.
 */
class EcsCapacityProviderLegacyKeyTest {

    private static final String REGION = EcsServiceFixtures.REGION;
    private static final String OTHER = "eu-west-1";
    private static final String NAME = "legacy-cp";
    private static final String LEGACY_ARN =
            "arn:aws:ecs:" + REGION + ":000000000000:capacity-provider/" + NAME;
    private static final String ROOT = "arn:aws:iam::000000000000:root";
    private static final String SETTING = "awsvpcTrunking";

    @Test
    void aLegacyProviderStaysInItsRegionAndIsRefiledOnItsNextWrite() {
        AccountAwareStorageBackend<CapacityProvider> providers =
                AccountAwareStorageBackend.inMemory(EcsServiceFixtures.ACCOUNT);
        providers.put(NAME, legacyProvider());
        EcsService service = EcsServiceFixtures.builder()
                .storageFactory(new InMemoryStorageFactory(EcsServiceFixtures.ACCOUNT)
                        .withStore("ecs-capacity-providers.json", providers))
                .build();

        assertEquals(List.of(LEGACY_ARN), arns(service.describeCapacityProviders(List.of(NAME), REGION)));
        assertTrue(service.describeCapacityProviders(List.of(NAME), OTHER).isEmpty());
        CapacityProvider other = service.createCapacityProvider(NAME, Map.of(), Map.of(), OTHER);
        service.updateCapacityProvider(NAME, Map.of(), REGION);

        assertEquals(Set.of(REGION + "::" + NAME, OTHER + "::" + NAME), Set.copyOf(providers.keys()));
        assertEquals(List.of(LEGACY_ARN), arns(service.describeCapacityProviders(List.of(NAME), REGION)));
        assertEquals(List.of(other.getCapacityProviderArn()),
                arns(service.describeCapacityProviders(List.of(NAME), OTHER)));
    }

    @Test
    void aLegacyAccountSettingIsTheDefaultRegionsAndIsRefiledOnItsNextWrite() {
        AccountAwareStorageBackend<String> settings = AccountAwareStorageBackend.inMemory(EcsServiceFixtures.ACCOUNT);
        settings.put(ROOT + "::" + SETTING, "enabled");
        EcsService service = EcsServiceFixtures.builder()
                .storageFactory(new InMemoryStorageFactory(EcsServiceFixtures.ACCOUNT)
                        .withStore("ecs-account-settings.json", settings))
                .build();

        assertEquals(List.of("enabled"), values(service, REGION));
        assertTrue(values(service, OTHER).isEmpty());

        service.putAccountSetting(SETTING, "disabled", null, REGION);

        assertEquals(List.of(REGION + "::" + ROOT + "::" + SETTING), List.copyOf(settings.keys()));
        assertEquals(List.of("disabled"), values(service, REGION));
    }

    private static CapacityProvider legacyProvider() {
        CapacityProvider cp = new CapacityProvider();
        cp.setName(NAME);
        cp.setCapacityProviderArn(LEGACY_ARN);
        cp.setStatus("ACTIVE");
        cp.setType("EC2_AUTOSCALING");
        return cp;
    }

    private static List<String> arns(List<CapacityProvider> providers) {
        return providers.stream().map(CapacityProvider::getCapacityProviderArn).toList();
    }

    private static List<String> values(EcsService service, String region) {
        return service.listAccountSettings(SETTING, null, null, false, null, null, region).settings().stream()
                .map(EcsService.AccountSetting::value)
                .toList();
    }
}
