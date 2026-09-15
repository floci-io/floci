package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.wafv2.WafV2Service;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WafV2CfnProvisionerTest {

    private final WafV2Service wafV2 = mock(WafV2Service.class);
    private final WafV2CfnProvisioner provisioner = new WafV2CfnProvisioner(wafV2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void webAclCreatesBackingResourceAndSetsAwsReferences() {
        ObjectNode props = requiredProps("portal").put("Description", "edge policy");
        props.set("Rules", mapper.createArrayNode().add(mapper.createObjectNode().put("Name", "managed")));
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Platform")));
        WebAcl created = acl("portal", "acl-123", "REGIONAL", "lock-1");
        created.setArn("arn:aws:wafv2:us-east-1:111122223333:regional/webacl/portal/acl-123");
        created.setCapacity(2);
        created.setLabelNamespace("awswaf:111122223333:webacl:portal:");
        when(wafV2.createWebAcl(any(), eq("REGIONAL"), eq("portal"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = resource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<WebAcl> desired = ArgumentCaptor.forClass(WebAcl.class);
        verify(wafV2).createWebAcl(desired.capture(), eq("REGIONAL"), eq("portal"), eq("us-east-1"));
        assertEquals("{\"Allow\":{}}", desired.getValue().getDefaultAction());
        assertEquals("Platform", desired.getValue().getTags().get("Owner"));
        assertEquals("portal|acl-123|REGIONAL", resource.getPhysicalId());
        assertEquals(created.getArn(), resource.getAttributes().get("Arn"));
        assertEquals("acl-123", resource.getAttributes().get("Id"));
        assertEquals("2", resource.getAttributes().get("Capacity"));
        assertEquals(created.getLabelNamespace(), resource.getAttributes().get("LabelNamespace"));
    }

    @Test
    void sameNamedWebAclUpdatesInPlace() {
        WebAcl existing = acl("portal", "acl-123", "REGIONAL", "lock-1");
        WebAcl updated = acl("portal", "acl-123", "REGIONAL", "lock-2");
        updated.setArn("arn:updated");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getWebAcl("REGIONAL", "acl-123", "portal")).thenReturn(updated);
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");

        provisioner.provision(resource, requiredProps("portal"), context());

        verify(wafV2).updateWebAcl(any(), eq("REGIONAL"), eq("acl-123"), eq("portal"), eq("lock-1"));
        assertEquals("portal|acl-123|REGIONAL", resource.getPhysicalId());
    }

    @Test
    void inPlaceUpdateReconcilesTagsReadFromListTagsForResource() {
        WebAcl existing = acl("portal", "acl-123", "REGIONAL", "lock-1");
        WebAcl fetched = acl("portal", "acl-123", "REGIONAL", "lock-2");
        fetched.setArn("arn:portal");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getWebAcl("REGIONAL", "acl-123", "portal")).thenReturn(fetched);
        when(wafV2.listTagsForResource("arn:portal"))
                .thenReturn(Map.of("Owner", "Security", "Stale", "yes"));
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");
        ObjectNode props = requiredProps("portal");
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Platform")));

        provisioner.provision(resource, props, context());

        verify(wafV2).untagResource("arn:portal", List.of("Stale"));
        verify(wafV2).tagResource("arn:portal", Map.of("Owner", "Platform"));
    }

    @Test
    void replacementTracksNewAclEvenWhenOldAclDeleteFails() {
        WebAcl existing = acl("portal", "acl-old", "REGIONAL", "lock-1");
        WebAcl created = acl("portal-renamed", "acl-new", "REGIONAL", "lock-2");
        created.setArn("arn:new");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.createWebAcl(any(), eq("REGIONAL"), eq("portal-renamed"), eq("us-east-1")))
                .thenReturn(created);
        org.mockito.Mockito.doThrow(new RuntimeException("WAFAssociatedItemException"))
                .when(wafV2).deleteWebAcl("REGIONAL", "acl-old", "portal", "lock-1");
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-old|REGIONAL");

        provisioner.provision(resource, requiredProps("portal-renamed"), context());

        assertEquals("portal-renamed|acl-new|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:new", resource.getAttributes().get("Arn"));
    }

    @Test
    void intrinsicResolvedRuleFieldIsNotDoubleEncoded() {
        ObjectNode props = requiredProps("portal");
        props.set("DefaultAction", mapper.createObjectNode());
        ObjectNode resolved = requiredProps("portal");
        resolved.set("DefaultAction",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("{\"Allow\":{}}"));
        WebAcl created = acl("portal", "acl-1", "REGIONAL", "lock-1");
        when(wafV2.createWebAcl(any(), eq("REGIONAL"), eq("portal"), eq("us-east-1"))).thenReturn(created);
        StackResource resource = resource();
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(props)).thenReturn(resolved);
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "111122223333", "stack");

        provisioner.provision(resource, props, ctx);

        ArgumentCaptor<WebAcl> desired = ArgumentCaptor.forClass(WebAcl.class);
        verify(wafV2).createWebAcl(desired.capture(), eq("REGIONAL"), eq("portal"), eq("us-east-1"));
        assertEquals("{\"Allow\":{}}", desired.getValue().getDefaultAction());
    }

    @Test
    void webAclWithoutScopeFailsBeforeCallingWafV2() {
        assertMissingRequiredPropertyFails("Scope");
    }

    @Test
    void webAclWithoutDefaultActionFailsBeforeCallingWafV2() {
        assertMissingRequiredPropertyFails("DefaultAction");
    }

    @Test
    void webAclWithoutVisibilityConfigFailsBeforeCallingWafV2() {
        assertMissingRequiredPropertyFails("VisibilityConfig");
    }

    @Test
    void updateWithoutScopeFailsInsteadOfReplacingTheWebAcl() {
        WebAcl existing = acl("portal", "acl-123", "REGIONAL", "lock-1");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");
        ObjectNode props = requiredProps("portal");
        props.remove("Scope");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context()));

        assertEquals("AWS::WAFv2::WebACL requires Scope", failure.getMessage());
        verify(wafV2, never()).createWebAcl(any(), any(), anyString(), anyString());
        verify(wafV2, never()).deleteWebAcl(any(), anyString(), anyString(), anyString());
        assertEquals("portal|acl-123|REGIONAL", resource.getPhysicalId());
    }

    private void assertMissingRequiredPropertyFails(String property) {
        ObjectNode props = requiredProps("portal");
        props.remove(property);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context()));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("AWS::WAFv2::WebACL requires " + property, failure.getMessage());
        verifyNoInteractions(wafV2);
    }

    private ObjectNode requiredProps(String name) {
        ObjectNode props = mapper.createObjectNode().put("Name", name).put("Scope", "REGIONAL");
        props.set("DefaultAction", mapper.createObjectNode().set("Allow", mapper.createObjectNode()));
        props.set("VisibilityConfig", mapper.createObjectNode().put("MetricName", name));
        return props;
    }

    private ProvisionContext context() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any(JsonNode.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "111122223333", "stack");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("PortalWebAcl");
        resource.setResourceType("AWS::WAFv2::WebACL");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private WebAcl acl(String name, String id, String scope, String lockToken) {
        WebAcl acl = new WebAcl();
        acl.setName(name);
        acl.setId(id);
        acl.setScope(scope);
        acl.setLockToken(lockToken);
        return acl;
    }
}
