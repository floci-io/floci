package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The launch-template CFN provisioner in isolation, mocking only Ec2Service.
 */
class Ec2LaunchTemplateCfnProvisionerTest {

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2LaunchTemplateCfnProvisioner provisioner = new Ec2LaunchTemplateCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalar/tree resolution is identity here; intrinsics are the engine's own concern.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType("AWS::EC2::LaunchTemplate");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private LaunchTemplate template(String id) {
        LaunchTemplate lt = new LaunchTemplate();
        lt.setLaunchTemplateId(id);
        return lt;
    }

    @Test
    void setsPhysicalIdAndGetAttAttributes() {
        when(ec2.createLaunchTemplate(eq("us-east-1"), eq("my-lt"), any(), any()))
                .thenReturn(template("lt-abc123"));
        ObjectNode data = mapper.createObjectNode()
                .put("ImageId", "ami-12345678")
                .put("InstanceType", "t3.small");
        ObjectNode props = mapper.createObjectNode().put("LaunchTemplateName", "my-lt");
        props.set("LaunchTemplateData", data);
        StackResource r = resource("Lt");

        provisioner.provision(r, props, ctx());

        assertEquals("lt-abc123", r.getPhysicalId());
        assertEquals("lt-abc123", r.getAttributes().get("LaunchTemplateId"));
        assertEquals("1", r.getAttributes().get("LatestVersionNumber"));
        assertEquals("1", r.getAttributes().get("DefaultVersionNumber"));
    }

    @Test
    void withoutNameGeneratesPhysicalName() {
        when(ec2.createLaunchTemplate(eq("us-east-1"), anyString(), any(), any()))
                .thenReturn(template("lt-gen"));
        StackResource r = resource("Lt");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        verify(ec2).createLaunchTemplate(eq("us-east-1"),
                org.mockito.ArgumentMatchers.matches("my-stack-Lt-[0-9a-f]{12}"), any(), isNull());
    }

    @Test
    void updateWithTheSameNamePublishesAVersionInsteadOfANewTemplate() {
        // Creating unconditionally hit InvalidLaunchTemplateName.AlreadyExistsException for an
        // explicitly named template.
        LaunchTemplate existing = template("lt-abc123");
        existing.setLaunchTemplateName("my-lt");
        when(ec2.describeLaunchTemplates(eq("us-east-1"), eq(List.of("lt-abc123")), eq(List.of()), any()))
                .thenReturn(List.of(existing));
        when(ec2.createLaunchTemplateVersion(eq("us-east-1"), eq("lt-abc123"), isNull(), isNull(),
                argThat(d -> d != null && "ami-updated".equals(d.getImageId()))))
                .thenReturn(template("lt-abc123"));

        ObjectNode props = mapper.createObjectNode().put("LaunchTemplateName", "my-lt");
        props.set("LaunchTemplateData", mapper.createObjectNode().put("ImageId", "ami-updated"));
        StackResource r = resource("Lt");
        r.setPhysicalId("lt-abc123");

        provisioner.provision(r, props, ctx());

        assertEquals("lt-abc123", r.getPhysicalId());
        verify(ec2, never()).createLaunchTemplate(any(), any(), any(), any());
        verify(ec2, never()).deleteLaunchTemplate(any(), any(), any());
    }

    @Test
    void updateOfAnUnnamedTemplateKeepsItsGeneratedName() {
        // Regenerating the name on every update minted a second template and orphaned the first.
        LaunchTemplate existing = template("lt-gen");
        existing.setLaunchTemplateName("my-stack-Lt-0123456789ab");
        when(ec2.describeLaunchTemplates(eq("us-east-1"), eq(List.of("lt-gen")), eq(List.of()), any()))
                .thenReturn(List.of(existing));
        when(ec2.createLaunchTemplateVersion(eq("us-east-1"), eq("lt-gen"), isNull(), isNull(), any()))
                .thenReturn(template("lt-gen"));

        StackResource r = resource("Lt");
        r.setPhysicalId("lt-gen");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertEquals("lt-gen", r.getPhysicalId());
        verify(ec2, never()).createLaunchTemplate(any(), any(), any(), any());
    }

    @Test
    void renamingReplacesTheTemplateAndRemovesTheOldOne() {
        LaunchTemplate existing = template("lt-old");
        existing.setLaunchTemplateName("old-name");
        when(ec2.describeLaunchTemplates(eq("us-east-1"), eq(List.of("lt-old")), eq(List.of()), any()))
                .thenReturn(List.of(existing));
        when(ec2.createLaunchTemplate(eq("us-east-1"), eq("new-name"), any(), any()))
                .thenReturn(template("lt-new"));

        StackResource r = resource("Lt");
        r.setPhysicalId("lt-old");

        provisioner.provision(r, mapper.createObjectNode().put("LaunchTemplateName", "new-name"), ctx());

        assertEquals("lt-new", r.getPhysicalId());
        // Created before the old one is dropped, so a failed create leaves the original intact.
        InOrder order = inOrder(ec2);
        order.verify(ec2).createLaunchTemplate(eq("us-east-1"), eq("new-name"), any(), any());
        order.verify(ec2).deleteLaunchTemplate("us-east-1", "lt-old", null);
    }

    @Test
    void profileGivenOnlyByNameKeepsThatForm() {
        // Normalizing Name into an Arn here is what made
        // aws_launch_template.iam_instance_profile.name never converge: Terraform sets .name and
        // read back .arn. EC2 derives the ARN at launch time instead.
        when(ec2.createLaunchTemplate(any(), any(), any(), any())).thenReturn(template("lt-prof"));
        ObjectNode data = mapper.createObjectNode();
        data.putObject("IamInstanceProfile").put("Name", "my-profile");
        ObjectNode props = mapper.createObjectNode();
        props.set("LaunchTemplateData", data);

        provisioner.provision(resource("Lt"), props, ctx());

        ArgumentCaptor<LaunchTemplateData> captor = ArgumentCaptor.forClass(LaunchTemplateData.class);
        verify(ec2).createLaunchTemplate(eq("us-east-1"), anyString(), captor.capture(), isNull());
        assertEquals("my-profile", captor.getValue().getIamInstanceProfile().getName());
        assertNull(captor.getValue().getIamInstanceProfile().getArn());
    }

    @Test
    void profileArnIsPassedThrough() {
        when(ec2.createLaunchTemplate(any(), any(), any(), any())).thenReturn(template("lt-prof"));
        ObjectNode data = mapper.createObjectNode();
        data.putObject("IamInstanceProfile")
                .put("Arn", "arn:aws:iam::000000000000:instance-profile/explicit");
        data.putArray("SecurityGroupIds").add("sg-1").add("sg-2");
        ObjectNode props = mapper.createObjectNode();
        props.set("LaunchTemplateData", data);

        provisioner.provision(resource("Lt"), props, ctx());

        ArgumentCaptor<LaunchTemplateData> captor = ArgumentCaptor.forClass(LaunchTemplateData.class);
        verify(ec2).createLaunchTemplate(eq("us-east-1"), anyString(), captor.capture(), isNull());
        assertEquals("arn:aws:iam::000000000000:instance-profile/explicit",
                captor.getValue().getIamInstanceProfile().getArn());
        assertNull(captor.getValue().getIamInstanceProfile().getName());
        assertEquals(List.of("sg-1", "sg-2"), captor.getValue().getSecurityGroupIds());
    }

    @Test
    void rollbackUpdateDeletesCreatedVersionAndRestoresAttributes() {
        LaunchTemplate existing = template("lt-abc123");
        existing.setLaunchTemplateName("my-lt");
        existing.setLatestVersionNumber("1");
        existing.setDefaultVersionNumber("1");
        when(ec2.describeLaunchTemplates(eq("us-east-1"), eq(List.of("lt-abc123")), eq(List.of()), any()))
                .thenReturn(List.of(existing));

        LaunchTemplate updated = template("lt-abc123");
        updated.setLaunchTemplateName("my-lt");
        updated.setLatestVersionNumber("2");
        updated.setDefaultVersionNumber("1");
        when(ec2.createLaunchTemplateVersion(eq("us-east-1"), eq("lt-abc123"), isNull(), isNull(), any()))
                .thenReturn(updated);

        ObjectNode props = mapper.createObjectNode().put("LaunchTemplateName", "my-lt");
        props.set("LaunchTemplateData", mapper.createObjectNode().put("ImageId", "ami-v2"));
        StackResource r = resource("Lt");
        r.setPhysicalId("lt-abc123");

        provisioner.provision(r, props, ctx());
        assertEquals("2", r.getAttributes().get("LatestVersionNumber"));

        boolean rolledBack = provisioner.rollbackUpdate(r);
        assertTrue(rolledBack);
        verify(ec2).deleteLaunchTemplateVersion("us-east-1", "lt-abc123", "2");
        assertEquals("1", r.getAttributes().get("LatestVersionNumber"));
        assertEquals("1", r.getAttributes().get("DefaultVersionNumber"));

        // Second rollback without snapshot should return false
        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void provisionedUserDataIsDecodedForInstanceLaunch() {
        when(ec2.createLaunchTemplate(any(), any(), any(), any())).thenReturn(template("lt-userdata"));
        String script = "#!/bin/bash\necho from-cfn\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        ObjectNode data = mapper.createObjectNode().put("UserData", encoded);
        ObjectNode props = mapper.createObjectNode();
        props.set("LaunchTemplateData", data);

        provisioner.provision(resource("Lt"), props, ctx());

        ArgumentCaptor<LaunchTemplateData> captor = ArgumentCaptor.forClass(LaunchTemplateData.class);
        verify(ec2).createLaunchTemplate(eq("us-east-1"), anyString(), captor.capture(), isNull());
        assertEquals(encoded, captor.getValue().getEncodedUserData());
        assertEquals(script, captor.getValue().getUserData());
    }

    @Test
    void oversizedGzippedUserDataIsRejectedBeforeTemplateCreation() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("A".repeat(UserDataPipeline.MAX_DECOMPRESSED_USER_DATA_BYTES + 1)
                    .getBytes(StandardCharsets.UTF_8));
        }
        ObjectNode props = mapper.createObjectNode();
        props.set("LaunchTemplateData", mapper.createObjectNode()
                .put("UserData", Base64.getEncoder().encodeToString(compressed.toByteArray())));

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("Lt"), props, ctx()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        verify(ec2, never()).createLaunchTemplate(any(), any(), any(), any());
    }
}
