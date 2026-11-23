package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emrserverless.EmrServerlessJobRunService;
import io.floci.aws.services.emrserverless.EmrServerlessService;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.AutoStopConfiguration;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.InitialCapacityConfig;
import io.floci.aws.services.emrserverless.model.SchedulerConfiguration;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import io.floci.aws.services.emrserverless.model.WorkerConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code AWS::EMRServerless::Application} in isolation, with the EMR Serverless services mocked. */
class EmrServerlessCfnProvisionerTest {

    private static final String ID = "00fexampleapp01";
    private static final String ARN = "arn:aws:emr-serverless:us-east-1:000000000000:/applications/" + ID;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EmrServerlessService.ResourceArn RESOURCE =
            new EmrServerlessService.ResourceArn(ARN, ID, null, null);

    private final EmrServerlessService applications = mock(EmrServerlessService.class);
    private final EmrServerlessJobRunService jobRuns = mock(EmrServerlessJobRunService.class);
    private final EmrServerlessCfnProvisioner provisioner = new EmrServerlessCfnProvisioner(applications, jobRuns);

    private static ProvisionContext ctx(String priorPhysicalId, String priorProperties) {
        return new ProvisionContext(CloudFormationTemplateEngine.standalone("000000000000", "us-east-1", "my-stack",
                MAPPER, null), "us-east-1", "000000000000", "my-stack", priorPhysicalId, event -> { },
                priorProperties == null ? null : json(priorProperties));
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("App");
        r.setResourceType("AWS::EMRServerless::Application");
        r.setAttributes(new HashMap<>());
        return r;
    }

    /** The resource as the create that made application {@link #ID} left it. */
    private static StackResource provisioned() {
        StackResource r = resource();
        r.setPhysicalId(ID);
        r.getAttributes().put("ApplicationId", ID);
        r.getAttributes().put("Arn", ARN);
        return r;
    }

    private static Application app(String id, Map<String, String> tags) {
        Application app = new Application();
        app.setApplicationId(id);
        app.setArn("arn:aws:emr-serverless:us-east-1:000000000000:/applications/" + id);
        app.setReleaseLabel("emr-7.5.0");
        app.setType("SPARK");
        app.setState("CREATED");
        app.setTags(new LinkedHashMap<>(tags));
        return app;
    }

    /** Application {@link #ID} stopping after 15 idle minutes with two pre-initialized drivers. */
    private static Application live() {
        Application live = app(ID, Map.of("team", "data"));
        AutoStopConfiguration autoStop = new AutoStopConfiguration();
        autoStop.setEnabled(true);
        autoStop.setIdleTimeoutMinutes(15);
        live.setAutoStopConfiguration(autoStop);
        WorkerConfiguration worker = new WorkerConfiguration();
        worker.setCpu("2 vCPU");
        worker.setMemory("4 GB");
        InitialCapacityConfig capacity = new InitialCapacityConfig();
        capacity.setWorkerCount(2L);
        capacity.setWorkerConfiguration(worker);
        live.setInitialCapacity(Map.of("Driver", capacity));
        return live;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    /** Tags in the order given, as key, value, key, value. */
    private static Map<String, String> tags(String... keysAndValues) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tags;
    }

    /** What ListTagsForResource answers for application {@link #ID}, the first time and then every time after. */
    private void stubTags(Map<String, String> first, Map<String, String> after) {
        when(applications.resourceArn(ARN)).thenReturn(RESOURCE);
        when(applications.listTags(RESOURCE)).thenReturn(first, after);
    }

    private static final String PRIOR = """
            {"ReleaseLabel": "emr-7.5.0", "Type": "SPARK",
             "AutoStopConfiguration": {"Enabled": true, "IdleTimeoutMinutes": 15},
             "InitialCapacity": [{"Key": "Driver",
               "Value": {"WorkerCount": 2, "WorkerConfiguration": {"Cpu": "2 vCPU", "Memory": "4 GB"}}}],
             "Tags": [{"Key": "team", "Value": "data"}]}
            """;
    private static final String UPDATED = """
            {"ReleaseLabel": "emr-7.5.0", "Type": "SPARK",
             "AutoStopConfiguration": {"Enabled": true, "IdleTimeoutMinutes": 30},
             "Tags": [{"Key": "stage", "Value": "prod"}]}
            """;

    @Test
    void createsTheApplicationWithTheTemplateInTheApiShape() {
        when(applications.createApplication(any())).thenReturn(app(ID, Map.of()));
        StackResource r = resource();

        provisioner.provision(r, json("""
                {"Name": "etl", "ReleaseLabel": "emr-7.5.0", "Type": "SPARK", "Architecture": "ARM64",
                 "InitialCapacity": [{"Key": "Driver",
                   "Value": {"WorkerCount": "2", "WorkerConfiguration": {"Cpu": "2 vCPU", "Memory": "4 GB"}}}],
                 "AutoStopConfiguration": {"Enabled": "true", "IdleTimeoutMinutes": "30"},
                 "MonitoringConfiguration": {"CloudWatchLoggingConfiguration": {"Enabled": true,
                   "LogTypeMap": [{"Key": "SPARK_DRIVER", "Value": ["STDOUT", "STDERR"]}]}},
                 "RuntimeConfiguration": [{"Classification": "spark-defaults",
                   "Properties": {"spark.driver.cores": "2"}}],
                 "WorkerTypeSpecifications": {"Driver": {"ImageConfiguration":
                   {"ImageUri": "public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest"}}},
                 "SchedulerConfiguration": {"MaxConcurrentRuns": "5"},
                 "Tags": [{"Key": "team", "Value": "data"}]}
                """), ctx(null, null));

        ArgumentCaptor<CreateApplicationRequest> created = ArgumentCaptor.forClass(CreateApplicationRequest.class);
        verify(applications).createApplication(created.capture());
        CreateApplicationRequest request = created.getValue();
        assertEquals("etl", request.getName());
        assertEquals("SPARK", request.getType());
        assertEquals("emr-7.5.0", request.getReleaseLabel());
        assertEquals("ARM64", request.getArchitecture());
        assertEquals(2L, request.getInitialCapacity().get("Driver").getWorkerCount());
        assertEquals("4 GB", request.getInitialCapacity().get("Driver").getWorkerConfiguration().getMemory());
        assertEquals(Boolean.TRUE, request.getAutoStopConfiguration().getEnabled());
        assertEquals(30, request.getAutoStopConfiguration().getIdleTimeoutMinutes());
        assertEquals(json("""
                {"cloudWatchLoggingConfiguration": {"enabled": true,
                  "logTypes": {"SPARK_DRIVER": ["STDOUT", "STDERR"]}}}
                """), MAPPER.valueToTree(request.getMonitoringConfiguration()));
        assertEquals(json("""
                [{"classification": "spark-defaults", "properties": {"spark.driver.cores": "2"}}]
                """), MAPPER.valueToTree(request.getRuntimeConfiguration()));
        assertEquals("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest",
                request.getWorkerTypeSpecifications().get("Driver").getImageConfiguration().getImageUri());
        assertEquals(json("{\"maxConcurrentRuns\": 5}"), MAPPER.valueToTree(request.getSchedulerConfiguration()));
        assertEquals(Map.of("team", "data"), request.getTags());
        assertFalse(request.getClientToken().isBlank());
        assertEquals(ID, r.getPhysicalId());
        assertEquals(ID, r.getAttributes().get("ApplicationId"));
        assertEquals("arn:aws:emr-serverless:us-east-1:000000000000:/applications/" + ID, r.getAttributes().get("Arn"));
    }

    @Test
    void aTemplateTheHandlersModelValidationRefusesFailsBeforeAnyCall() {
        AwsException missing = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), json("{\"Type\": \"SPARK\"}"), ctx(null, null)));
        AwsException extraneous = assertThrows(AwsException.class, () -> provisioner.provision(resource(), json("""
                {"ReleaseLabel": "emr-7.5.0", "Type": "SPARK", "AutoStopConfiguration": {"Idle": 5}}
                """), ctx(null, null)));

        AwsException architecture = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                json("{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\", \"Architecture\": \"AMD64\"}"),
                ctx(null, null)));
        AwsException readOnly = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                json("{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\", \"ApplicationId\": \"00fmine\"}"),
                ctx(null, null)));

        assertEquals("Model validation failed (#: required key [ReleaseLabel] not found)", missing.getMessage());
        assertEquals("Model validation failed (#/AutoStopConfiguration: extraneous key [Idle] is not permitted)",
                extraneous.getMessage());
        assertEquals("Model validation failed (#/Architecture: AMD64 is not a valid enum value)",
                architecture.getMessage());
        assertEquals("ApplicationId is a ReadOnly property which cannot be specified in the template",
                readOnly.getMessage());
        verifyNoInteractions(applications, jobRuns);
    }

    @Test
    void anUpdateSendsWhatTheTemplateChangedClearsADroppedInitialCapacityAndTakesTheTagsToTheTemplates() {
        when(applications.findLive(ID)).thenReturn(Optional.of(live()));
        stubTags(tags("team", "data", "owner", "ops"), tags());
        StackResource r = provisioned();

        provisioner.provision(r, json(UPDATED), ctx(ID, PRIOR));

        ArgumentCaptor<UpdateApplicationRequest> updated = ArgumentCaptor.forClass(UpdateApplicationRequest.class);
        verify(jobRuns).updateApplication(eq(ID), updated.capture());
        UpdateApplicationRequest request = updated.getValue();
        assertEquals(30, request.getAutoStopConfiguration().getIdleTimeoutMinutes());
        assertEquals(Map.of(), request.getInitialCapacity());
        assertNull(request.getReleaseLabel());
        assertNull(request.getArchitecture());
        verify(applications).untagResource(RESOURCE, List.of("team", "owner"));
        verify(applications).tagResource(RESOURCE, Map.of("stage", "prod"));
        verify(applications, never()).createApplication(any());
        assertEquals(ID, r.getPhysicalId());
        assertTrue(r.getAttributes().containsKey(EmrServerlessCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aRollbackPutsBackTheSettingsAndTagsTheUpdateChanged() {
        when(applications.findLive(ID)).thenReturn(Optional.of(live()));
        stubTags(tags("team", "data"), tags("stage", "prod"));
        StackResource r = provisioned();
        provisioner.provision(r, json(UPDATED), ctx(ID, PRIOR));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<UpdateApplicationRequest> updates = ArgumentCaptor.forClass(UpdateApplicationRequest.class);
        verify(jobRuns, times(2)).updateApplication(eq(ID), updates.capture());
        UpdateApplicationRequest restore = updates.getAllValues().get(1);
        assertEquals(15, restore.getAutoStopConfiguration().getIdleTimeoutMinutes());
        assertEquals(2L, restore.getInitialCapacity().get("Driver").getWorkerCount());
        assertNull(restore.getReleaseLabel());
        verify(applications).untagResource(RESOURCE, List.of("stage"));
        verify(applications).tagResource(RESOURCE, Map.of("team", "data"));
        assertFalse(r.getAttributes().containsKey(EmrServerlessCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aSettingTheApplicationDidNotHoldIsClearedOnRollbackWhereAnEmptyValueClearsIt() {
        Application live = app(ID, Map.of());
        when(applications.findLive(ID)).thenReturn(Optional.of(live));
        stubTags(tags(), tags());
        StackResource r = provisioned();
        provisioner.provision(r, json("""
                {"ReleaseLabel": "emr-7.5.0", "Type": "SPARK",
                 "ImageConfiguration": {"ImageUri": "public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest"},
                 "AutoStartConfiguration": {"Enabled": false}}
                """), ctx(ID, "{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"));

        provisioner.rollbackUpdate(r);

        ArgumentCaptor<UpdateApplicationRequest> updates = ArgumentCaptor.forClass(UpdateApplicationRequest.class);
        verify(jobRuns, times(2)).updateApplication(eq(ID), updates.capture());
        UpdateApplicationRequest restore = updates.getAllValues().get(1);
        assertNull(restore.getImageConfiguration().getImageUri());
        assertNull(restore.getAutoStartConfiguration());
    }

    @Test
    void anUpdateUpdateApplicationRefusesChangedNothingSoNothingIsPutBack() {
        when(applications.findLive(ID)).thenReturn(Optional.of(live()));
        AwsException refused = new AwsException("ValidationException", "refused", 400);
        // A STARTED application refuses the restore's stopped-only settings as well.
        when(jobRuns.updateApplication(eq(ID), any())).thenThrow(refused);
        StackResource r = provisioned();

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, json(UPDATED), ctx(ID, PRIOR)));

        assertEquals(refused, thrown);
        verify(jobRuns, times(1)).updateApplication(eq(ID), any());
        verify(applications, never()).listTags(any());
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aTagFailureAfterTheSettingsChangedPutsTheSettingsAndTagsBackBeforeItPropagates() {
        when(applications.findLive(ID)).thenReturn(Optional.of(live()));
        stubTags(tags("team", "data"), tags());
        AwsException tagging = new AwsException("ValidationException", "tagging", 400);
        doThrow(tagging).doNothing().when(applications).tagResource(eq(RESOURCE), any());
        StackResource r = provisioned();

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, json(UPDATED), ctx(ID, PRIOR)));

        assertEquals(tagging, thrown);
        ArgumentCaptor<UpdateApplicationRequest> updates = ArgumentCaptor.forClass(UpdateApplicationRequest.class);
        verify(jobRuns, times(2)).updateApplication(eq(ID), updates.capture());
        assertEquals(15, updates.getAllValues().get(1).getAutoStopConfiguration().getIdleTimeoutMinutes());
        verify(applications).tagResource(RESOURCE, Map.of("team", "data"));
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aSchedulerChangeIsPutBackWithTheMembersTheUpdateSentSoAStartedApplicationTakesIt() {
        Application live = app(ID, Map.of());
        SchedulerConfiguration scheduler = new SchedulerConfiguration();
        scheduler.setProperty("maxConcurrentRuns", 15);
        scheduler.setProperty("queueTimeoutMinutes", 360);
        live.setSchedulerConfiguration(scheduler);
        when(applications.findLive(ID)).thenReturn(Optional.of(live));
        stubTags(tags(), tags());
        StackResource r = provisioned();
        provisioner.provision(r, json("""
                {"ReleaseLabel": "emr-7.5.0", "Type": "SPARK", "SchedulerConfiguration": {"MaxConcurrentRuns": 10}}
                """), ctx(ID, "{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"));

        provisioner.rollbackUpdate(r);

        ArgumentCaptor<UpdateApplicationRequest> updates = ArgumentCaptor.forClass(UpdateApplicationRequest.class);
        verify(jobRuns, times(2)).updateApplication(eq(ID), updates.capture());
        assertEquals(json("{\"maxConcurrentRuns\": 10}"),
                MAPPER.valueToTree(updates.getAllValues().get(0).getSchedulerConfiguration()));
        assertEquals(json("{\"maxConcurrentRuns\": 15}"),
                MAPPER.valueToTree(updates.getAllValues().get(1).getSchedulerConfiguration()));
    }

    @Test
    void aSchedulerTheUpdateSetWhereQueuingWasOffIsLeftAsTheUpdateLeftIt() {
        Application live = app(ID, Map.of());
        live.setSchedulerOff(true);
        when(applications.findLive(ID)).thenReturn(Optional.of(live));
        stubTags(tags(), tags());
        StackResource r = provisioned();
        provisioner.provision(r, json("""
                {"ReleaseLabel": "emr-7.5.0", "Type": "SPARK", "SchedulerConfiguration": {"MaxConcurrentRuns": 10},
                 "AutoStopConfiguration": {"Enabled": false}}
                """), ctx(ID, "{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"));

        provisioner.rollbackUpdate(r);

        verify(jobRuns, times(1)).updateApplication(eq(ID), any());
    }

    @Test
    void anUndoThatFailedLeavesItsSnapshotForContinueUpdateRollback() {
        StackResource previous = provisioned();
        StackResource attempted = provisioned();
        attempted.getAttributes().put(EmrServerlessCfnProvisioner.SNAPSHOT_ATTR, "{}");
        StackResource undone = provisioned();
        undone.getAttributes().put(EmrServerlessCfnProvisioner.SNAPSHOT_ATTR, "{}");

        provisioner.mergeFailedUpdateResourceTracking(previous, undone);
        assertFalse(previous.getAttributes().containsKey(EmrServerlessCfnProvisioner.SNAPSHOT_ATTR));
        attempted.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, "Could not restore");
        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals("{}", previous.getAttributes().get(EmrServerlessCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void anUpdateOfAnApplicationThatIsGoneFailsAsTheHandlerDoes() {
        when(applications.findLive(ID)).thenReturn(Optional.empty());

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(provisioned(), json(UPDATED), ctx(ID, PRIOR)));

        assertEquals("Resource of type 'AWS::EMRServerless::Application' with identifier '" + ID
                + "' was not found.", e.getMessage());
        verifyNoInteractions(jobRuns);
    }

    @Test
    void changingTheNameReplacesTheApplicationAndLeavesTheOldOneForTheCleanup() {
        when(applications.createApplication(any())).thenReturn(app("00fnewapp02", Map.of()));
        StackResource r = provisioned();

        provisioner.provision(r, json("{\"Name\": \"b\", \"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"),
                ctx(ID, "{\"Name\": \"a\", \"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"));

        assertEquals("00fnewapp02", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(ID, provisioner.updateCleanupPhysicalId(r));
        verify(applications, never()).findLive(any());
        verifyNoInteractions(jobRuns);
    }

    @Test
    void aStubFromBeforeTheTypeHadAProvisionerIsTakenOverByACreate() {
        when(applications.createApplication(any())).thenReturn(app(ID, Map.of()));
        StackResource r = resource();
        r.setPhysicalId("App-0a1b2c3d");
        r.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "App");

        provisioner.provision(r, json("{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"),
                ctx("App-0a1b2c3d", "{\"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}"));

        assertEquals(ID, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        verify(applications, never()).findLive(any());
    }

    @Test
    void theDeleteRemovesALiveApplicationAndLeavesAGoneOneAlone() {
        when(applications.whileLive(eq(ID), any())).thenAnswer(inv -> {
            inv.<Consumer<Application>>getArgument(1).accept(live());
            return true;
        });

        provisioner.delete("AWS::EMRServerless::Application", ID, "us-east-1");
        provisioner.delete("AWS::EMRServerless::Application", "00fgoneapp03", "us-east-1");

        verify(applications).deleteApplication(ID);
        verify(applications, never()).deleteApplication("00fgoneapp03");
    }
}
