package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emr.EmrHandler;
import io.floci.aws.services.emr.EmrService;
import io.floci.aws.services.emr.EmrService.InstanceFleetModification;
import io.floci.aws.services.emr.EmrService.InstanceGroupModification;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.services.emr.model.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.cloudformation.provisioners.ProvisionContexts.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Amazon EMR's own CloudFormation types over {@link EmrService} in isolation, with EMR mocked. */
class EmrCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENCRYPTION = """
            {"EncryptionConfiguration": {"EnableInTransitEncryption": false, "EnableAtRestEncryption": false}}""";

    private final EmrService emr = mock(EmrService.class);
    private final EmrHandler handler = mock(EmrHandler.class);
    private final EmrCfnProvisioner provisioner = new EmrCfnProvisioner(emr, handler);

    private static ProvisionContext ctx(String priorPhysicalId, String priorProperties) {
        return ProvisionContexts.standalone(priorPhysicalId, priorProperties == null ? null : json(priorProperties));
    }

    private static StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Security");
        r.setResourceType("AWS::EMR::SecurityConfiguration");
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }


    private static String props(String name, String configuration) {
        return "{" + (name == null ? "" : "\"Name\": \"" + name + "\", ") + "\"SecurityConfiguration\": "
                + configuration + "}";
    }

    @Test
    void createsTheSecurityConfigurationFromAnObjectOrItsText() {
        StackResource fromObject = resource(null);
        StackResource fromText = resource(null);

        provisioner.provision(fromObject, json(props("tls", ENCRYPTION)), ctx(null, null));
        provisioner.provision(fromText, json(props("text", MAPPER.valueToTree(ENCRYPTION).toString())),
                ctx(null, null));

        verify(emr).createSecurityConfiguration("tls", json(ENCRYPTION).toString(), "us-east-1");
        verify(emr).createSecurityConfiguration("text", ENCRYPTION, "us-east-1");
        assertEquals("tls", fromObject.getPhysicalId());
        assertEquals("text", fromText.getPhysicalId());
    }

    @Test
    void anUnnamedSecurityConfigurationGetsAGeneratedName() {
        StackResource r = resource(null);

        provisioner.provision(r, json(props(null, ENCRYPTION)), ctx(null, null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(emr).createSecurityConfiguration(name.capture(), anyString(), eq("us-east-1"));
        assertTrue(name.getValue().startsWith("my-stack-Security-"), name.getValue());
        assertEquals(name.getValue(), r.getPhysicalId());
    }

    @Test
    void anUpdateThatChangesNothingKeepsIt() {
        StackResource r = resource("tls");

        provisioner.provision(r, json(props("tls", ENCRYPTION)), ctx("tls", props("tls", ENCRYPTION)));

        assertEquals("tls", r.getPhysicalId());
        verifyNoInteractions(emr);
    }

    @Test
    void aChangeReplacesAnUnnamedOneAndIsRefusedForOneThatKeepsItsName() {
        String changed = ENCRYPTION.replace("\"EnableAtRestEncryption\": false", "\"EnableAtRestEncryption\": true");
        StackResource unnamed = resource("my-stack-Security-OLD");

        provisioner.provision(unnamed, json(props(null, changed)),
                ctx("my-stack-Security-OLD", props(null, ENCRYPTION)));
        AwsException named = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("tls"), json(props("tls", changed)),
                        ctx("tls", props("tls", ENCRYPTION))));

        assertTrue(unnamed.getPhysicalId().startsWith("my-stack-Security-"), unnamed.getPhysicalId());
        assertEquals("my-stack-Security-OLD", provisioner.updateCleanupPhysicalId(unnamed));
        assertTrue(named.getMessage().startsWith("CloudFormation cannot update a stack when a custom-named resource"),
                named.getMessage());
    }

    @Test
    void theDeleteTakesASecurityConfigurationTheRegionDoesNotListAsDeleted() {
        SecurityConfiguration tls = new SecurityConfiguration();
        tls.setName("tls");
        when(emr.listSecurityConfigurations("us-east-1")).thenReturn(List.of(tls));

        provisioner.delete("AWS::EMR::SecurityConfiguration", "tls", "us-east-1");
        provisioner.delete("AWS::EMR::SecurityConfiguration", "gone", "us-east-1");

        verify(emr).deleteSecurityConfiguration("tls", "us-east-1");
        verify(emr, never()).deleteSecurityConfiguration(eq("gone"), any());
    }

    private static final String CLUSTER_ID = "j-ABCDEFGHIJKLM";
    private static final String MANAGED_SCALING = """
            {"ComputeLimits": {"UnitType": "Instances", "MinimumCapacityUnits": 2, "MaximumCapacityUnits": 6}}""";

    /** A cluster template whose core group has {@code coreCount} instances of {@code coreType}. */
    private static String clusterProps(int coreCount, String coreType, String extra) {
        return """
                {"Name": "etl", "ReleaseLabel": "emr-7.5.0", "JobFlowRole": "EMR_EC2_DefaultRole",
                 "ServiceRole": "EMR_DefaultRole", "Configurations": [{"Classification": "spark-defaults",
                   "ConfigurationProperties": {"spark.executor.cores": "2"}}],
                 "Instances": {"TerminationProtected": true,
                   "MasterInstanceGroup": {"InstanceCount": 1, "InstanceType": "m5.xlarge"},
                   "CoreInstanceGroup": {"InstanceCount": %d, "InstanceType": "%s"},
                   "TaskInstanceGroups": [{"Name": "spot", "InstanceCount": 1, "InstanceType": "m5.xlarge"}]},
                 "Steps": [{"Name": "pi", "ActionOnFailure": "CONTINUE", "HadoopJarStep": {"Jar": "command-runner.jar",
                   "Args": ["spark-example", "SparkPi"], "StepProperties": [{"Key": "k", "Value": "v"}]}}],
                 "Tags": [{"Key": "stage", "Value": "v1"}]%s}
                """.formatted(coreCount, coreType, extra);
    }

    private static StackResource cluster(String physicalId) {
        StackResource r = resource(physicalId);
        r.setLogicalId("Cluster");
        r.setResourceType("AWS::EMR::Cluster");
        return r;
    }

    private static EmrCluster emrCluster(String id, String state) {
        EmrCluster cluster = new EmrCluster();
        cluster.setId(id);
        cluster.setState(state);
        cluster.setMasterPublicDnsName("ec2-10-0-0-1.compute-1.amazonaws.com");
        cluster.setTerminationProtected(true);
        cluster.setTags(Map.of("stage", "v1"));
        return cluster;
    }

    private static EmrInstanceGroup group(String id, String type, String name) {
        EmrInstanceGroup group = new EmrInstanceGroup();
        group.setId(id);
        group.setInstanceGroupType(type);
        group.setName(name);
        return group;
    }

    /** The cluster as a create of {@code clusterProps(2, "m5.xlarge", "")} left the resource. */
    private StackResource created() {
        when(handler.clusterFrom(any(), any())).thenReturn(new EmrCluster());
        when(emr.runJobFlow(any(), eq("us-east-1"))).thenReturn(emrCluster(CLUSTER_ID, "STARTING"));
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        StackResource r = cluster(null);
        provisioner.provision(r, json(clusterProps(2, "m5.xlarge", "")), ctx(null, null));
        return r;
    }

    @Test
    void createsTheClusterFromTheTemplateInRunJobFlowsMembersOnceItIsReady() {
        StackResource r = created();

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).clusterFrom(request.capture(), eq("us-east-1"));
        JsonNode sent = request.getValue();
        assertEquals("etl", sent.path("Name").asText());
        assertEquals(json("{\"spark.executor.cores\": \"2\"}"), sent.path("Configurations").get(0).get("Properties"));
        JsonNode groups = sent.path("Instances").path("InstanceGroups");
        assertEquals(List.of("MASTER", "CORE", "TASK"),
                List.of(groups.get(0).path("InstanceRole").asText(), groups.get(1).path("InstanceRole").asText(),
                        groups.get(2).path("InstanceRole").asText()));
        assertEquals(2, groups.get(1).path("InstanceCount").asInt());
        assertTrue(sent.path("Instances").path("TerminationProtected").asBoolean());
        assertFalse(sent.path("Instances").has("CoreInstanceGroup"));
        assertEquals(json("[{\"Key\": \"k\", \"Value\": \"v\"}]"),
                sent.path("Steps").get(0).path("HadoopJarStep").get("Properties"));
        assertEquals(CLUSTER_ID, r.getPhysicalId());
        assertEquals(CLUSTER_ID, r.getAttributes().get("Id"));
        assertEquals("ec2-10-0-0-1.compute-1.amazonaws.com", r.getAttributes().get("MasterPublicDNS"));
        assertNotNull(r.getAttributes().get(EmrCfnProvisioner.CLUSTER_SHAPE_ATTR));
    }

    @Test
    void theClustersOwnSettingsReachRunJobFlowAsTheTemplateGivesThem() {
        when(handler.clusterFrom(any(), any())).thenReturn(new EmrCluster());
        when(emr.runJobFlow(any(), eq("us-east-1"))).thenReturn(emrCluster(CLUSTER_ID, "STARTING"));
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        String settings = """
                , "KerberosAttributes": {"Realm": "EC2.INTERNAL", "KdcAdminPassword": "kdc-secret"},
                "OSReleaseLabel": "2.0.20260727.0", "EbsRootVolumeIops": 4000, "EbsRootVolumeThroughput": 250,
                "PlacementGroupConfigs": [{"InstanceRole": "MASTER", "PlacementStrategy": "SPREAD"}],
                "LogEncryptionKmsKeyId": "key-logs",
                "MonitoringConfiguration": {"CloudWatchLogConfiguration": {"Enabled": true}}""";
        JsonNode template = json(clusterProps(2, "m5.xlarge", settings));
        provisioner.provision(cluster(null), template, ctx(null, null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).clusterFrom(request.capture(), eq("us-east-1"));
        for (String name : List.of("KerberosAttributes", "OSReleaseLabel", "EbsRootVolumeIops",
                "EbsRootVolumeThroughput", "PlacementGroupConfigs", "LogEncryptionKmsKeyId",
                "MonitoringConfiguration")) {
            assertEquals(template.get(name), request.getValue().get(name), name);
        }
    }

    @Test
    void aClusterThatTerminatesBeforeItIsReadyFailsTheCreateAndIsTerminatedAgain() {
        when(handler.clusterFrom(any(), any())).thenReturn(new EmrCluster());
        when(emr.runJobFlow(any(), eq("us-east-1"))).thenReturn(emrCluster(CLUSTER_ID, "STARTING"));
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "TERMINATED_WITH_ERRORS"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(cluster(null),
                json(clusterProps(2, "m5.xlarge", "")), ctx(null, null)));

        assertEquals("NotStabilized", e.getErrorCode());
        verify(emr).setTerminationProtection(List.of(CLUSTER_ID), false);
        verify(emr).terminateJobFlows(List.of(CLUSTER_ID));
    }

    @Test
    void anUpdateAppliesTheMutableSettingsThatChangedAndARollbackPutsThemBack() {
        StackResource r = created();
        when(emr.listInstanceGroups(CLUSTER_ID)).thenReturn(List.of(group("ig-M", "MASTER", null),
                group("ig-C", "CORE", null), group("ig-T", "TASK", "spot")));
        String updated = clusterProps(3, "m5.xlarge", ", \"StepConcurrencyLevel\": 2, \"ManagedScalingPolicy\": "
                + MANAGED_SCALING).replace("\"TerminationProtected\": true", "\"TerminationProtected\": false");

        provisioner.provision(r, json(updated), ctx(CLUSTER_ID, clusterProps(2, "m5.xlarge", "")));
        assertTrue(provisioner.rollbackUpdate(r));

        verify(emr).modifyInstanceGroups(CLUSTER_ID, "us-east-1",
                List.of(new InstanceGroupModification("ig-C", 3, null)));
        verify(emr).modifyInstanceGroups(CLUSTER_ID, "us-east-1",
                List.of(new InstanceGroupModification("ig-C", 2, null)));
        verify(emr).setTerminationProtection(List.of(CLUSTER_ID), false);
        verify(emr).setTerminationProtection(List.of(CLUSTER_ID), true);
        verify(emr).modifyCluster(CLUSTER_ID, 2);
        verify(emr).modifyCluster(CLUSTER_ID, 1);
        verify(emr).putManagedScalingPolicy(CLUSTER_ID, json(MANAGED_SCALING));
        verify(emr).removeManagedScalingPolicy(CLUSTER_ID);
        verify(emr, never()).setVisibleToAllUsers(any(), anyBoolean());
        assertEquals(CLUSTER_ID, r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey(EmrCfnProvisioner.CLUSTER_SNAPSHOT_ATTR));
    }

    @Test
    void anotherCoreInstanceTypeReplacesTheCluster() {
        StackResource r = created();
        when(emr.runJobFlow(any(), eq("us-east-1"))).thenReturn(emrCluster("j-REPLACEMENT", "STARTING"));
        when(emr.describeCluster("j-REPLACEMENT")).thenReturn(emrCluster("j-REPLACEMENT", "WAITING"));

        provisioner.provision(r, json(clusterProps(2, "m5.2xlarge", "")), ctx(CLUSTER_ID, clusterProps(2, "m5.xlarge",
                "")));

        assertEquals("j-REPLACEMENT", r.getPhysicalId());
        assertEquals(CLUSTER_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void anUpdateOfATerminatedClusterFailsAndATemplateThatSetsItsIdIsRefused() {
        StackResource r = created();
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "TERMINATED"));

        AwsException terminated = assertThrows(AwsException.class, () -> provisioner.provision(r,
                json(clusterProps(3, "m5.xlarge", "")), ctx(CLUSTER_ID, clusterProps(2, "m5.xlarge", ""))));
        AwsException readOnly = assertThrows(AwsException.class, () -> provisioner.provision(cluster(null),
                json(clusterProps(2, "m5.xlarge", ", \"Id\": \"j-MINE\"")), ctx(null, null)));

        assertEquals("Resource of type 'AWS::EMR::Cluster' with identifier '" + CLUSTER_ID + "' was not found.",
                terminated.getMessage());
        assertEquals("Id is a ReadOnly property which cannot be specified in the template", readOnly.getMessage());
    }

    @Test
    void aMasterCountChangeReplacesTheClusterAsModifyInstanceGroupsRefusesIt() {
        StackResource r = created();
        when(emr.runJobFlow(any(), eq("us-east-1"))).thenReturn(emrCluster("j-REPLACEMENT", "STARTING"));
        when(emr.describeCluster("j-REPLACEMENT")).thenReturn(emrCluster("j-REPLACEMENT", "WAITING"));
        String twoMasters = clusterProps(2, "m5.xlarge", "").replace(
                "\"MasterInstanceGroup\": {\"InstanceCount\": 1", "\"MasterInstanceGroup\": {\"InstanceCount\": 3");

        provisioner.provision(r, json(twoMasters), ctx(CLUSTER_ID, clusterProps(2, "m5.xlarge", "")));

        assertEquals("j-REPLACEMENT", r.getPhysicalId());
        verify(emr, never()).modifyInstanceGroups(any(), any(), any());
    }

    @Test
    void taskGroupsWithoutAUniqueNameAreRefused() {
        String unnamed = clusterProps(2, "m5.xlarge", "").replace("{\"Name\": \"spot\", ", "{");
        String twice = clusterProps(2, "m5.xlarge", "").replace(
                "[{\"Name\": \"spot\", \"InstanceCount\": 1, \"InstanceType\": \"m5.xlarge\"}]",
                "[{\"Name\": \"spot\", \"InstanceCount\": 1, \"InstanceType\": \"m5.xlarge\"}, "
                        + "{\"Name\": \"spot\", \"InstanceCount\": 2, \"InstanceType\": \"m5.xlarge\"}]");

        for (String props : List.of(unnamed, twice)) {
            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(cluster(null), json(props), ctx(null, null)));
            assertEquals("Each task instance group must have a unique name specified so that CloudFormation can "
                    + "differentiate between the task instance groups.", e.getMessage());
        }
        verifyNoInteractions(emr);
    }

    @Test
    void aClusterThatRanItsStepsAndEndedCameUpSoTheCreateCompletes() {
        when(handler.clusterFrom(any(), any())).thenReturn(new EmrCluster());
        when(emr.runJobFlow(any(), eq("us-east-1"))).thenReturn(emrCluster(CLUSTER_ID, "STARTING"));
        EmrCluster ended = emrCluster(CLUSTER_ID, "TERMINATED");
        ended.setStateChangeReasonCode("ALL_STEPS_COMPLETED");
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(ended);
        StackResource r = cluster(null);

        provisioner.provision(r, json(clusterProps(2, "m5.xlarge", "")), ctx(null, null));

        assertEquals(CLUSTER_ID, r.getPhysicalId());
        verify(emr, never()).terminateJobFlows(any());
    }

    @Test
    void theClusterDeleteTerminatesIt() {
        provisioner.delete("AWS::EMR::Cluster", CLUSTER_ID, "us-east-1");

        verify(emr).terminateJobFlows(List.of(CLUSTER_ID));
    }

    private static StackResource typed(String type, String physicalId) {
        StackResource r = resource(physicalId);
        r.setLogicalId("Child");
        r.setResourceType(type);
        return r;
    }

    private static final String POLICY = """
            {"Constraints": {"MinCapacity": 1, "MaxCapacity": 4}, "Rules": []}""";

    private static String groupProps(int count, String policy) {
        return """
                {"JobFlowId": "%s", "InstanceRole": "TASK", "InstanceType": "m5.xlarge", "InstanceCount": %d,
                 "Name": "spot", "Configurations": [{"Classification": "yarn-site",
                   "ConfigurationProperties": {"yarn.nodemanager.vmem-check-enabled": "false"}}]%s}
                """.formatted(CLUSTER_ID, count, policy == null ? "" : ", \"AutoScalingPolicy\": " + policy);
    }

    private static String fleetProps(int onDemand) {
        return """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": %d,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}
                """.formatted(CLUSTER_ID, onDemand);
    }

    @Test
    void aStepIsAddedWithItsPropertiesAndMonitoringInAddJobFlowStepsMembersAndStaysWhenDeleted() {
        when(emr.addJobFlowSteps(eq(CLUSTER_ID), any())).thenReturn(List.of("s-STEP1"));
        StackResource r = typed("AWS::EMR::Step", null);

        provisioner.provision(r, json("""
                {"JobFlowId": "%s", "Name": "pi", "ActionOnFailure": "CONTINUE", "LogUri": "s3://logs/steps/",
                 "HadoopJarStep": {"Jar": "command-runner.jar", "Args": ["spark-example", "SparkPi"],
                   "StepProperties": [{"Key": "k", "Value": "v"}]}}
                """.formatted(CLUSTER_ID)), ctx(null, null));
        provisioner.delete(r, "us-east-1");

        ArgumentCaptor<JsonNode> steps = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).stepsFrom(steps.capture(), eq(null));
        JsonNode step = steps.getValue().get(0);
        assertEquals(json("[{\"Key\": \"k\", \"Value\": \"v\"}]"), step.path("HadoopJarStep").get("Properties"));
        assertEquals("s3://logs/steps/",
                step.path("StepMonitoringConfiguration").path("S3MonitoringConfiguration").path("LogUri").asText());
        assertEquals("s-STEP1", r.getPhysicalId());
        assertEquals("s-STEP1", r.getAttributes().get("Id"));
        verify(emr, never()).cancelSteps(any(), any(), any());
    }

    @Test
    void anInstanceGroupIsAddedThenResizedAndGivenAPolicyInPlaceAndARollbackPutsThemBack() {
        when(emr.addInstanceGroups(eq(CLUSTER_ID), any())).thenReturn(List.of("ig-TASK"));
        StackResource r = typed("AWS::EMR::InstanceGroupConfig", null);
        provisioner.provision(r, json(groupProps(1, null)), ctx(null, null));
        ArgumentCaptor<JsonNode> groups = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler).instanceGroupsFrom(groups.capture());
        assertEquals("TASK", groups.getValue().get(0).path("InstanceRole").asText());
        assertEquals("false", groups.getValue().get(0).path("Configurations").get(0).path("Properties")
                .path("yarn.nodemanager.vmem-check-enabled").asText());
        assertEquals("ig-TASK", r.getAttributes().get("InstanceGroupId"));

        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        EmrInstanceGroup live = group("ig-TASK", "TASK", "spot");
        live.setRequestedInstanceCount(1);
        when(emr.listInstanceGroups(CLUSTER_ID)).thenReturn(List.of(live));
        provisioner.provision(r, json(groupProps(3, POLICY)), ctx("ig-TASK", groupProps(1, null)));
        provisioner.rollbackUpdate(r);

        verify(emr).modifyInstanceGroups(CLUSTER_ID, "us-east-1",
                List.of(new InstanceGroupModification("ig-TASK", 3, null)));
        verify(emr).putAutoScalingPolicy(CLUSTER_ID, "ig-TASK", json(POLICY));
        verify(emr).modifyInstanceGroups(CLUSTER_ID, "us-east-1",
                List.of(new InstanceGroupModification("ig-TASK", 1, null)));
        verify(emr).removeAutoScalingPolicy(CLUSTER_ID, "ig-TASK");
        assertEquals("ig-TASK", r.getPhysicalId());
        // The policy the group has again is the one the next update and the delete compare with: none.
        assertFalse(r.getAttributes().containsKey(EmrCfnProvisioner.GROUP_POLICY_ATTR));
    }

    @Test
    void anInstanceFleetIsResizedInPlaceAndARollbackPutsItsCapacityBack() {
        when(handler.instanceFleetFrom(any())).thenReturn(new EmrInstanceFleet());
        when(emr.addInstanceFleet(eq(CLUSTER_ID), any())).thenReturn("if-TASK");
        StackResource r = typed("AWS::EMR::InstanceFleetConfig", null);
        provisioner.provision(r, json(fleetProps(1)), ctx(null, null));
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        EmrInstanceFleet live = new EmrInstanceFleet();
        live.setId("if-TASK");
        live.setTargetOnDemandCapacity(1);
        when(emr.listInstanceFleets(CLUSTER_ID)).thenReturn(List.of(live));

        provisioner.provision(r, json(fleetProps(4)), ctx("if-TASK", fleetProps(1)));
        provisioner.rollbackUpdate(r);

        verify(emr).modifyInstanceFleet(CLUSTER_ID, "if-TASK",
                new InstanceFleetModification(4, 0, null, null, null, null));
        verify(emr).modifyInstanceFleet(CLUSTER_ID, "if-TASK",
                new InstanceFleetModification(1, 0, null, null, null, null));
        assertEquals("if-TASK", r.getAttributes().get("InstanceFleetId"));
    }

    @Test
    void anInstanceFleetsConfigsAndResizeSpecificationsChangeInPlaceAndARollbackKeepsAnAddedType() {
        String before = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 1,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}
                """.formatted(CLUSTER_ID);
        String after = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 1,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge", "WeightedCapacity": 2,
                   "Configurations": [{"Classification": "spark", "ConfigurationProperties": {"a": "b"}}]},
                  {"InstanceType": "r5.xlarge"}],
                 "ResizeSpecifications": {"SpotResizeSpecification": {"TimeoutDurationMinutes": 20}}}
                """.formatted(CLUSTER_ID);
        StackResource r = typed("AWS::EMR::InstanceFleetConfig", "if-TASK");
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        EmrInstanceFleet live = new EmrInstanceFleet();
        live.setId("if-TASK");
        live.setTargetOnDemandCapacity(1);
        live.setInstanceTypeConfigs("[{\"InstanceType\":\"m5.xlarge\"}]");
        EmrInstanceFleet updated = new EmrInstanceFleet();
        updated.setId("if-TASK");
        updated.setInstanceTypeConfigs("[{\"InstanceType\":\"m5.xlarge\",\"WeightedCapacity\":2},"
                + "{\"InstanceType\":\"r5.xlarge\"}]");
        updated.setInstanceTypes(new ArrayList<>(List.of("m5.xlarge", "r5.xlarge")));
        when(emr.listInstanceFleets(CLUSTER_ID)).thenReturn(List.of(live), List.of(updated));

        provisioner.provision(r, json(after), ctx("if-TASK", before));
        provisioner.rollbackUpdate(r);

        ArgumentCaptor<InstanceFleetModification> modifications =
                ArgumentCaptor.forClass(InstanceFleetModification.class);
        verify(emr, times(2)).modifyInstanceFleet(eq(CLUSTER_ID), eq("if-TASK"), modifications.capture());
        InstanceFleetModification update = modifications.getAllValues().get(0);
        assertEquals(json("{\"SpotResizeSpecification\":{\"TimeoutDurationMinutes\":20}}"),
                json(update.resizeSpecifications()));
        assertEquals(json("""
                [{"InstanceType": "m5.xlarge", "WeightedCapacity": 2,
                  "Configurations": [{"Classification": "spark", "Properties": {"a": "b"}}]},
                 {"InstanceType": "r5.xlarge"}]"""), json(update.instanceTypeConfigs()));
        assertEquals(List.of("m5.xlarge", "r5.xlarge"), update.instanceTypes());
        // The configs go back, but r5.xlarge stays: ModifyInstanceFleet refuses configs that leave a type out. The
        // fleet had no resize specifications, which no request can clear, so they stay too.
        InstanceFleetModification restore = modifications.getAllValues().get(1);
        assertNull(restore.resizeSpecifications());
        assertEquals(json("[{\"InstanceType\":\"m5.xlarge\"},{\"InstanceType\":\"r5.xlarge\"}]"),
                json(restore.instanceTypeConfigs()));
        assertEquals(List.of("m5.xlarge", "r5.xlarge"), restore.instanceTypes());
    }

    @Test
    void anInstanceGroupOrFleetDeleteShrinksItAndOneWhoseClusterEndedIsLeftAlone() {
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        EmrInstanceGroup withPolicy = group("ig-TASK", "TASK", "spot");
        withPolicy.setAutoScalingPolicy(POLICY);
        when(emr.listInstanceGroups(CLUSTER_ID)).thenReturn(List.of(withPolicy));
        StackResource group = typed("AWS::EMR::InstanceGroupConfig", "ig-TASK");
        group.getAttributes().put(EmrCfnProvisioner.CLUSTER_ID_ATTR, CLUSTER_ID);
        StackResource fleet = typed("AWS::EMR::InstanceFleetConfig", "if-TASK");
        fleet.getAttributes().put(EmrCfnProvisioner.CLUSTER_ID_ATTR, CLUSTER_ID);
        StackResource orphan = typed("AWS::EMR::InstanceGroupConfig", "ig-GONE");
        orphan.getAttributes().put(EmrCfnProvisioner.CLUSTER_ID_ATTR, "j-ENDED");
        when(emr.describeCluster("j-ENDED")).thenReturn(emrCluster("j-ENDED", "TERMINATED"));

        provisioner.delete(group, "us-east-1");
        provisioner.delete(fleet, "us-east-1");
        provisioner.delete(orphan, "us-east-1");

        verify(emr).removeAutoScalingPolicy(CLUSTER_ID, "ig-TASK");
        verify(emr).modifyInstanceGroups(CLUSTER_ID, "us-east-1",
                List.of(new InstanceGroupModification("ig-TASK", 0, null)));
        verify(emr).modifyInstanceFleet(CLUSTER_ID, "if-TASK", 0, 0);
        verify(emr, never()).modifyInstanceGroups(eq("j-ENDED"), any(), any());
    }

    @Test
    void anInstanceGroupOrFleetOfAnotherRoleThanTaskIsRefused() {
        AwsException group = assertThrows(AwsException.class, () -> provisioner.provision(
                typed("AWS::EMR::InstanceGroupConfig", null),
                json(groupProps(1, null).replace("\"TASK\"", "\"CORE\"")), ctx(null, null)));
        AwsException fleet = assertThrows(AwsException.class, () -> provisioner.provision(
                typed("AWS::EMR::InstanceFleetConfig", null),
                json(fleetProps(1).replace("\"TASK\"", "\"MASTER\"")), ctx(null, null)));

        assertEquals("InstanceRole must be TASK, the one value the template reference allows, not CORE.",
                group.getMessage());
        assertEquals("InstanceFleetType must be TASK, the one value the template reference allows, not MASTER.",
                fleet.getMessage());
        verifyNoInteractions(emr);
    }

    @Test
    void aFleetWhoseClusterIsStillStartingIsShrunkOnceItRuns() {
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "STARTING"),
                emrCluster(CLUSTER_ID, "BOOTSTRAPPING"), emrCluster(CLUSTER_ID, "WAITING"));
        StackResource fleet = typed("AWS::EMR::InstanceFleetConfig", "if-TASK");
        fleet.getAttributes().put(EmrCfnProvisioner.CLUSTER_ID_ATTR, CLUSTER_ID);

        provisioner.delete(fleet, "us-east-1");

        verify(emr).modifyInstanceFleet(CLUSTER_ID, "if-TASK", 0, 0);
    }

    @Test
    void droppedFleetConfigsAreKeptAndAFleetStoredWithItsTypesAloneGetsThemBackOnRollback() {
        String before = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 1,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}
                """.formatted(CLUSTER_ID);
        String dropped = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 2}
                """.formatted(CLUSTER_ID);
        String weighted = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 1,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge", "WeightedCapacity": 4}]}
                """.formatted(CLUSTER_ID);
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        EmrInstanceFleet live = new EmrInstanceFleet();
        live.setId("if-TASK");
        live.setTargetOnDemandCapacity(1);
        live.setInstanceTypes(new ArrayList<>(List.of("m5.xlarge")));
        when(emr.listInstanceFleets(CLUSTER_ID)).thenReturn(List.of(live));

        // Dropping the configs resizes the fleet and leaves its configs as they are.
        provisioner.provision(typed("AWS::EMR::InstanceFleetConfig", "if-TASK"), json(dropped),
                ctx("if-TASK", before));
        verify(emr).modifyInstanceFleet(CLUSTER_ID, "if-TASK",
                new InstanceFleetModification(2, 0, null, null, null, null));

        // A fleet stored with its instance types alone gets a config naming each back, not the update's weight.
        StackResource r = typed("AWS::EMR::InstanceFleetConfig", "if-TASK");
        provisioner.provision(r, json(weighted), ctx("if-TASK", before));
        provisioner.rollbackUpdate(r);
        ArgumentCaptor<InstanceFleetModification> modifications =
                ArgumentCaptor.forClass(InstanceFleetModification.class);
        verify(emr, times(3)).modifyInstanceFleet(eq(CLUSTER_ID), eq("if-TASK"), modifications.capture());
        assertEquals(json("[{\"InstanceType\":\"m5.xlarge\"}]"),
                json(modifications.getAllValues().get(2).instanceTypeConfigs()));
    }

    @Test
    void aFleetUpdateWhoseConfigsBreakTheVolumeLimitIsRefusedBeforeAnythingChanges() {
        String before = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 1,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}
                """.formatted(CLUSTER_ID);
        String tooMany = """
                {"ClusterId": "%s", "InstanceFleetType": "TASK", "Name": "tasks", "TargetOnDemandCapacity": 3,
                 "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge", "EbsConfiguration": {"EbsBlockDeviceConfigs": [
                   {"VolumeSpecification": {"VolumeType": "gp3", "SizeInGB": 10}, "VolumesPerInstance": 26}]}}]}
                """.formatted(CLUSTER_ID);
        when(emr.describeCluster(CLUSTER_ID)).thenReturn(emrCluster(CLUSTER_ID, "WAITING"));
        EmrInstanceFleet live = new EmrInstanceFleet();
        live.setId("if-TASK");
        live.setTargetOnDemandCapacity(1);
        live.setInstanceTypeConfigs("[{\"InstanceType\":\"m5.xlarge\"}]");
        when(emr.listInstanceFleets(CLUSTER_ID)).thenReturn(List.of(live));

        AwsException refused = assertThrows(AwsException.class, () -> provisioner.provision(
                typed("AWS::EMR::InstanceFleetConfig", "if-TASK"), json(tooMany), ctx("if-TASK", before)));

        assertEquals("There is a limit of 25 volumes per instance.", refused.getMessage());
        verify(emr, never()).modifyInstanceFleet(eq(CLUSTER_ID), eq("if-TASK"), any(InstanceFleetModification.class));
    }
}
