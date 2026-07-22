package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontServiceTest {

    private static final String ACCOUNT = "000000000000";

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(domainSuffix);

        return new CloudFrontService(storageFactory, config);
    }

    @Test
    void createDistributionUsesDefaultDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.net"),
                "Expected default suffix, got: " + dist.getDomainName());
    }

    @Test
    void createDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    @Test
    void createStreamingDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        StreamingDistribution sd = service.createStreamingDistribution(new StreamingDistribution());

        assertTrue(sd.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + sd.getDomainName());
    }

    @Test
    void findByHostMatchesDomainNameAndAliasIgnoringPort() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setAliases(List.of("console.example.test"));
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        dist = service.createDistribution(dist, Map.of());

        // Matches the assigned CloudFront domain name, with or without a port.
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName()).getId());
        assertEquals(dist.getId(), service.findByHost(dist.getDomainName() + ":4566").getId());
        // Matches a configured alias, case-insensitively and ignoring the port.
        assertEquals(dist.getId(), service.findByHost("console.example.test").getId());
        assertEquals(dist.getId(), service.findByHost("CONSOLE.EXAMPLE.TEST:8443").getId());
        // No match for an unrelated host.
        assertNull(service.findByHost("unrelated.example.test"));
        assertNull(service.findByHost(null));
    }
}
