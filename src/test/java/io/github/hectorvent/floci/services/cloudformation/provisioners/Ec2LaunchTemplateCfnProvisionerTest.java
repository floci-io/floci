package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
        when(ec2.createLaunchTemplate(eq("us-east-1"), eq("my-lt"), eq("ami-12345678"),
                eq("t3.small"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
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
        when(ec2.createLaunchTemplate(eq("us-east-1"), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(template("lt-gen"));
        StackResource r = resource("Lt");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        verify(ec2).createLaunchTemplate(eq("us-east-1"),
                org.mockito.ArgumentMatchers.matches("my-stack-Lt-[0-9a-f]{12}"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
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
                eq("ami-updated"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(template("lt-abc123"));

        ObjectNode props = mapper.createObjectNode().put("LaunchTemplateName", "my-lt");
        props.set("LaunchTemplateData", mapper.createObjectNode().put("ImageId", "ami-updated"));
        StackResource r = resource("Lt");
        r.setPhysicalId("lt-abc123");

        provisioner.provision(r, props, ctx());

        assertEquals("lt-abc123", r.getPhysicalId());
        verify(ec2, never()).createLaunchTemplate(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(ec2, never()).deleteLaunchTemplate(any(), any(), any());
    }

    @Test
    void updateOfAnUnnamedTemplateKeepsItsGeneratedName() {
        // Regenerating the name on every update minted a second template and orphaned the first.
        LaunchTemplate existing = template("lt-gen");
        existing.setLaunchTemplateName("my-stack-Lt-0123456789ab");
        when(ec2.describeLaunchTemplates(eq("us-east-1"), eq(List.of("lt-gen")), eq(List.of()), any()))
                .thenReturn(List.of(existing));
        when(ec2.createLaunchTemplateVersion(eq("us-east-1"), eq("lt-gen"), isNull(), isNull(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(template("lt-gen"));

        StackResource r = resource("Lt");
        r.setPhysicalId("lt-gen");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertEquals("lt-gen", r.getPhysicalId());
        verify(ec2, never()).createLaunchTemplate(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void renamingReplacesTheTemplateAndRemovesTheOldOne() {
        LaunchTemplate existing = template("lt-old");
        existing.setLaunchTemplateName("old-name");
        when(ec2.describeLaunchTemplates(eq("us-east-1"), eq(List.of("lt-old")), eq(List.of()), any()))
                .thenReturn(List.of(existing));
        when(ec2.createLaunchTemplate(eq("us-east-1"), eq("new-name"), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(template("lt-new"));

        StackResource r = resource("Lt");
        r.setPhysicalId("lt-old");

        provisioner.provision(r, mapper.createObjectNode().put("LaunchTemplateName", "new-name"), ctx());

        assertEquals("lt-new", r.getPhysicalId());
        // Created before the old one is dropped, so a failed create leaves the original intact.
        InOrder order = inOrder(ec2);
        order.verify(ec2).createLaunchTemplate(eq("us-east-1"), eq("new-name"), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        order.verify(ec2).deleteLaunchTemplate("us-east-1", "lt-old", null);
    }

    @Test
    void profileNameIsNormalizedToInstanceProfileArn() {
        when(ec2.createLaunchTemplate(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(template("lt-prof"));
        ObjectNode data = mapper.createObjectNode();
        data.putObject("IamInstanceProfile").put("Name", "my-profile");
        ObjectNode props = mapper.createObjectNode();
        props.set("LaunchTemplateData", data);

        provisioner.provision(resource("Lt"), props, ctx());

        verify(ec2).createLaunchTemplate(eq("us-east-1"), anyString(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq("arn:aws:iam::000000000000:instance-profile/my-profile"), isNull(), isNull());
    }

    @Test
    void profileArnIsPassedThrough() {
        when(ec2.createLaunchTemplate(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(template("lt-prof"));
        ObjectNode data = mapper.createObjectNode();
        data.putObject("IamInstanceProfile")
                .put("Arn", "arn:aws:iam::000000000000:instance-profile/explicit");
        data.putArray("SecurityGroupIds").add("sg-1").add("sg-2");
        ObjectNode props = mapper.createObjectNode();
        props.set("LaunchTemplateData", data);

        provisioner.provision(resource("Lt"), props, ctx());

        verify(ec2).createLaunchTemplate(eq("us-east-1"), anyString(), isNull(), isNull(), isNull(),
                eq(List.of("sg-1", "sg-2")), isNull(), isNull(),
                eq("arn:aws:iam::000000000000:instance-profile/explicit"), isNull(), isNull());
    }

    @Test
    void deleteDelegatesToServiceById() {
        provisioner.delete("AWS::EC2::LaunchTemplate", "lt-abc123", "us-east-1");
        verify(ec2).deleteLaunchTemplate("us-east-1", "lt-abc123", null);
    }

    @Test
    void handlesOnlyLaunchTemplate() {
        assertEquals(Set.of("AWS::EC2::LaunchTemplate"), provisioner.resourceTypes());
    }
}
