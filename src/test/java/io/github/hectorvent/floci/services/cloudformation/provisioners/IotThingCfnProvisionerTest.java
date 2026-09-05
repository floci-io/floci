package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotService;
import io.github.hectorvent.floci.services.iot.model.Thing;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.REGION;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.ctx;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.notFound;
import static io.github.hectorvent.floci.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IoT::Thing} through the IoT CFN provisioner in isolation: one mocked service. Every
 * case asserts the exact physical id and the exact {@code Fn::GetAtt} attribute keys, because an
 * unmapped type still reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class IotThingCfnProvisionerTest {

    private static final String TYPE = "AWS::IoT::Thing";
    private static final String ARN = "arn:aws:iot:us-east-1:000000000000:thing/sensor-1";
    private static final String THING_ID = "11111111-2222-3333-4444-555555555555";

    private final IotService iot = mock(IotService.class);
    private final IotCfnProvisioner provisioner = new IotCfnProvisioner(iot);
    private final ObjectMapper mapper = new ObjectMapper();

    private static Thing thing(String name, Map<String, String> attributes) {
        Thing thing = new Thing();
        thing.setThingName(name);
        thing.setThingArn("arn:aws:iot:us-east-1:000000000000:thing/" + name);
        thing.setThingId(THING_ID);
        thing.setAttributes(attributes);
        return thing;
    }

    private ObjectNode props(String name, Map<String, String> attributes) {
        ObjectNode props = mapper.createObjectNode();
        if (name != null) {
            props.put("ThingName", name);
        }
        if (attributes != null) {
            ObjectNode attrs = props.putObject("AttributePayload").putObject("Attributes");
            attributes.forEach(attrs::put);
        }
        return props;
    }

    private Map<String, String> capturedAttributes(String thingName) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(iot).createThing(eq(thingName), captor.capture(), eq(REGION));
        return captor.getValue();
    }

    @Test
    void resourceTypesCoverThingPolicyAndTopicRule() {
        assertEquals(Set.of(TYPE, "AWS::IoT::Policy", "AWS::IoT::TopicRule"), provisioner.resourceTypes());
    }

    @Test
    void thingSetsNameAsPhysicalIdAndArnAndIdAttributes() {
        Map<String, String> attributes = Map.of("SerialNumber", "SN-1", "Model", "M2");
        when(iot.createThing(eq("sensor-1"), any(), eq(REGION))).thenReturn(thing("sensor-1", attributes));
        StackResource r = resource(TYPE, "Sensor");

        provisioner.provision(r, props("sensor-1", attributes), ctx());

        assertEquals("sensor-1", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals(THING_ID, r.getAttributes().get("Id"));
        assertEquals(attributes, capturedAttributes("sensor-1"));
    }

    @Test
    void thingWithoutAttributePayloadIsCreatedWithNoAttributes() {
        when(iot.createThing(eq("sensor-1"), any(), eq(REGION))).thenReturn(thing("sensor-1", Map.of()));
        StackResource r = resource(TYPE, "Sensor");

        provisioner.provision(r, props("sensor-1", null), ctx());

        assertEquals(Map.of(), capturedAttributes("sensor-1"));
    }

    @Test
    void thingWithoutNameGetsAGeneratedNameWithinTheLimit() {
        when(iot.createThing(anyString(), any(), eq(REGION))).thenAnswer(inv -> thing(inv.getArgument(0), Map.of()));
        StackResource r = resource(TYPE, "Sensor");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        String name = r.getPhysicalId();
        assertTrue(name.matches("my-stack-Sensor-[0-9a-f]{12}"), "generated thing name: " + name);
        assertTrue(name.length() <= 128);
        assertEquals("arn:aws:iot:us-east-1:000000000000:thing/" + name, r.getAttributes().get("Arn"));
    }

    @Test
    void thingUpdateWithTheSameNameReplacesTheAttributesInPlace() {
        Map<String, String> attributes = Map.of("Model", "M3");
        when(iot.updateThing(eq("sensor-1"), any(), isNull(), eq(REGION))).thenReturn(thing("sensor-1", attributes));
        StackResource r = resource(TYPE, "Sensor");

        provisioner.provision(r, props("sensor-1", attributes), ctx("sensor-1"));

        verify(iot, never()).createThing(anyString(), any(), anyString());
        verify(iot, never()).deleteThing(anyString(), anyString());
        assertEquals("sensor-1", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals(THING_ID, r.getAttributes().get("Id"));
    }

    @Test
    void thingUpdateWithoutAnExplicitNameKeepsThePriorGeneratedName() {
        when(iot.updateThing(eq("my-stack-Sensor-0123456789ab"), any(), isNull(), eq(REGION)))
                .thenReturn(thing("my-stack-Sensor-0123456789ab", Map.of()));
        StackResource r = resource(TYPE, "Sensor");

        provisioner.provision(r, mapper.createObjectNode(), ctx("my-stack-Sensor-0123456789ab"));

        verify(iot, never()).createThing(anyString(), any(), anyString());
        assertEquals("my-stack-Sensor-0123456789ab", r.getPhysicalId());
    }

    @Test
    void thingRenameCreatesTheReplacementThenDeletesThePriorThing() {
        when(iot.createThing(eq("sensor-2"), any(), eq(REGION))).thenReturn(thing("sensor-2", Map.of()));
        StackResource r = resource(TYPE, "Sensor");

        provisioner.provision(r, props("sensor-2", null), ctx("sensor-1"));

        verify(iot).deleteThing("sensor-1", REGION);
        verify(iot, never()).updateThing(anyString(), any(), any(), anyString());
        assertEquals("sensor-2", r.getPhysicalId());
        assertEquals("arn:aws:iot:us-east-1:000000000000:thing/sensor-2", r.getAttributes().get("Arn"));
    }

    @Test
    void thingRenameToleratesAPriorThingThatIsAlreadyGone() {
        when(iot.createThing(eq("sensor-2"), any(), eq(REGION))).thenReturn(thing("sensor-2", Map.of()));
        doThrow(notFound("Thing")).when(iot).deleteThing("sensor-1", REGION);
        StackResource r = resource(TYPE, "Sensor");

        assertDoesNotThrow(() -> provisioner.provision(r, props("sensor-2", null), ctx("sensor-1")));

        assertEquals("sensor-2", r.getPhysicalId());
    }

    @Test
    void thingRenameKeepsTheUpdateWhenThePriorThingCannotBeDeleted() {
        // As in CloudFormation's cleanup phase: the new thing exists and the stack points at it,
        // so a failed removal of the old one is logged and the update completes.
        when(iot.createThing(eq("sensor-2"), any(), eq(REGION))).thenReturn(thing("sensor-2", Map.of()));
        doThrow(new AwsException("InvalidRequestException", "Cannot delete", 400)).when(iot).deleteThing("sensor-1", REGION);
        StackResource r = resource(TYPE, "Sensor");

        assertDoesNotThrow(() -> provisioner.provision(r, props("sensor-2", null), ctx("sensor-1")));

        assertEquals("sensor-2", r.getPhysicalId());
    }

    @Test
    void deleteThingDelegatesToTheService() {
        provisioner.delete(TYPE, "sensor-1", REGION);

        verify(iot).deleteThing("sensor-1", REGION);
    }

    @Test
    void deleteThingToleratesOnlyNotFound() {
        doThrow(notFound("Thing")).when(iot).deleteThing("gone", REGION);
        doThrow(new AwsException("InvalidRequestException", "Cannot delete", 400)).when(iot).deleteThing("held", REGION);

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", REGION));
        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "held", REGION));
        assertEquals("InvalidRequestException", e.getErrorCode());
    }
}
