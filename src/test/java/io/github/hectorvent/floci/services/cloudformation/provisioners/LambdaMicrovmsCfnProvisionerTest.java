package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Lambda::MicrovmImage} in isolation. Name is a createOnlyProperty in the registry
 * schema and the physical id, so an unnamed image must keep its generated name across updates and
 * the update must go through UpdateMicrovmImage, the schema's update handler, rather than mint a
 * fresh version through create.
 */
class LambdaMicrovmsCfnProvisionerTest {

    private static final String BASE_IMAGE = "arn:aws:lambda:us-east-1::base-image:nodejs";
    private static final String BUILD_ROLE = "arn:aws:iam::000000000000:role/build";
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
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("MyImage");
        r.setResourceType("AWS::Lambda::MicrovmImage");
        return r;
    }

    private ObjectNode props(String name) {
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

    private static LambdaMicrovmsService.MicrovmImage image(String name, String version) {
        LambdaMicrovmsService.MicrovmImage image = new LambdaMicrovmsService.MicrovmImage();
        image.name = name;
        image.imageArn = "arn:aws:lambda:us-east-1:000000000000:microvm-image:" + name;
        image.latestActiveImageVersion = version;
        return image;
    }

    @Test
    void createSetsThePhysicalIdAndTheGetAttAttributes() {
        when(service.createImage(eq("us-east-1"), eq("000000000000"), eq("img"), eq(BASE_IMAGE),
                eq(BUILD_ROLE), eq(CODE_URI), eq("an image"))).thenReturn(image("img", "1.0"));
        StackResource r = resource();

        provisioner.provision(r, props("img"), ctx(null));

        assertEquals("img", r.getPhysicalId());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:microvm-image:img", r.getAttributes().get("ImageArn"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:microvm-image:img", r.getAttributes().get("Arn"));
        assertEquals("img", r.getAttributes().get("Name"));
        assertEquals("1.0", r.getAttributes().get("LatestActiveImageVersion"));
    }

    @Test
    void anUnnamedImageKeepsItsNameAndIsUpdatedRatherThanRecreated() {
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(2), "1.0"));
        when(service.updateImage(anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(1), "2.0"));
        StackResource created = resource();
        provisioner.provision(created, props(null), ctx(null));
        String generatedName = created.getPhysicalId();
        assertTrue(generatedName.startsWith("my-stack-MyImage-"), generatedName);

        StackResource updated = resource();
        provisioner.provision(updated, props(null), ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId());
        assertEquals("2.0", updated.getAttributes().get("LatestActiveImageVersion"));
        verify(service, times(1)).createImage(anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(service).updateImage("us-east-1", generatedName, BASE_IMAGE, BUILD_ROLE, CODE_URI, "an image");
    }

    @Test
    void aRenamedImageIsAReplacingUpdateAndStillCreates() {
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(2), "1.0"));
        StackResource r = resource();

        provisioner.provision(r, props("renamed"), ctx("old-name"));

        assertEquals("renamed", r.getPhysicalId());
        verify(service).createImage(eq("us-east-1"), eq("000000000000"), eq("renamed"), any(), any(), any(), any());
        verify(service, never()).updateImage(anyString(), anyString(), any(), any(), any(), any());
    }
}
