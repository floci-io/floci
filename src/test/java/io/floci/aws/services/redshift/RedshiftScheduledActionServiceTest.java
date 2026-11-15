package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.model.ScheduledAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftScheduledActionServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/scheduler";
    private static final Instant BASE_TIME = Instant.parse("2026-11-20T12:15:00Z");

    private AccountAwareStorageBackend<ScheduledAction> store;
    private RedshiftService redshift;
    private RedshiftScheduledActionAuthorizer authorizer;
    private RedshiftScheduledActionService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        store = spy(new AccountAwareStorageBackend<>(new InMemoryStorage<String, ScheduledAction>(), null, ACCOUNT_ID));
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("redshift"), eq("redshift-scheduled-actions.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        redshift = mock(RedshiftService.class);
        when(redshift.clusterExists(anyString(), anyString())).thenReturn(true);
        authorizer = mock(RedshiftScheduledActionAuthorizer.class);
        when(authorizer.refusal(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.getDefaultRegion()).thenReturn(REGION);
        when(regionResolver.getRegion()).thenReturn(REGION);
        when(regionResolver.buildArn(eq("redshift"), anyString(), anyString(), anyString()))
                .thenAnswer(call -> "arn:aws:redshift:" + call.getArgument(1) + ":" + call.getArgument(2)
                        + ":" + call.getArgument(3));
        service = new RedshiftScheduledActionService(storageFactory, redshift, authorizer, regionResolver,
                Clock.fixed(BASE_TIME, ZoneOffset.UTC));
    }

    private static ScheduledAction request(String name, String type, String schedule) {
        ScheduledAction request = new ScheduledAction();
        request.setScheduledActionName(name);
        request.setIamRole(ROLE_ARN);
        request.setSchedule(schedule);
        request.setTargetActionType(type);
        request.setClusterIdentifier("analytics");
        return request;
    }

    @Test
    void createStoresTheActionEnabledAndRejectsADuplicateName() {
        ScheduledAction created = service.createScheduledAction(
                request("nightly-pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 22 * * ? *)"));

        assertTrue(created.isEnabled());
        assertEquals(BASE_TIME, created.getActivatedAt());
        AwsException duplicate = assertThrows(AwsException.class, () -> service.createScheduledAction(
                request("nightly-pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 22 * * ? *)")));
        assertEquals("ScheduledActionAlreadyExists", duplicate.getErrorCode());
    }

    @Test
    void createRefusesAnUnknownClusterAnUnsupportedTargetAndAnInvertedWindow() {
        doThrow(new AwsException("ClusterNotFound", "Cluster analytics not found", 404))
                .when(redshift).describeClusters("analytics");
        assertEquals("ClusterNotFound", assertThrows(AwsException.class, () -> service.createScheduledAction(
                request("a", ScheduledAction.PAUSE_CLUSTER, "cron(0 22 * * ? *)"))).getErrorCode());

        assertEquals("InvalidScheduledAction", assertThrows(AwsException.class, () -> service.createScheduledAction(
                request("b", "ResizeCluster", "cron(0 22 * * ? *)"))).getErrorCode());

        ScheduledAction inverted = request("c", ScheduledAction.PAUSE_CLUSTER, "cron(0 22 * * ? *)");
        inverted.setStartTime(Instant.parse("2026-12-01T00:00:00Z"));
        inverted.setEndTime(Instant.parse("2026-11-01T00:00:00Z"));
        assertEquals("InvalidSchedule",
                assertThrows(AwsException.class, () -> service.createScheduledAction(inverted)).getErrorCode());
    }

    @Test
    void describeFiltersAndPagesByName() {
        service.createScheduledAction(request("b-resume", ScheduledAction.RESUME_CLUSTER, "cron(0 6 * * ? *)"));
        service.createScheduledAction(request("a-pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 22 * * ? *)"));

        PaginatedResult<ScheduledAction> all = service.describeScheduledActions(
                null, null, null, null, null, List.of(), List.of(), null, null);
        assertEquals(List.of("a-pause", "b-resume"),
                all.items().stream().map(ScheduledAction::getScheduledActionName).toList());

        PaginatedResult<ScheduledAction> pauses = service.describeScheduledActions(
                null, ScheduledAction.PAUSE_CLUSTER, null, null, null, List.of(), List.of(), null, null);
        assertEquals(1, pauses.items().size());

        PaginatedResult<ScheduledAction> otherCluster = service.describeScheduledActions(
                null, null, null, null, false, List.of("other"), List.of(), null, null);
        assertTrue(otherCluster.items().isEmpty());

        ScheduledAction disable = new ScheduledAction();
        disable.setScheduledActionName("b-resume");
        service.modifyScheduledAction(disable, false);
        assertEquals(List.of("b-resume"), service.describeScheduledActions(
                null, null, null, null, false, List.of(), List.of(), null, null).items().stream()
                .map(ScheduledAction::getScheduledActionName).toList());
        assertEquals(List.of("a-pause"), service.describeScheduledActions(
                null, null, null, null, true, List.of(), List.of(), null, null).items().stream()
                .map(ScheduledAction::getScheduledActionName).toList());

        assertEquals("InvalidParameterValue", assertThrows(AwsException.class, () -> service.describeScheduledActions(
                null, null, null, null, null, List.of(), List.of(), 5, null)).getErrorCode());
    }

    @Test
    void modifyReplacesOnlyTheGivenMembersAndRestartsTheCountOnANewSchedule() {
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 22 * * ? *)"));
        ScheduledAction changes = new ScheduledAction();
        changes.setScheduledActionName("pause");
        changes.setSchedule("cron(0 23 * * ? *)");

        ScheduledAction modified = service.modifyScheduledAction(changes, false);

        assertEquals("cron(0 23 * * ? *)", modified.getSchedule());
        assertEquals(ROLE_ARN, modified.getIamRole());
        assertEquals(ScheduledAction.PAUSE_CLUSTER, modified.getTargetActionType());
        assertTrue(!modified.isEnabled());
        assertEquals(BASE_TIME, modified.getActivatedAt());
    }

    @Test
    void deletingAMissingActionIsNotFound() {
        assertEquals("ScheduledActionNotFound", assertThrows(AwsException.class,
                () -> service.deleteScheduledAction("missing")).getErrorCode());
    }

    @Test
    void describePagesWithMarkerAndFiltersByInvocationWindow() {
        for (int i = 0; i < 21; i++) {
            service.createScheduledAction(request(String.format("a%02d", i), ScheduledAction.PAUSE_CLUSTER,
                    "cron(0 22 * * ? *)"));
        }
        PaginatedResult<ScheduledAction> first = service.describeScheduledActions(
                null, null, null, null, null, List.of(), List.of(), 20, null);
        assertEquals(20, first.items().size());
        assertNotNull(first.nextToken());
        PaginatedResult<ScheduledAction> second = service.describeScheduledActions(
                null, null, null, null, null, List.of(), List.of(), 20, first.nextToken());
        assertEquals(List.of("a20"), second.items().stream().map(ScheduledAction::getScheduledActionName).toList());

        Instant farFuture = Instant.parse("2027-01-01T00:00:00Z");
        assertEquals(21, service.describeScheduledActions(
                null, null, farFuture, null, null, List.of(), List.of(), 100, null).items().size());
        assertTrue(service.describeScheduledActions(
                null, null, null, BASE_TIME.plusSeconds(60), null, List.of(), List.of(), 100, null).items().isEmpty());
    }

    @Test
    void sweepRunsOnlyTheLatestDueInvocationOnceAndRecordsIt() {
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));
        Instant tick = BASE_TIME.plusSeconds(3 * 3600);

        service.runScheduledActions(tick);
        service.runScheduledActions(tick.plusSeconds(60));

        verify(redshift, times(1)).pauseCluster("analytics");
        ScheduledAction stored = store.get("pause").orElseThrow();
        assertEquals(Instant.parse("2026-11-20T15:00:00Z"), stored.getLastCompletedInvocation());
        assertNull(stored.getLastFailure());
    }

    @Test
    void sweepResumesAtAOneOffTimeAndNeverRepeatsIt() {
        service.createScheduledAction(request("resume", ScheduledAction.RESUME_CLUSTER, "at(2026-11-20T13:00:00)"));

        service.runScheduledActions(Instant.parse("2026-11-20T12:30:00Z"));
        verify(redshift, never()).resumeCluster(anyString());

        service.runScheduledActions(Instant.parse("2026-11-20T13:05:00Z"));
        service.runScheduledActions(Instant.parse("2026-11-20T14:05:00Z"));
        verify(redshift, times(1)).resumeCluster("analytics");
    }

    @Test
    void disabledActionAndRefusedRoleDoNotRunAndAFailureIsRecorded() {
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));
        ScheduledAction changes = new ScheduledAction();
        changes.setScheduledActionName("pause");
        service.modifyScheduledAction(changes, false);
        service.runScheduledActions(BASE_TIME.plusSeconds(7200));
        verify(redshift, never()).pauseCluster(anyString());

        service.modifyScheduledAction(changes, true);
        when(authorizer.refusal(eq(ROLE_ARN), eq(ACCOUNT_ID), anyString(), eq("PauseCluster"), anyString()))
                .thenReturn(Optional.of("the role does not trust the scheduler"));
        service.runScheduledActions(BASE_TIME.plusSeconds(4 * 3600));

        verify(redshift, never()).pauseCluster(anyString());
        ScheduledAction stored = store.get("pause").orElseThrow();
        assertNotNull(stored.getLastFailure());
        assertNull(stored.getLastCompletedInvocation());
    }

    @Test
    void failedInvocationIsRecordedAndNotRetriedAndReEnablingDoesNotReplay() {
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));
        doThrow(new AwsException("InvalidClusterState", "not available", 400)).when(redshift).pauseCluster("analytics");
        Instant tick = BASE_TIME.plusSeconds(3 * 3600);

        service.runScheduledActions(tick);
        service.runScheduledActions(tick.plusSeconds(60));

        verify(redshift, times(1)).pauseCluster("analytics");
        assertEquals("not available", store.get("pause").orElseThrow().getLastFailure());

        ScheduledAction changes = new ScheduledAction();
        changes.setScheduledActionName("pause");
        service.modifyScheduledAction(changes, false);
        service.modifyScheduledAction(changes, true);
        service.runScheduledActions(tick.plusSeconds(120));
        verify(redshift, times(1)).pauseCluster("analytics");
    }

    @Test
    void sweepAuthorizesAgainstTheArnOfTheRegionTheClusterLivesIn() {
        when(redshift.clusterRegion(ACCOUNT_ID, "analytics")).thenReturn(Optional.of("eu-west-1"));
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));

        service.runScheduledActions(BASE_TIME.plusSeconds(3 * 3600));

        verify(authorizer).refusal(ROLE_ARN, ACCOUNT_ID, "eu-west-1", ScheduledAction.PAUSE_CLUSTER, "analytics");
        verify(redshift).pauseCluster("analytics");
    }

    @Test
    void aFailedOutcomeWriteIsNotSwallowedAndDoesNotAdvanceTheCheckpoint() {
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));
        doThrow(new IllegalStateException("disk full")).when(store).putAllAtomicallyForAccount(anyString(), any());
        Instant tick = BASE_TIME.plusSeconds(3 * 3600);

        service.runScheduledActions(tick);

        assertNull(store.get("pause").orElseThrow().getLastClaimedInvocation());
    }

    @Test
    void modifyDropsTheActionWhenTheClusterIsDeletedBetweenValidationAndWrite() {
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));
        when(redshift.clusterExists(ACCOUNT_ID, "analytics")).thenReturn(true, false);
        ScheduledAction changes = new ScheduledAction();
        changes.setScheduledActionName("pause");
        changes.setDescription("changed");

        AwsException failure = assertThrows(AwsException.class, () -> service.modifyScheduledAction(changes, null));

        assertEquals("ClusterNotFound", failure.getErrorCode());
        assertTrue(store.get("pause").isEmpty());
    }

    @Test
    void removingTheClusterDeletesItsActionsAndASweepDropsAnOrphan() {
        service.registerClusterRemoval();
        ArgumentCaptor<BiConsumer<String, String>> listener = ArgumentCaptor.forClass(BiConsumer.class);
        verify(redshift).onClusterRemoved(listener.capture());
        service.createScheduledAction(request("pause", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));

        listener.getValue().accept(ACCOUNT_ID, "analytics");
        assertTrue(store.get("pause").isEmpty());

        service.createScheduledAction(request("again", ScheduledAction.PAUSE_CLUSTER, "cron(0 * * * ? *)"));
        when(redshift.clusterExists(ACCOUNT_ID, "analytics")).thenReturn(false);
        service.runScheduledActions(BASE_TIME.plusSeconds(7200));
        assertTrue(store.get("again").isEmpty());
        verify(redshift, never()).pauseCluster(anyString());
    }
}
