package io.floci.aws.services.opensearch;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.opensearch.model.ClusterConfig;
import io.floci.aws.services.opensearch.model.Domain;
import io.floci.aws.services.opensearch.model.VolumeOwnership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private static final String OTHER_ACCOUNT = "111111111111";

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
     * Two concurrent creates of one name start one container: the second is refused as the name
     * already exists, rather than starting a second container whose domain overwrites the first
     * and leaves the first container running with nothing to stop it.
     */
    @Test
    void concurrentCreatesOfOneNameStartOneContainerAndRefuseTheSecond() throws Exception {
        CountDownLatch firstStarting = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        when(domainManager.tryStartDomain(any())).thenAnswer(invocation -> {
            if (starts.incrementAndGet() == 1) {
                firstStarting.countDown();
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "the test never released the first start");
            } else {
                secondStarting.countDown();
            }
            return true;
        });

        CompletableFuture<Domain> first = CompletableFuture.supplyAsync(() -> service.createDomain(
                "raced-domain", "OpenSearch_2.11", null, null, null, US_EAST_1));
        assertTrue(firstStarting.await(10, TimeUnit.SECONDS), "the first create never started its container");
        CompletableFuture<Domain> second = CompletableFuture.supplyAsync(() -> service.createDomain(
                "raced-domain", "OpenSearch_2.11", null, null, null, US_EAST_1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (secondStarting.getCount() > 0 && !second.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        releaseFirst.countDown();

        first.get(10, TimeUnit.SECONDS);
        ExecutionException refused = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
        AwsException cause = assertInstanceOf(AwsException.class, refused.getCause());
        assertEquals("ResourceAlreadyExistsException", cause.getErrorCode());
        assertEquals(1, starts.get());
    }

    @Test
    void createDomainWhoseContainerFailedToStartCanBeRetried() {
        when(domainManager.tryStartDomain(any()))
                .thenThrow(new IllegalStateException("container failed to start"))
                .thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.createDomain(
                "retried-domain", "OpenSearch_2.11", null, null, null, US_EAST_1));
        Domain domain = service.createDomain("retried-domain", "OpenSearch_2.11", null, null, null, US_EAST_1);

        assertEquals("retried-domain", domain.getDomainName());
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

    /**
     * IAM's tag lookup reads the tags of the domain whose ARN it is given, as tag calls leave them, a tag call giving
     * the domain a new map rather than changing the one a reader holds, and those of one stored under its name from
     * before keys named the Region, in place, without migrating it; nothing for an ARN of another account or Region, or
     * of no domain.
     */
    @Test
    void storedTagsAreTheTagsOfTheDomainTheArnNamesAndTaggingPutsANewMap() {
        when(domainManager.tryStartDomain(any())).thenReturn(false);
        String arn = service.createDomain("tagged", "OpenSearch_2.11", null, null, Map.of("env", "dev"), US_EAST_1)
                .getArn();
        Map<String, String> held = service.listTags(arn);
        service.addTags(arn, Map.of("team", "data"));
        service.removeTags(arn, List.of("env"));
        String legacyArn = AwsArnUtils.Arn.of("es", US_EAST_1, ACCOUNT, "domain/legacy").toString();
        Domain legacy = new Domain();
        legacy.setDomainName("legacy");
        legacy.setArn(legacyArn);
        legacy.setTags(Map.of("tier", "web"));
        domainStore.putForAccount(ACCOUNT, "legacy", legacy);

        assertEquals(Map.of("env", "dev"), held);
        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertEquals(Map.of("tier", "web"), service.storedTags(legacyArn));
        assertTrue(domainStore.getForAccount(ACCOUNT, "legacy").isPresent());
        assertEquals(Map.of(), service.storedTags(arn.replace(":" + ACCOUNT + ":", ":111122223333:")));
        assertEquals(Map.of(), service.storedTags(arn.replace(":" + US_EAST_1 + ":", ":" + EU_WEST_1 + ":")));
        assertEquals(Map.of(), service.storedTags(arn + "0"));
        assertEquals(Map.of(), service.storedTags("arn:aws:s3:::tagged"));
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

    /**
     * Each persisted domain, in any account, is processing again at once, and its container starts in the background,
     * in its own account and Region; the poller leaves it alone while its restore runs, and reports it ready after.
     */
    @Test
    void restoringStartsEachDomainsContainerAgain() {
        storedDomain("home", ACCOUNT, US_EAST_1);
        Domain away = storedDomain("away", OTHER_ACCOUNT, EU_WEST_1);
        away.setAccountId(null);
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            Domain domain = start.getArgument(0);
            assertTrue(domain.isProcessing());
            // Its record still names the endpoint it had before the restart.
            service.pollReadiness();
            verify(domainManager, Mockito.never()).isReady(
                    Mockito.argThat(polled -> polled != null && polled.getDomainName().equals(domain.getDomainName())));
            domain.setEndpoint("http://new-" + domain.getDomainName() + ":9200");
            return true;
        });

        service.restorePersistedRuntime().join();

        Domain home = domainStore.getForAccount(ACCOUNT, US_EAST_1 + "::home").orElseThrow();
        assertTrue(home.isProcessing());
        assertEquals("http://new-home:9200", home.getEndpoint());
        assertEquals("http://new-away:9200",
                domainStore.getForAccount(OTHER_ACCOUNT, EU_WEST_1 + "::away").orElseThrow().getEndpoint());
        assertTrue(domainStore.getForAccount(ACCOUNT, EU_WEST_1 + "::away").isEmpty(),
                "the record stays in its own account");
        when(domainManager.isReady(any())).thenReturn(true);
        service.pollReadiness();
        assertFalse(domainStore.getForAccount(ACCOUNT, US_EAST_1 + "::home").orElseThrow().isProcessing());
    }

    /** A domain whose container outlived Floci keeps it, and starts none. */
    @Test
    void aDomainKeepsItsSurvivingContainer() {
        storedDomain("kept", ACCOUNT, US_EAST_1);
        when(domainManager.adoptRunningDomain(any())).thenReturn(true);

        service.restorePersistedRuntime().join();

        verify(domainManager, Mockito.never()).tryRestartDomain(any());
        assertTrue(service.describeDomain("kept", US_EAST_1).isProcessing(), "until the poller sees it answer");
    }

    /** With no Docker daemon reachable, a domain has no container behind it, as one created then has. */
    @Test
    void withNoDockerDaemonADomainReportsNoContainer() {
        storedDomain("no-docker", ACCOUNT, US_EAST_1);
        when(domainManager.tryRestartDomain(any())).thenReturn(false);

        service.restorePersistedRuntime().join();

        Domain domain = service.describeDomain("no-docker", US_EAST_1);
        assertFalse(domain.isProcessing());
        assertTrue(domain.getEndpoint().startsWith("search-no-docker-"), domain.getEndpoint());
    }

    /**
     * A domain whose container cannot start has none behind it, and the failure is thrown once every other domain
     * has been restored.
     */
    @Test
    void aDomainWhoseContainerCannotStartFailsTheRestore() {
        storedDomain("broken", ACCOUNT, US_EAST_1);
        storedDomain("fine", ACCOUNT, US_EAST_1);
        IllegalStateException cause = new IllegalStateException("image not found");
        Mockito.doReturn(true).when(domainManager).tryRestartDomain(any());
        Mockito.doThrow(cause).when(domainManager).tryRestartDomain(
                Mockito.argThat(domain -> domain != null && "broken".equals(domain.getDomainName())));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertEquals("Could not restore OpenSearch domain broken of account " + ACCOUNT,
                thrown.getCause().getMessage());
        assertSame(cause, thrown.getCause().getCause());
        Domain broken = service.describeDomain("broken", US_EAST_1);
        assertFalse(broken.isProcessing());
        assertTrue(broken.getEndpoint().startsWith("search-broken-"), broken.getEndpoint());
        assertTrue(service.describeDomain("fine", US_EAST_1).isProcessing());
    }

    /**
     * A domain deleted while its container starts reads Deleted, as AWS reports a domain being deleted, and its name
     * stays taken; once the start is through its container and data go, and so does the domain.
     */
    @Test
    void aDomainDeletedWhileItsContainerStartsIsDeletedOnceTheStartIsThrough() {
        storedDomain("deleted", ACCOUNT, US_EAST_1);
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            assertTrue(service.deleteDomain("deleted", US_EAST_1).isDeleted());
            assertTrue(service.describeDomain("deleted", US_EAST_1).isDeleted());
            AwsException again = assertThrows(AwsException.class,
                    () -> service.createDomain("deleted", "OpenSearch_2.11", null, null, null, US_EAST_1));
            assertEquals("ResourceAlreadyExistsException", again.getErrorCode());
            return true;
        });

        service.restorePersistedRuntime().join();

        verify(domainManager).removeDomainContainers(
                Mockito.argThat(domain -> "deleted".equals(domain.getDomainName())));
        verify(domainManager).removeDomainStorage(Mockito.argThat(domain -> "deleted".equals(domain.getDomainName())));
        verify(domainManager, Mockito.never()).stopDomain(any());
        assertTrue(service.findDomain("deleted", US_EAST_1).isEmpty());
        verify(domainManager, Mockito.times(1)).tryRestartDomain(any());
    }

    /** A domain deleted while its container fails to start is still deleted: its data and record go. */
    @Test
    void aDomainDeletedWhileItsContainerFailsToStartIsStillDeleted() {
        storedDomain("doomed", ACCOUNT, US_EAST_1);
        IllegalStateException cause = new IllegalStateException("image not found");
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            service.deleteDomain("doomed", US_EAST_1);
            throw cause;
        });

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertSame(cause, thrown.getCause().getCause());
        verify(domainManager).removeDomainContainers(any());
        verify(domainManager).removeDomainStorage(any());
        assertTrue(service.findDomain("doomed", US_EAST_1).isEmpty());
    }

    /** A domain deleted while the restore starts another one is not started: its container and data go instead. */
    @Test
    void aDomainDeletedWhileItWaitsIsNotStarted() {
        storedDomain("first", ACCOUNT, US_EAST_1);
        storedDomain("second", ACCOUNT, US_EAST_1);
        List<String> deleted = new ArrayList<>();
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            Domain domain = start.getArgument(0);
            String waiting = "first".equals(domain.getDomainName()) ? "second" : "first";
            service.deleteDomain(waiting, US_EAST_1);
            deleted.add(waiting);
            return true;
        });

        service.restorePersistedRuntime().join();

        verify(domainManager, Mockito.times(1)).tryRestartDomain(any());
        String waiting = deleted.getFirst();
        verify(domainManager).removeDomainContainers(Mockito.argThat(domain -> waiting.equals(domain.getDomainName())));
        assertTrue(service.findDomain(waiting, US_EAST_1).isEmpty());
    }

    /**
     * A domain whose record goes during its restore without a delete of its own is left alone: only the container the
     * restore started goes, and the record is not written back.
     */
    @Test
    void aDomainWhoseRecordGoesDuringItsRestoreIsLeftAlone() {
        storedDomain("vanished", ACCOUNT, US_EAST_1);
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            domainStore.deleteForAccount(ACCOUNT, US_EAST_1 + "::vanished");
            return true;
        });

        service.restorePersistedRuntime().join();

        verify(domainManager).stopDomain(Mockito.argThat(domain -> "vanished".equals(domain.getDomainName())));
        verify(domainManager, Mockito.never()).removeDomainContainers(any());
        verify(domainManager, Mockito.never()).removeDomainStorage(any());
        assertTrue(service.findDomain("vanished", US_EAST_1).isEmpty());
    }

    /**
     * A domain created again under the name of one being restored keeps its record: the restore removes only the
     * container it started, not the name, the record or the data.
     */
    @Test
    void aDomainCreatedAgainUnderTheNameKeepsItsRecord() {
        Domain old = storedDomain("again", ACCOUNT, US_EAST_1);
        Domain again = storedDomain("again", ACCOUNT, US_EAST_1);
        domainStore.putForAccount(ACCOUNT, US_EAST_1 + "::again", old);
        again.setCreatedAt(old.getCreatedAt().plusSeconds(60));
        again.setEndpoint("http://new-again:9200");
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            domainStore.putForAccount(ACCOUNT, US_EAST_1 + "::again", again);
            return true;
        });

        service.restorePersistedRuntime().join();

        assertSame(again, domainStore.getForAccount(ACCOUNT, US_EAST_1 + "::again").orElseThrow());
        assertEquals("http://new-again:9200", again.getEndpoint());
        verify(domainManager).stopDomain(old);
        verify(domainManager, Mockito.never()).removeDomainContainers(any());
    }

    /** A domain whose record goes while the restore starts another one is not started. */
    @Test
    void aDomainWhoseRecordGoesWhileItWaitsIsNotStarted() {
        storedDomain("first", ACCOUNT, US_EAST_1);
        storedDomain("second", ACCOUNT, US_EAST_1);
        when(domainManager.tryRestartDomain(any())).thenAnswer(start -> {
            Domain domain = start.getArgument(0);
            String waiting = "first".equals(domain.getDomainName()) ? "second" : "first";
            domainStore.deleteForAccount(ACCOUNT, US_EAST_1 + "::" + waiting);
            return true;
        });

        service.restorePersistedRuntime().join();

        verify(domainManager, Mockito.times(1)).tryRestartDomain(any());
        verify(domainManager, Mockito.never()).stopDomain(any());
    }

    /** A delete a restore did not live to finish is finished at the next start, which starts nothing for it. */
    @Test
    void aDeleteFlociDidNotLiveToFinishIsFinishedAtTheNextStart() {
        storedDomain("half-deleted", ACCOUNT, US_EAST_1).setDeleted(true);

        service.restorePersistedRuntime().join();

        verify(domainManager).removeDomainContainers(any());
        verify(domainManager).removeDomainStorage(any());
        verify(domainManager, Mockito.never()).tryRestartDomain(any());
        assertTrue(service.findDomain("half-deleted", US_EAST_1).isEmpty());
    }

    /** A deleted domain whose container Docker keeps stays Deleted, naming it, for the next start to remove. */
    @Test
    void aDeletedDomainWhoseContainerDockerKeepsStaysDeleted() {
        storedDomain("kept-deleted", ACCOUNT, US_EAST_1).setDeleted(true);
        IllegalStateException refused = new IllegalStateException("Failed to remove stale container old-id");
        Mockito.doThrow(refused).when(domainManager).removeDomainContainers(any());

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertSame(refused, thrown.getCause().getCause());
        assertTrue(service.describeDomain("kept-deleted", US_EAST_1).isDeleted());
        verify(domainManager, Mockito.never()).removeDomainStorage(any());
    }

    /**
     * Deleting again a domain whose delete Docker kept the container of removes it the same confirmed way: while
     * Docker still keeps it the domain stays Deleted, and once Docker removes it the domain and its data go.
     */
    @Test
    void aDeleteRetriedAfterDockerKeptTheContainerConfirmsTheRemoval() {
        storedDomain("retried", ACCOUNT, US_EAST_1).setDeleted(true);
        IllegalStateException refused = new IllegalStateException("Failed to remove stale container old-retried");
        Mockito.doThrow(refused).doThrow(refused).doNothing().when(domainManager).removeDomainContainers(any());
        assertThrows(CompletionException.class, () -> service.restorePersistedRuntime().join());

        assertTrue(service.deleteDomain("retried", US_EAST_1).isDeleted());
        assertTrue(service.describeDomain("retried", US_EAST_1).isDeleted(), "Docker still keeps the container");
        verify(domainManager, Mockito.never()).removeDomainStorage(any());

        service.deleteDomain("retried", US_EAST_1);

        assertTrue(service.findDomain("retried", US_EAST_1).isEmpty());
        verify(domainManager).removeDomainStorage(any());
        verify(domainManager, Mockito.never()).stopDomain(any());
    }

    /**
     * Two retried deletes of one domain run one at a time: the one that waits finds the domain gone, and removes
     * nothing, so it could never remove the container of a domain created again under the name meanwhile.
     */
    @Test
    void retriedDeletesOfOneDomainRunOneAtATime() throws Exception {
        storedDomain("contested", ACCOUNT, US_EAST_1).setDeleted(true);
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);
        AtomicInteger removals = new AtomicInteger();
        Mockito.doAnswer(remove -> {
            if (removals.getAndIncrement() == 0) {
                removing.countDown();
                assertTrue(carryOn.await(30, TimeUnit.SECONDS));
            }
            return null;
        }).when(domainManager).removeDomainContainers(any());
        Thread first = new Thread(() -> service.deleteDomain("contested", US_EAST_1));
        first.start();
        assertTrue(removing.await(30, TimeUnit.SECONDS));

        Thread second = new Thread(() -> service.deleteDomain("contested", US_EAST_1));
        second.start();
        await().atMost(Duration.ofSeconds(30)).until(() -> second.getState() == Thread.State.BLOCKED
                || second.getState() == Thread.State.TERMINATED);
        carryOn.countDown();
        first.join(30_000);
        second.join(30_000);

        assertEquals(1, removals.get());
        assertTrue(service.findDomain("contested", US_EAST_1).isEmpty());
    }

    /** Once its restore is through, a domain is deleted as any other: its container stopped and its data removed. */
    @Test
    void aDomainIsDeletedAsUsualOnceItsRestoreIsThrough() {
        storedDomain("restored", ACCOUNT, US_EAST_1);
        when(domainManager.tryRestartDomain(any())).thenReturn(true);
        service.restorePersistedRuntime().join();

        service.deleteDomain("restored", US_EAST_1);

        verify(domainManager).stopDomain(Mockito.argThat(domain -> "restored".equals(domain.getDomainName())));
        verify(domainManager, Mockito.never()).removeDomainContainers(any());
        assertTrue(service.findDomain("restored", US_EAST_1).isEmpty());
    }

    /**
     * A shutdown during the restore stops it: domains it has not reached, and one whose start shutdown overtook, are
     * left processing for the next start, and the poller treats them as any other.
     */
    @Test
    void aShutdownDuringTheRestoreLeavesItsDomainsForTheNextStart() {
        storedDomain("waiting", ACCOUNT, US_EAST_1);
        when(domainManager.isShuttingDown()).thenReturn(true);

        service.restorePersistedRuntime().join();

        verify(domainManager, Mockito.never()).tryRestartDomain(any());

        when(domainManager.isShuttingDown()).thenReturn(false);
        when(domainManager.tryRestartDomain(any()))
                .thenThrow(new OpenSearchDomainManager.ShuttingDownException("waiting"));
        service.restorePersistedRuntime().join();

        assertTrue(service.describeDomain("waiting", US_EAST_1).isProcessing());
        service.pollReadiness();
        verify(domainManager).isReady(Mockito.argThat(domain -> "waiting".equals(domain.getDomainName())));
    }

    @Test
    void restoringLeavesMockDomainsAsTheyAre() {
        when(osConfig.mock()).thenReturn(true);
        storedDomain("mocked", ACCOUNT, US_EAST_1);

        service.restorePersistedRuntime().join();

        assertFalse(service.describeDomain("mocked", US_EAST_1).isProcessing());
        Mockito.verifyNoInteractions(domainManager);
    }

    /** Shutdown stops restores and starts before it stops the domains' containers. */
    @Test
    void shutdownStopsStartsBeforeItStopsTheContainers() {
        storedDomain("running", ACCOUNT, US_EAST_1);

        service.shutdown();

        InOrder order = Mockito.inOrder(domainManager);
        order.verify(domainManager).markShuttingDown();
        order.verify(domainManager).stopDomain(any());
    }

    /** A domain stored in {@code account} and {@code region} whose record names the container it ran on. */
    private Domain storedDomain(String name, String account, String region) {
        Domain domain = new Domain();
        domain.setDomainName(name);
        domain.setAccountId(account);
        domain.setRegion(region);
        domain.setArn(AwsArnUtils.Arn.of("es", region, account, "domain/" + name).toString());
        domain.setEngineVersion("OpenSearch_2.11");
        domain.setCreatedAt(Instant.parse("2026-11-01T00:00:00Z"));
        domain.setEndpoint("http://172.17.0.2:9200");
        domain.setContainerId("old-" + name);
        domainStore.putForAccount(account, region + "::" + name, domain);
        return domain;
    }
}
