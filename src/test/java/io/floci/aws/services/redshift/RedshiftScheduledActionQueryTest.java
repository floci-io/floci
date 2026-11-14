package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.model.ScheduledAction;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftScheduledActionQueryTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/scheduler";

    private RedshiftQueryHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        AccountAwareStorageBackend<ScheduledAction> store =
                new AccountAwareStorageBackend<>(new InMemoryStorage<String, ScheduledAction>(), null, ACCOUNT_ID);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("redshift"), eq("redshift-scheduled-actions.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.clusterExists(anyString(), anyString())).thenReturn(true);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        RedshiftScheduledActionService scheduledActions = new RedshiftScheduledActionService(
                storageFactory, redshift, mock(RedshiftScheduledActionAuthorizer.class), regionResolver,
                Clock.fixed(Instant.parse("2026-11-20T12:15:00Z"), ZoneOffset.UTC));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        handler = new RedshiftQueryHandler(redshift, new RedshiftCredentialBroker(), config,
                mock(RedshiftIamDbUserResolver.class), regionResolver, null, null, null, scheduledActions);
    }

    private static MultivaluedMap<String, String> createParams(String name) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ScheduledActionName", name);
        params.add("IamRole", ROLE_ARN);
        params.add("Schedule", "cron(0 22 * * ? *)");
        params.add("TargetAction.PauseCluster.ClusterIdentifier", "analytics");
        params.add("ScheduledActionDescription", "pause at night");
        return params;
    }

    private static String body(Response response) {
        return String.valueOf(response.getEntity());
    }

    @Test
    void createReturnsTheActionWithItsStateAndNextInvocations() {
        String xml = body(handler.handle("CreateScheduledAction", createParams("nightly")));

        assertTrue(xml.contains("<ScheduledActionName>nightly</ScheduledActionName>"), xml);
        assertTrue(xml.contains("<TargetAction><PauseCluster><ClusterIdentifier>analytics</ClusterIdentifier>"
                + "</PauseCluster></TargetAction>"), xml);
        assertTrue(xml.contains("<State>ACTIVE</State>"), xml);
        assertTrue(xml.contains("<ScheduledActionTime>2026-11-20T22:00:00Z</ScheduledActionTime>"), xml);
        assertTrue(xml.contains("<ScheduledActionDescription>pause at night</ScheduledActionDescription>"), xml);
    }

    @Test
    void describeListsTheActionsFilteredByClusterAndModifyDisablesOne() {
        handler.handle("CreateScheduledAction", createParams("nightly"));
        MultivaluedMap<String, String> modify = new MultivaluedHashMap<>();
        modify.add("ScheduledActionName", "nightly");
        modify.add("Enable", "false");

        assertTrue(body(handler.handle("ModifyScheduledAction", modify)).contains("<State>DISABLED</State>"));

        MultivaluedMap<String, String> describe = new MultivaluedHashMap<>();
        describe.add("Filters.ScheduledActionFilter.1.Name", "cluster-identifier");
        describe.add("Filters.ScheduledActionFilter.1.Values.item.1", "analytics");
        String xml = body(handler.handle("DescribeScheduledActions", describe));
        assertTrue(xml.contains("<ScheduledActions><ScheduledAction><ScheduledActionName>nightly"), xml);

        describe.putSingle("Filters.ScheduledActionFilter.1.Values.item.1", "other");
        assertTrue(body(handler.handle("DescribeScheduledActions", describe))
                .contains("<ScheduledActions></ScheduledActions>"));
    }

    @Test
    void activeFlagKeepsEnabledOrDisabledActionsAndAbsentMeansAll() {
        handler.handle("CreateScheduledAction", createParams("on"));
        handler.handle("CreateScheduledAction", createParams("off"));
        MultivaluedMap<String, String> disable = new MultivaluedHashMap<>();
        disable.add("ScheduledActionName", "off");
        disable.add("Enable", "false");
        handler.handle("ModifyScheduledAction", disable);

        MultivaluedMap<String, String> describe = new MultivaluedHashMap<>();
        String all = body(handler.handle("DescribeScheduledActions", describe));
        assertTrue(all.contains("<ScheduledActionName>on<") && all.contains("<ScheduledActionName>off<"), all);

        describe.putSingle("Active", "false");
        String disabled = body(handler.handle("DescribeScheduledActions", describe));
        assertTrue(disabled.contains("<ScheduledActionName>off<") && !disabled.contains("<ScheduledActionName>on<"),
                disabled);

        describe.putSingle("Active", "true");
        String enabled = body(handler.handle("DescribeScheduledActions", describe));
        assertTrue(enabled.contains("<ScheduledActionName>on<") && !enabled.contains("<ScheduledActionName>off<"),
                enabled);
    }

    @Test
    void aNonBooleanEnableOrActiveIsRefusedAndNeverChangesState() {
        MultivaluedMap<String, String> create = createParams("typo");
        create.add("Enable", "maybe");
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> handler.handle("CreateScheduledAction", create)).getErrorCode());

        handler.handle("CreateScheduledAction", createParams("nightly"));
        MultivaluedMap<String, String> modify = new MultivaluedHashMap<>();
        modify.add("ScheduledActionName", "nightly");
        modify.add("Enable", "maybe");
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> handler.handle("ModifyScheduledAction", modify)).getErrorCode());

        MultivaluedMap<String, String> describe = new MultivaluedHashMap<>();
        describe.add("Active", "maybe");
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> handler.handle("DescribeScheduledActions", describe)).getErrorCode());

        describe.remove("Active");
        assertTrue(body(handler.handle("DescribeScheduledActions", describe)).contains("<State>ACTIVE</State>"));
    }

    @Test
    void deleteRemovesTheActionAndAMissingOneIsNotFound() {
        handler.handle("CreateScheduledAction", createParams("nightly"));
        MultivaluedMap<String, String> delete = new MultivaluedHashMap<>();
        delete.add("ScheduledActionName", "nightly");

        assertEquals(200, handler.handle("DeleteScheduledAction", delete).getStatus());
        AwsException failure = assertThrows(AwsException.class,
                () -> handler.handle("DeleteScheduledAction", delete));
        assertEquals("ScheduledActionNotFound", failure.getErrorCode());
    }

    @Test
    void resizeAndAmbiguousTargetsAreRefused() {
        MultivaluedMap<String, String> resize = createParams("resize");
        resize.remove("TargetAction.PauseCluster.ClusterIdentifier");
        resize.add("TargetAction.ResizeCluster.ClusterIdentifier", "analytics");
        assertEquals("ScheduledActionTypeUnsupported", assertThrows(AwsException.class,
                () -> handler.handle("CreateScheduledAction", resize)).getErrorCode());

        MultivaluedMap<String, String> both = createParams("both");
        both.add("TargetAction.ResumeCluster.ClusterIdentifier", "analytics");
        assertEquals("InvalidScheduledAction", assertThrows(AwsException.class,
                () -> handler.handle("CreateScheduledAction", both)).getErrorCode());
    }
}
