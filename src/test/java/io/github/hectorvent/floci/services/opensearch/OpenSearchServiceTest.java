package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.opensearch.model.ClusterConfig;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.VolumeOwnership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A domain stays {@code Processing=true} until the readiness poller flips it. Mock mode and a
 * Floci with no reachable Docker daemon report {@code Processing=false} from the first read.
 *
 * <p>Domains are scoped to an account and a region: the same name can exist in two regions,
 * and describe, update, delete and list only ever see the caller's region.
 */
class OpenSearchServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";

    private OpenSearchService service;
    private AccountAwareStorageBackend<Domain> domainStore;
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

        RegionResolver regionResolver = new RegionResolver(US_EAST_1, ACCOUNT);
        domainStore = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new OpenSearchService(domainStore, config, regionResolver, domainManager);
    }

    @Test
    void createDomainKeepsProcessingTrueWhileContainerStarts() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);

        Domain domain = service.createDomain("docker-domain", "OpenSearch_2.11",
                null, null, null, US_EAST_1);

        assertTrue(domain.isProcessing(),
                "the readiness poller flips Processing once the container answers");
        assertTrue(service.describeDomain("docker-domain", US_EAST_1).isProcessing());
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
                null, null, null, US_EAST_1);

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
                null, null, null, US_EAST_1);

        assertFalse(domain.isProcessing());
        assertEquals("no-docker-domain", service.describeDomain("no-docker-domain", US_EAST_1).getDomainName());
        assertFalse(service.describeDomain("no-docker-domain", US_EAST_1).isProcessing());
    }

    @Test
    void mockModeKeepsProcessingFalse() {
        when(osConfig.mock()).thenReturn(true);

        Domain domain = service.createDomain("mock-domain", "OpenSearch_2.11",
                null, null, null, US_EAST_1);

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
                null, null, null, US_EAST_1);

        service.pollReadiness();
        assertTrue(service.describeDomain("ready-recovery", US_EAST_1).isProcessing());

        service.pollReadiness();
        assertFalse(service.describeDomain("ready-recovery", US_EAST_1).isProcessing());
        verify(domainManager, atLeast(2)).isReady(any());
    }

    @Test
    void shutdownLeavesDomainContainersRunningWhenRetentionIsEnabled() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(osConfig.keepRunningOnShutdown()).thenReturn(true);
        service.createDomain("kept-domain", "OpenSearch_2.11", null, null, null, US_EAST_1);

        service.shutdown();

        verify(domainManager, Mockito.never()).stopDomain(any());
    }

    @Test
    void sameNameInTwoRegionsCoexistsAndEachArnCarriesItsRegion() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);

        Domain east = service.createDomain("dual-region", "OpenSearch_2.11", null, null, null, US_EAST_1);
        Domain west = service.createDomain("dual-region", "OpenSearch_2.11", null, null, null, EU_WEST_1);

        assertEquals(AwsArnUtils.Arn.of("es", US_EAST_1, ACCOUNT, "domain/dual-region").toString(),
                east.getArn());
        assertEquals(AwsArnUtils.Arn.of("es", EU_WEST_1, ACCOUNT, "domain/dual-region").toString(),
                west.getArn());
        assertEquals(US_EAST_1, service.describeDomain("dual-region", US_EAST_1).getRegion());
        assertEquals(EU_WEST_1, service.describeDomain("dual-region", EU_WEST_1).getRegion());
    }

    @Test
    void sameNameInTheSameRegionStillConflicts() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        service.createDomain("occupied-name", "OpenSearch_2.11", null, null, null, US_EAST_1);

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createDomain(
                "occupied-name", "OpenSearch_2.11", null, null, null, US_EAST_1));

        assertEquals("ResourceAlreadyExistsException", duplicate.getErrorCode());
    }

    @Test
    void describeFromAnotherRegionDoesNotSeeTheDomain() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        service.createDomain("east-only", "OpenSearch_2.11", null, null, null, US_EAST_1);

        AwsException absent = assertThrows(AwsException.class,
                () -> service.describeDomain("east-only", EU_WEST_1));

        assertEquals("ResourceNotFoundException", absent.getErrorCode());
    }

    @Test
    void updateInOneRegionLeavesTheOtherRegionUntouched() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        service.createDomain("split-update", "OpenSearch_2.11", null, null, null, US_EAST_1);
        service.createDomain("split-update", "OpenSearch_2.11", null, null, null, EU_WEST_1);

        ClusterConfig eastConfig = new ClusterConfig();
        eastConfig.setInstanceCount(5);
        service.updateDomainConfig("split-update", null, eastConfig, null, US_EAST_1);

        assertEquals(5, service.describeDomain("split-update", US_EAST_1).getClusterConfig().getInstanceCount());
        assertEquals(1, service.describeDomain("split-update", EU_WEST_1).getClusterConfig().getInstanceCount());
    }

    @Test
    void deleteInOneRegionLeavesTheOtherRegionIntact() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        service.createDomain("split-delete", "OpenSearch_2.11", null, null, null, US_EAST_1);
        service.createDomain("split-delete", "OpenSearch_2.11", null, null, null, EU_WEST_1);

        Domain deleted = service.deleteDomain("split-delete", US_EAST_1);

        assertTrue(deleted.isDeleted());
        assertThrows(AwsException.class, () -> service.describeDomain("split-delete", US_EAST_1));
        assertEquals(EU_WEST_1, service.describeDomain("split-delete", EU_WEST_1).getRegion());
    }

    @Test
    void listDomainNamesReturnsOnlyTheRequestRegionsDomains() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        service.createDomain("east-listed", "OpenSearch_2.11", null, null, null, US_EAST_1);
        service.createDomain("west-listed", "OpenSearch_2.11", null, null, null, EU_WEST_1);

        List<Domain> east = service.listDomainNames(null, US_EAST_1);
        List<Domain> west = service.listDomainNames(null, EU_WEST_1);

        assertTrue(east.stream().anyMatch(d -> d.getDomainName().equals("east-listed")));
        assertFalse(east.stream().anyMatch(d -> d.getDomainName().equals("west-listed")));
        assertTrue(west.stream().anyMatch(d -> d.getDomainName().equals("west-listed")));
        assertFalse(west.stream().anyMatch(d -> d.getDomainName().equals("east-listed")));
    }

    /**
     * A record persisted before domains were keyed by region sits under the bare name; its ARN,
     * which has always carried the region, decides which region may still see it, and the
     * lookup moves it onto the region-scoped key.
     */
    @Test
    void preRegionScopingRecordResolvesByItsArnRegionAndMigrates() {
        Domain legacy = new Domain();
        legacy.setDomainName("legacy-record");
        legacy.setAccountId(ACCOUNT);
        legacy.setArn(AwsArnUtils.Arn.of("es", US_EAST_1, ACCOUNT, "domain/legacy-record").toString());
        domainStore.putForAccount(ACCOUNT, "legacy-record", legacy);

        Domain resolved = service.describeDomain("legacy-record", US_EAST_1);

        assertEquals(US_EAST_1, AwsArnUtils.parse(resolved.getArn()).region());
        assertFalse(domainStore.keysForAccount(ACCOUNT).contains("legacy-record"),
                "the lookup migrates the bare-name key onto the region-scoped key");
        assertTrue(domainStore.keysForAccount(ACCOUNT).contains(US_EAST_1 + "::legacy-record"));
        assertThrows(AwsException.class, () -> service.describeDomain("legacy-record", EU_WEST_1));
    }

    /**
     * A pre-scoping record whose ARN names another region is invisible from, and does not
     * collide with, a same-named create in this region.
     */
    @Test
    void preRegionScopingRecordOfAnotherRegionDoesNotConflictWithACreateHere() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        Domain legacy = new Domain();
        legacy.setDomainName("legacy-elsewhere");
        legacy.setAccountId(ACCOUNT);
        legacy.setArn(AwsArnUtils.Arn.of("es", EU_WEST_1, ACCOUNT, "domain/legacy-elsewhere").toString());
        domainStore.putForAccount(ACCOUNT, "legacy-elsewhere", legacy);

        Domain created = service.createDomain("legacy-elsewhere", "OpenSearch_2.11",
                null, null, null, US_EAST_1);

        assertEquals(US_EAST_1, AwsArnUtils.parse(created.getArn()).region());
        assertEquals(EU_WEST_1,
                AwsArnUtils.parse(service.describeDomain("legacy-elsewhere", EU_WEST_1).getArn()).region());
    }

    @Test
    void pollerFlipsReadinessUnderTheScopedKey() {
        when(domainManager.tryStartDomain(any())).thenReturn(true);
        when(domainManager.isReady(any())).thenReturn(true);
        service.createDomain("poller-scoped", "OpenSearch_2.11", null, null, null, EU_WEST_1);

        service.pollReadiness();

        assertFalse(service.describeDomain("poller-scoped", EU_WEST_1).isProcessing());
        assertTrue(domainStore.keysForAccount(ACCOUNT).contains(EU_WEST_1 + "::poller-scoped"));
    }
}
