package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A domain stays {@code Processing=true} until the readiness poller flips it. Mock mode and a
 * Floci with no reachable Docker daemon report {@code Processing=false} from the first read.
 */
class OpenSearchServiceTest {

    private OpenSearchService service;
    private OpenSearchDomainManager domainManager;
    private EmulatorConfig.OpenSearchServiceConfig osConfig;

    @BeforeEach
    void setUp() {
        domainManager = mock(OpenSearchDomainManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        osConfig = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.opensearch()).thenReturn(osConfig);
        when(osConfig.mock()).thenReturn(false);

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new OpenSearchService(AccountAwareStorageBackend.inMemory("000000000000"),
                config, regionResolver, domainManager);
    }

    @Test
    void createDomainKeepsProcessingTrueWhileContainerStarts() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);

        Domain domain = service.createDomain("docker-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertTrue(domain.isProcessing(),
                "the readiness poller flips Processing once the container answers");
        assertTrue(service.describeDomain("docker-domain").isProcessing());
        verify(domainManager).tryStartDomain(any());
    }

    @Test
    void createDomainWithoutDockerDaemonStillSucceedsAndReportsProcessingFalse() {
        // tryStartDomain() returns false when no Docker daemon is reachable. The domain record is
        // metadata, so the create still succeeds and the first describe already reports the
        // terminal state.
        when(domainManager.tryStartDomain(any())).thenReturn(false);

        Domain domain = service.createDomain("no-docker-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertFalse(domain.isProcessing());
        assertEquals("no-docker-domain", service.describeDomain("no-docker-domain").getDomainName());
        assertFalse(service.describeDomain("no-docker-domain").isProcessing());
    }

    @Test
    void mockModeKeepsProcessingFalse() {
        when(osConfig.mock()).thenReturn(true);

        Domain domain = service.createDomain("mock-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertFalse(domain.isProcessing());
        verify(domainManager, Mockito.never()).tryStartDomain(any());
    }
}
