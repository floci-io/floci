package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigStoredTagsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private AppConfigService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn(REGION);
        when(regions.getRegion()).thenReturn(REGION);
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        service = new AppConfigService(storageFactory, regions);
    }

    @Test
    void storedTagsReadTheEnvironmentTheArnNamesInItsApplicationAccountAndRegion() {
        String appId = service.createApplication(Map.of("Name", "app")).getId();
        String otherAppId = service.createApplication(Map.of("Name", "other")).getId();
        String envId = service.createEnvironment(appId, Map.of("Name", "env", "Tags", Map.of("Protected", "true")))
                .getId();
        String environment = "arn:aws:appconfig:" + REGION + ":" + ACCOUNT + ":application/" + appId
                + "/environment/" + envId;

        assertEquals(Map.of("Protected", "true"), service.storedTags(environment));
        assertEquals(Map.of(), service.storedTags(environment.replace(appId, otherAppId)));
        assertEquals(Map.of(), service.storedTags(environment.replace(ACCOUNT, "111111111111")));
        assertEquals(Map.of(), service.storedTags(environment.replace(REGION, "us-west-2")));
    }
}
