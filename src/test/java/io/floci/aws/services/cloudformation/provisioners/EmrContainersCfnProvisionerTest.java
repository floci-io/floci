package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emrcontainers.EmrContainersService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code AWS::EMRContainers::VirtualCluster} in isolation, with the EMR on EKS service mocked. */
class EmrContainersCfnProvisionerTest {

    private static final String ID = "vc0123456789abcdefghijklm";
    private static final String ARN = "arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/" + ID;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMPLATE = """
            {"Name": "spark", "ContainerProvider": {"Type": "EKS", "Id": "eks-1",
              "Info": {"EksInfo": {"Namespace": "jobs"}}}, "Tags": [{"Key": "stage", "Value": "prod"}]}
            """;
    private static final String PRIOR = """
            {"Name": "spark", "ContainerProvider": {"Type": "EKS", "Id": "eks-1",
              "Info": {"EksInfo": {"Namespace": "jobs"}}}, "Tags": [{"Key": "team", "Value": "data"}]}
            """;

    private final EmrContainersService service = mock(EmrContainersService.class);
    private final EmrContainersCfnProvisioner provisioner = new EmrContainersCfnProvisioner(service);

    private static ProvisionContext ctx(String priorPhysicalId, String priorProperties) {
        return new ProvisionContext(CloudFormationTemplateEngine.standalone("000000000000", "us-east-1", "my-stack",
                MAPPER, null), "us-east-1", "000000000000", "my-stack", priorPhysicalId, event -> { },
                priorProperties == null ? null : json(priorProperties));
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Cluster");
        r.setResourceType("AWS::EMRContainers::VirtualCluster");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static StackResource provisioned() {
        StackResource r = resource();
        r.setPhysicalId(ID);
        r.getAttributes().put("Id", ID);
        r.getAttributes().put("Arn", ARN);
        return r;
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

    private void stubCluster(String state, Map<String, String> tags) {
        ObjectNode cluster = MAPPER.createObjectNode().put("id", ID).put("arn", ARN).put("state", state);
        cluster.set("tags", MAPPER.valueToTree(tags));
        ObjectNode described = MAPPER.createObjectNode();
        described.set("virtualCluster", cluster);
        when(service.describeVirtualCluster(ID)).thenReturn(described);
    }

    @Test
    void createsTheVirtualClusterWithTheTemplateInTheApiShape() {
        when(service.createVirtualCluster(any())).thenReturn(
                MAPPER.createObjectNode().put("id", ID).put("name", "spark").put("arn", ARN));
        StackResource r = resource();

        provisioner.provision(r, json("""
                {"Name": "spark", "ContainerProvider": {"Type": "EKS", "Id": "eks-1",
                  "Info": {"EksInfo": {"Namespace": "jobs"}}}, "SecurityConfigurationId": "abc123",
                 "SessionEnabled": "true", "Tags": [{"Key": "stage", "Value": "prod"}]}
                """), ctx(null, null));

        ArgumentCaptor<ObjectNode> created = ArgumentCaptor.forClass(ObjectNode.class);
        verify(service).createVirtualCluster(created.capture());
        ObjectNode request = created.getValue();
        assertFalse(request.path("clientToken").asText().isBlank());
        request.remove("clientToken");
        assertEquals(json("""
                {"name": "spark", "containerProvider": {"type": "EKS", "id": "eks-1",
                  "info": {"eksInfo": {"namespace": "jobs"}}}, "tags": {"stage": "prod"},
                 "securityConfigurationId": "abc123", "sessionEnabled": true}
                """), request);
        assertEquals(ID, r.getPhysicalId());
        assertEquals(ID, r.getAttributes().get("Id"));
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void aTemplateTheHandlerRefusesFailsBeforeAnyCall() {
        AwsException missing = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), json("{\"Name\": \"spark\"}"), ctx(null, null)));
        ObjectNode withArn = (ObjectNode) json(TEMPLATE);
        withArn.put("Arn", ARN);
        AwsException readOnly = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), withArn, ctx(null, null)));

        assertEquals("Model validation failed (#: required key [ContainerProvider] not found)", missing.getMessage());
        assertEquals("Arn is a ReadOnly property which cannot be specified in the template", readOnly.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void anUpdateDrivesTheTagsToTheTemplatesKeepingAnAwsOne() {
        stubCluster("RUNNING", tags("team", "data"));
        when(service.listTags(ARN)).thenReturn(tags("team", "data", "aws:cloudformation:stack-name", "my-stack"));
        StackResource r = provisioned();

        provisioner.provision(r, json(TEMPLATE), ctx(ID, PRIOR));

        verify(service).untagResource(ARN, List.of("team"));
        verify(service).tagResource(ARN, Map.of("stage", "prod"));
        verify(service, never()).createVirtualCluster(any());
        assertEquals(ID, r.getPhysicalId());
        assertTrue(r.getAttributes().containsKey(EmrContainersCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aRollbackPutsBackTheTagsTheUpdateChanged() {
        stubCluster("RUNNING", tags("team", "data"));
        when(service.listTags(ARN)).thenReturn(tags("team", "data"), tags("stage", "prod"));
        StackResource r = provisioned();
        provisioner.provision(r, json(TEMPLATE), ctx(ID, PRIOR));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(service).untagResource(ARN, List.of("stage"));
        verify(service).tagResource(ARN, Map.of("team", "data"));
        assertFalse(r.getAttributes().containsKey(EmrContainersCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aTagFailurePutsTheTagsBackBeforeItPropagates() {
        stubCluster("RUNNING", tags("team", "data"));
        when(service.listTags(ARN)).thenReturn(tags("team", "data"), tags());
        AwsException tagging = new AwsException("ValidationException", "tagging", 400);
        doThrow(tagging).doNothing().when(service).tagResource(any(), any());
        StackResource r = provisioned();

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, json(TEMPLATE), ctx(ID, PRIOR)));

        assertEquals(tagging, thrown);
        verify(service).tagResource(ARN, Map.of("team", "data"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void anUpdateOfAVirtualClusterThatIsTerminatedOrGoneFailsAsTheHandlerDoes() {
        stubCluster("TERMINATED", tags());
        AwsException terminated = assertThrows(AwsException.class,
                () -> provisioner.provision(provisioned(), json(TEMPLATE), ctx(ID, PRIOR)));
        when(service.describeVirtualCluster(ID))
                .thenThrow(new AwsException("ResourceNotFoundException", "Virtual cluster doesn't exist.", 404));
        AwsException gone = assertThrows(AwsException.class,
                () -> provisioner.provision(provisioned(), json(TEMPLATE), ctx(ID, PRIOR)));

        String message = "Resource of type 'AWS::EMRContainers::VirtualCluster' with identifier '" + ID
                + "' was not found.";
        assertEquals(message, terminated.getMessage());
        assertEquals(message, gone.getMessage());
        verify(service, never()).tagResource(any(), any());
    }

    @Test
    void aNewNamespaceReplacesTheVirtualClusterAndLeavesTheOldOneForTheCleanup() {
        when(service.createVirtualCluster(any())).thenReturn(MAPPER.createObjectNode().put("id", "vcnew")
                .put("name", "spark").put("arn", ARN.replace(ID, "vcnew")));
        StackResource r = provisioned();

        provisioner.provision(r, json(TEMPLATE.replace("\"jobs\"", "\"etl\"")), ctx(ID, PRIOR));

        assertEquals("vcnew", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(ID, provisioner.updateCleanupPhysicalId(r));
        verify(service, never()).describeVirtualCluster(any());
    }

    @Test
    void anUndoThatFailedLeavesItsSnapshotForContinueUpdateRollback() {
        StackResource previous = provisioned();
        StackResource attempted = provisioned();
        attempted.getAttributes().put(EmrContainersCfnProvisioner.SNAPSHOT_ATTR, "{}");
        attempted.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, "Could not restore");

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals("{}", previous.getAttributes().get(EmrContainersCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void theDeleteTerminatesTheVirtualClusterAndTakesAGoneOneAsDeleted() {
        stubCluster("RUNNING", tags());
        when(service.describeVirtualCluster("vcgone"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Virtual cluster doesn't exist.", 404));

        provisioner.delete("AWS::EMRContainers::VirtualCluster", ID, "us-east-1");
        provisioner.delete("AWS::EMRContainers::VirtualCluster", "vcgone", "us-east-1");

        verify(service).deleteVirtualCluster(ID);
        verify(service, never()).deleteVirtualCluster("vcgone");
    }
}
