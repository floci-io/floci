package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.VolumeOwnership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A domain stays {@code Processing=true} until the readiness poller flips it. Mock mode and a
 * Floci with no reachable Docker daemon report {@code Processing=false} from the first read.
 */
class OpenSearchServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";

    private OpenSearchService service;
    private OpenSearchDomainManager domainManager;
    private EmulatorConfig.OpenSearchServiceConfig osConfig;
    private InMemoryStorage<String, Domain> rawStore;
    private AccountAwareStorageBackend<Domain> domainStore;

    @BeforeEach
    void setUp() {
        domainManager = mock(OpenSearchDomainManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        osConfig = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.opensearch()).thenReturn(osConfig);
        when(osConfig.mock()).thenReturn(false);

        RegionResolver regionResolver = new RegionResolver("us-east-1", ACCOUNT);
        rawStore = new InMemoryStorage<>();
        domainStore = new AccountAwareStorageBackend<>(rawStore, null, ACCOUNT);
        service = new OpenSearchService(domainStore, config, regionResolver, domainManager);
    }

    @Test
    void createDomainKeepsProcessingTrueWhileContainerStarts() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);

        Domain domain = service.createDomain("docker-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertTrue(domain.isProcessing(),
                "the readiness poller flips Processing once the container answers");
        assertTrue(service.describeDomain("docker-domain", "us-east-1").isProcessing());
        verify(domainManager).tryStartDomain(any());
    }

    /**
     * The volume name and the ownership marker are persisted at creation, so deletion
     * acts on what was stamped instead of recomputing ownership from whatever
     * configuration holds at delete time.
     */
    @Test
    void createDomainStampsManagedVolumeNameAndOwnership() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);

        Domain domain = service.createDomain("stamped-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertEquals(VolumeOwnership.FLOCI_MANAGED, domain.getVolumeOwnership());
        assertTrue(domain.getDockerVolumeName().startsWith("floci-aws-opensearch-"),
                "the stamped name carries the current managed prefix");
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
        assertEquals("no-docker-domain", service.describeDomain("no-docker-domain", "us-east-1").getDomainName());
        assertFalse(service.describeDomain("no-docker-domain", "us-east-1").isProcessing());
    }

    @Test
    void mockModeKeepsProcessingFalse() {
        when(osConfig.mock()).thenReturn(true);

        Domain domain = service.createDomain("mock-domain", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        assertFalse(domain.isProcessing());
        verify(domainManager, Mockito.never()).tryStartDomain(any());
    }

    @Test
    void readinessPollerContinuesAfterOneReadinessFailure() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(domainManager.isReady(any()))
                .thenThrow(new RuntimeException("temporary readiness failure"))
                .thenReturn(true);

        service.createDomain("ready-recovery", "OpenSearch_2.11",
                null, null, null, "us-east-1");

        service.pollReadiness();
        assertTrue(service.describeDomain("ready-recovery", "us-east-1").isProcessing());

        service.pollReadiness();
        assertFalse(service.describeDomain("ready-recovery", "us-east-1").isProcessing());
        verify(domainManager, atLeast(2)).isReady(any());
    }

    @Test
    void shutdownLeavesDomainContainersRunningWhenRetentionIsEnabled() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(osConfig.keepRunningOnShutdown()).thenReturn(true);
        service.createDomain("kept-domain", "OpenSearch_2.11", null, null, null, "us-east-1");

        service.shutdown();

        verify(domainManager, Mockito.never()).stopDomain(any());
    }

    /**
     * A reset wipes every account's domain records, so it stops their containers first whatever
     * keep-running-on-shutdown says, or they would keep running, and keep their host ports, with no
     * record left of them.
     */
    @Test
    void resetStopsTheDomainsOfEveryAccountEvenWhenRetentionIsEnabled() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(osConfig.keepRunningOnShutdown()).thenReturn(true);
        service.createDomain("east-domain", "OpenSearch_2.11", null, null, null, "us-east-1");
        service.createDomain("west-domain", "OpenSearch_2.11", null, null, null, "us-west-2");
        domainStore.putForAccount(OTHER_ACCOUNT, "us-east-1::other-domain",
                domainOf(OTHER_ACCOUNT, "other-domain", "us-east-1"));

        Resettable resettable = assertInstanceOf(Resettable.class, service);
        resettable.beforeReset();

        ArgumentCaptor<Domain> stopped = ArgumentCaptor.forClass(Domain.class);
        verify(domainManager, times(3)).stopDomain(stopped.capture());
        assertEquals(Set.of("east-domain", "west-domain", "other-domain"),
                stopped.getAllValues().stream().map(Domain::getDomainName).collect(Collectors.toSet()));
        ArgumentCaptor<Domain> released = ArgumentCaptor.forClass(Domain.class);
        verify(domainManager, times(3)).removeDomainStorage(released.capture());
        assertEquals(Set.of("east-domain", "west-domain", "other-domain"),
                released.getAllValues().stream().map(Domain::getDomainName).collect(Collectors.toSet()));
    }

    @Test
    void resetStillStopsTheOtherDomainsWhenOneFailsToStop() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        service.createDomain("broken-domain", "OpenSearch_2.11", null, null, null, "us-east-1");
        service.createDomain("healthy-domain", "OpenSearch_2.11", null, null, null, "us-east-1");
        doThrow(new RuntimeException("docker stop failed"))
                .when(domainManager).stopDomain(argThat(d -> "broken-domain".equals(d.getDomainName())));

        Resettable resettable = assertInstanceOf(Resettable.class, service);
        resettable.beforeReset();

        verify(domainManager).stopDomain(argThat(d -> "healthy-domain".equals(d.getDomainName())));
        verify(domainManager).removeDomainStorage(argThat(d -> "healthy-domain".equals(d.getDomainName())));
    }

    @Test
    void resetInMockModeTouchesNoContainers() {
        when(osConfig.mock()).thenReturn(true);
        service.createDomain("mock-domain", "OpenSearch_2.11", null, null, null, "us-east-1");

        Resettable resettable = assertInstanceOf(Resettable.class, service);
        resettable.beforeReset();

        verify(domainManager, Mockito.never()).stopDomain(any());
        verify(domainManager, Mockito.never()).removeDomainStorage(any());
    }

    /** A poll that read the domains before the wipe must not write one back after it. */
    @Test
    void readinessPollerDoesNotRestoreADomainWipedByAReset() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        service.createDomain("polled-domain", "OpenSearch_2.11", null, null, null, "us-east-1");
        Resettable resettable = assertInstanceOf(Resettable.class, service);
        when(domainManager.isReady(any())).thenAnswer(invocation -> {
            resettable.beforeReset();
            domainStore.clear();
            resettable.clear();
            resettable.afterReset();
            return true;
        });

        service.pollReadiness();

        assertEquals(Set.of(), rawStore.keys());
    }

    @Test
    void readinessPollerResumesAfterAReset() {
        Resettable resettable = assertInstanceOf(Resettable.class, service);
        resettable.beforeReset();
        domainStore.clear();
        resettable.clear();
        resettable.afterReset();
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(domainManager.isReady(any())).thenReturn(true);
        service.createDomain("after-reset", "OpenSearch_2.11", null, null, null, "us-east-1");

        service.pollReadiness();

        assertFalse(service.describeDomain("after-reset", "us-east-1").isProcessing());
    }

    /** Before domains were keyed by Region, each sat under its bare name inside the account's partition. */
    @Test
    void legacyNameKeyedDomainIsServedOnlyInItsArnRegionAndRekeyedOnRead() {
        domainStore.putForAccount(ACCOUNT, "legacy-domain", legacyDomain("legacy-domain", "us-west-2"));

        AwsException crossRegion = assertThrows(AwsException.class,
                () -> service.describeDomain("legacy-domain", "us-east-1"));
        assertEquals("ResourceNotFoundException", crossRegion.getErrorCode());
        assertEquals(Set.of(ACCOUNT + "/legacy-domain"), rawStore.keys(),
                "a lookup from another Region must leave the record where it is");

        assertEquals("legacy-domain", service.describeDomain("legacy-domain", "us-west-2").getDomainName());
        assertEquals(Set.of(ACCOUNT + "/us-west-2::legacy-domain"), rawStore.keys());
        assertEquals(List.of("legacy-domain"),
                service.listDomainNames(null, "us-west-2").stream().map(Domain::getDomainName).toList());

        service.deleteDomain("legacy-domain", "us-west-2");
        assertEquals(Set.of(), rawStore.keys());
    }

    @Test
    void legacyNameKeyedDomainStillReservesItsNameInItsOwnRegionOnly() {
        domainStore.putForAccount(ACCOUNT, "legacy-domain", legacyDomain("legacy-domain", "us-west-2"));
        when(domainManager.tryStartDomain(any())).thenReturn(true);

        AwsException conflict = assertThrows(AwsException.class, () -> service.createDomain(
                "legacy-domain", "OpenSearch_2.11", null, null, null, "us-west-2"));
        assertEquals("ResourceAlreadyExistsException", conflict.getErrorCode());

        service.createDomain("legacy-domain", "OpenSearch_2.11", null, null, null, "us-east-1");
        assertEquals(Set.of(ACCOUNT + "/us-west-2::legacy-domain", ACCOUNT + "/us-east-1::legacy-domain"),
                rawStore.keys());
    }

    @Test
    void readinessPollerRekeysALegacyDomainInsteadOfDuplicatingIt() {
        Domain legacy = legacyDomain("legacy-domain", "us-west-2");
        legacy.setProcessing(true);
        legacy.setEndpoint("http://127.0.0.1:9400");
        domainStore.putForAccount(ACCOUNT, "legacy-domain", legacy);
        when(domainManager.isReady(any())).thenReturn(true);

        service.pollReadiness();

        assertEquals(Set.of(ACCOUNT + "/us-west-2::legacy-domain"), rawStore.keys());
        assertFalse(service.describeDomain("legacy-domain", "us-west-2").isProcessing());
    }

    /** Records older than per-account storage carry no account prefix at all; the ARN still names both. */
    @Test
    void preMultiAccountDomainIsRekeyedUnderItsAccountAndRegion() {
        rawStore.put("old-domain", legacyDomain("old-domain", "us-west-2"));

        assertEquals("old-domain", service.describeDomain("old-domain", "us-west-2").getDomainName());
        assertEquals(Set.of(ACCOUNT + "/us-west-2::old-domain"), rawStore.keys());
    }

    private static Domain legacyDomain(String name, String region) {
        return domainOf(ACCOUNT, name, region);
    }

    private static Domain domainOf(String accountId, String name, String region) {
        Domain domain = new Domain();
        domain.setDomainName(name);
        domain.setDomainId(accountId + "/" + name);
        domain.setAccountId(accountId);
        domain.setArn("arn:aws:es:" + region + ":" + accountId + ":domain/" + name);
        domain.setEngineVersion("OpenSearch_2.11");
        return domain;
    }
}
