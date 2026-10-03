package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Lambda::MicrovmImage} and {@code AWS::Lambda::NetworkConnector} in isolation. Both are
 * identified by their ARN (the registry schemas' primaryIdentifier), and {@code Name} is the only
 * createOnly property of either: an update that keeps it goes through the schema's update handler,
 * one that changes it creates a replacement and deletes the displaced entity after the commit.
 */
class LambdaMicrovmsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String IMAGE_TYPE = "AWS::Lambda::MicrovmImage";
    private static final String CONNECTOR_TYPE = "AWS::Lambda::NetworkConnector";
    private static final String BASE_IMAGE = "arn:aws:lambda:us-east-1::base-image:nodejs";
    private static final String BUILD_ROLE = "arn:aws:iam::000000000000:role/build";
    private static final String OPERATOR_ROLE = "arn:aws:iam::000000000000:role/operator";
    private static final String CODE_URI = "s3://bucket/code.zip";

    private final LambdaMicrovmsService service = mock(LambdaMicrovmsService.class);
    private final LambdaMicrovmsCfnProvisioner provisioner = new LambdaMicrovmsCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Res");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    /** What CfnResourceDispatcher hands provision on UpdateStack: the prior physical id and attributes. */
    private static StackResource updateOf(StackResource prior) {
        StackResource r = resource(prior.getResourceType());
        r.setPhysicalId(prior.getPhysicalId());
        r.setAttributes(new HashMap<>(prior.getAttributes()));
        return r;
    }

    private ObjectNode imageProps(String name) {
        ObjectNode props = mapper.createObjectNode()
                .put("BaseImageArn", BASE_IMAGE)
                .put("BuildRoleArn", BUILD_ROLE)
                .put("Description", "an image");
        if (name != null) {
            props.put("Name", name);
        }
        props.set("CodeArtifact", mapper.createObjectNode().put("Uri", CODE_URI));
        return props;
    }

    private ObjectNode connectorProps(String name, String subnet) {
        ObjectNode props = mapper.createObjectNode().put("OperatorRole", OPERATOR_ROLE);
        if (name != null) {
            props.put("Name", name);
        }
        ObjectNode vpc = props.putObject("Configuration").putObject("VpcEgressConfiguration");
        vpc.put("NetworkProtocol", "IPv4");
        vpc.putArray("SubnetIds").add(subnet);
        vpc.putArray("SecurityGroupIds").add("sg-1");
        vpc.putArray("AssociatedComputeResourceTypes").add("MICROVM");
        return props;
    }

    private static String imageArn(String name) {
        return "arn:aws:lambda:us-east-1:000000000000:microvm-image:" + name;
    }

    private static LambdaMicrovmsService.MicrovmImage image(String name, String version) {
        LambdaMicrovmsService.MicrovmImage image = new LambdaMicrovmsService.MicrovmImage();
        image.name = name;
        image.imageArn = imageArn(name);
        image.latestActiveImageVersion = version;
        return image;
    }

    private static LambdaMicrovmsService.NetworkConnector connector(String id, String name) {
        LambdaMicrovmsService.NetworkConnector connector = new LambdaMicrovmsService.NetworkConnector();
        connector.id = id;
        connector.name = name;
        connector.arn = "arn:aws:lambda:us-east-1:000000000000:network-connector:" + id;
        connector.subnetIds = List.of("subnet-1");
        connector.securityGroupIds = List.of("sg-1");
        connector.operatorRole = OPERATOR_ROLE;
        connector.networkProtocol = "IPv4";
        connector.associatedComputeResourceTypes = List.of("MICROVM");
        return connector;
    }

    private void stubImageCreateAndUpdate() {
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(2), "1.0"));
        when(service.updateImage(anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(1), "2.0"));
    }

    @Test
    void imageRefIsTheImageArnAndTheGetAttAttributesAreSet() {
        when(service.createImage(eq(REGION), eq("000000000000"), eq("img"), eq(BASE_IMAGE),
                eq(BUILD_ROLE), eq(CODE_URI), eq("an image"))).thenReturn(image("img", "1.0"));
        StackResource r = resource(IMAGE_TYPE);

        provisioner.provision(r, imageProps("img"), ctx(null));

        assertEquals(imageArn("img"), r.getPhysicalId());
        assertEquals(imageArn("img"), r.getAttributes().get("ImageArn"));
        assertEquals(imageArn("img"), r.getAttributes().get("Arn"));
        assertEquals("img", r.getAttributes().get("Name"));
        assertEquals("1.0", r.getAttributes().get("LatestActiveImageVersion"));
    }

    @Test
    void anUnnamedImageKeepsItsNameAndIsUpdatedRatherThanRecreated() {
        stubImageCreateAndUpdate();
        StackResource created = resource(IMAGE_TYPE);
        provisioner.provision(created, imageProps(null), ctx(null));
        String generatedName = created.getAttributes().get("Name");
        assertTrue(generatedName.startsWith("my-stack-Res-"), generatedName);

        StackResource updated = updateOf(created);
        provisioner.provision(updated, imageProps(null), ctx(created.getPhysicalId()));

        assertEquals(imageArn(generatedName), updated.getPhysicalId());
        assertEquals("2.0", updated.getAttributes().get("LatestActiveImageVersion"));
        assertFalse(provisioner.hasReplacementUpdate(updated));
        verify(service, times(1)).createImage(anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(service).updateImage(REGION, generatedName, BASE_IMAGE, BUILD_ROLE, CODE_URI, "an image");
    }

    @Test
    void aRenamedImageIsReplacedAndTheDisplacedOneDeletedAfterTheCommit() {
        stubImageCreateAndUpdate();
        StackResource created = resource(IMAGE_TYPE);
        provisioner.provision(created, imageProps("old-name"), ctx(null));

        StackResource updated = updateOf(created);
        provisioner.provision(updated, imageProps("renamed"), ctx(created.getPhysicalId()));

        assertEquals(imageArn("renamed"), updated.getPhysicalId());
        verify(service, never()).updateImage(anyString(), anyString(), any(), any(), any(), any());
        assertTrue(provisioner.hasReplacementUpdate(updated));
        assertEquals(imageArn("old-name"), provisioner.updateCleanupPhysicalId(updated));

        assertTrue(provisioner.completeUpdate(updated).complete());
        verify(service).deleteImage(REGION, imageArn("old-name"));
    }

    @Test
    void anImageFromAStackThatStoredItsNameIsReusedAndOnlyItsIdMovesToTheArn() {
        stubImageCreateAndUpdate();
        StackResource legacy = resource(IMAGE_TYPE);
        legacy.setPhysicalId("img");
        legacy.getAttributes().put("Name", "img");
        legacy.getAttributes().put("ImageArn", imageArn("img"));

        StackResource updated = updateOf(legacy);
        provisioner.provision(updated, imageProps("img"), ctx("img"));

        assertEquals(imageArn("img"), updated.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(updated), "the reused image must not be marked displaced");
        verify(service).updateImage(REGION, "img", BASE_IMAGE, BUILD_ROLE, CODE_URI, "an image");
        verify(service, never()).createImage(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void connectorRefIsTheConnectorArnAndTheGetAttAttributesAreSet() {
        when(service.createConnector(eq(REGION), eq("000000000000"), eq("nc"), eq(List.of("subnet-1")),
                eq(List.of("sg-1")), eq(OPERATOR_ROLE), anyString(), eq(List.of("MICROVM")), eq("IPv4")))
                .thenReturn(connector("nc-1", "nc"));
        StackResource r = resource(CONNECTOR_TYPE);

        provisioner.provision(r, connectorProps("nc", "subnet-1"), ctx(null));

        String arn = connector("nc-1", "nc").arn;
        assertEquals(arn, r.getPhysicalId());
        assertEquals(arn, r.getAttributes().get("Arn"));
        assertEquals("nc-1", r.getAttributes().get("Id"));
        assertEquals("nc", r.getAttributes().get("Name"));
    }

    @Test
    void aConnectorUpdateThatKeepsTheNameUpdatesTheExistingConnector() {
        when(service.createConnector(anyString(), anyString(), anyString(), any(), any(), any(), anyString(), any(),
                any())).thenAnswer(inv -> connector("nc-1", inv.getArgument(2)));
        StackResource created = resource(CONNECTOR_TYPE);
        provisioner.provision(created, connectorProps(null, "subnet-1"), ctx(null));
        String name = created.getAttributes().get("Name");
        when(service.getConnector(REGION, created.getPhysicalId())).thenReturn(connector("nc-1", name));
        when(service.updateConnector(REGION, created.getPhysicalId(), List.of("subnet-2"), List.of("sg-1"),
                OPERATOR_ROLE, "IPv4", List.of("MICROVM"))).thenReturn(connector("nc-1", name));

        StackResource updated = updateOf(created);
        provisioner.provision(updated, connectorProps(null, "subnet-2"), ctx(created.getPhysicalId()));

        assertEquals(created.getPhysicalId(), updated.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(updated));
        verify(service, times(1)).createConnector(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), any());
        verify(service).updateConnector(REGION, created.getPhysicalId(), List.of("subnet-2"), List.of("sg-1"),
                OPERATOR_ROLE, "IPv4", List.of("MICROVM"));
    }

    @Test
    void anInPlaceConnectorUpdateAppliesEverySettingAndRollsBackFromItsSnapshot() {
        String arn = connector("nc-1", "nc").arn;
        StackResource prior = resource(CONNECTOR_TYPE);
        prior.setPhysicalId(arn);
        prior.getAttributes().put("Name", "nc");
        when(service.getConnector(REGION, arn)).thenReturn(connector("nc-1", "nc"));
        when(service.updateConnector(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(connector("nc-1", "nc"));
        ObjectNode props = connectorProps("nc", "subnet-2");
        props.put("OperatorRole", "arn:aws:iam::000000000000:role/operator-v2");
        ObjectNode vpc = (ObjectNode) props.path("Configuration").path("VpcEgressConfiguration");
        vpc.put("NetworkProtocol", "DUAL_STACK");

        StackResource updated = updateOf(prior);
        provisioner.provision(updated, props, ctx(arn));

        verify(service).updateConnector(REGION, arn, List.of("subnet-2"), List.of("sg-1"),
                "arn:aws:iam::000000000000:role/operator-v2", "DUAL_STACK", List.of("MICROVM"));
        assertTrue(provisioner.rollbackUpdate(updated));
        verify(service).updateConnector(REGION, arn, List.of("subnet-1"), List.of("sg-1"), OPERATOR_ROLE, "IPv4",
                List.of("MICROVM"));
        assertFalse(updated.getAttributes().containsKey(CfnRollback.NETWORK_CONNECTOR_UPDATE_SNAPSHOT_ATTR),
                "the snapshot is spent");
    }

    @Test
    void droppingAnExplicitConnectorNameReplacesTheConnector() {
        when(service.createConnector(anyString(), anyString(), anyString(), any(), any(), any(), anyString(), any(),
                any())).thenAnswer(inv -> connector("nc-2", inv.getArgument(2)));
        String arn = connector("nc-1", "explicit-name").arn;
        StackResource prior = resource(CONNECTOR_TYPE);
        prior.setPhysicalId(arn);
        prior.getAttributes().put("Name", "explicit-name");

        StackResource updated = updateOf(prior);
        provisioner.provision(updated, connectorProps(null, "subnet-1"), ctx(arn));

        String name = updated.getAttributes().get("Name");
        assertTrue(name.startsWith("my-stack-Res-"), name);
        assertEquals(arn, provisioner.updateCleanupPhysicalId(updated));
        verify(service, never()).updateConnector(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void onlyANameWithTheGeneratedShapeCountsAsGenerated() {
        assertTrue(LambdaMicrovmsCfnProvisioner.wasGenerated("my-stack-Res-0123456789ab", "my-stack", "Res"));
        assertTrue(LambdaMicrovmsCfnProvisioner.wasGenerated("my-sta-0123456789ab", "my-stack", "Res"),
                "a truncated prefix still counts");
        assertFalse(LambdaMicrovmsCfnProvisioner.wasGenerated("explicit-name", "my-stack", "Res"));
        assertFalse(LambdaMicrovmsCfnProvisioner.wasGenerated("other-stack-Res-0123456789ab", "my-stack", "Res"));
    }

    @Test
    void aRenamedConnectorIsReplacedAndTheDisplacedOneDeletedAfterTheCommit() {
        when(service.createConnector(anyString(), anyString(), eq("first"), any(), any(), any(), anyString(), any(),
                any())).thenReturn(connector("nc-1", "first"));
        when(service.createConnector(anyString(), anyString(), eq("second"), any(), any(), any(), anyString(), any(),
                any())).thenReturn(connector("nc-2", "second"));
        StackResource created = resource(CONNECTOR_TYPE);
        provisioner.provision(created, connectorProps("first", "subnet-1"), ctx(null));

        StackResource updated = updateOf(created);
        provisioner.provision(updated, connectorProps("second", "subnet-1"), ctx(created.getPhysicalId()));

        assertEquals(connector("nc-2", "second").arn, updated.getPhysicalId());
        assertEquals(created.getPhysicalId(), provisioner.updateCleanupPhysicalId(updated));
        assertTrue(provisioner.completeUpdate(updated).complete());
        verify(service).deleteConnector(REGION, created.getPhysicalId());
        verify(service, never()).updateConnector(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void aConnectorFromAStackThatStoredItsIdIsReusedAndOnlyItsIdMovesToTheArn() {
        when(service.getConnector(REGION, "nc-1")).thenReturn(connector("nc-1", "nc"));
        when(service.updateConnector(REGION, "nc-1", List.of("subnet-1"), List.of("sg-1"), OPERATOR_ROLE, "IPv4",
                List.of("MICROVM"))).thenReturn(connector("nc-1", "nc"));
        StackResource legacy = resource(CONNECTOR_TYPE);
        legacy.setPhysicalId("nc-1");
        legacy.getAttributes().put("Name", "nc");

        StackResource updated = updateOf(legacy);
        provisioner.provision(updated, connectorProps("nc", "subnet-1"), ctx("nc-1"));

        assertEquals(connector("nc-1", "nc").arn, updated.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(updated), "the reused connector must not be marked displaced");
        verify(service, never()).createConnector(anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), any(), any());
    }

    @Test
    void deleteToleratesAnAlreadyDeletedEntityButPropagatesARealFailure() {
        String arn = imageArn("img");
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).deleteConnector(REGION, "nc-gone");
        doThrow(new AwsException("ValidationException", "Cannot delete microvm image with running microvms.", 400))
                .when(service).deleteImage(REGION, arn);

        provisioner.delete(CONNECTOR_TYPE, "nc-gone", REGION);
        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete(IMAGE_TYPE, arn, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }
}
