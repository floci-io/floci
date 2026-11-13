package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.HostedZone;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.floci.aws.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a Route53 zone replacement and a record set update behave when a step of them fails. */
class Route53ReplacementFailuresTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CloudFormationTemplateEngine engine() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return engine;
    }

    private static StackResource zone() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");
        resource.setPhysicalId("ZOLD");
        resource.getAttributes().put("Id", "ZOLD");
        resource.getAttributes().put("NameServers", "ns-old");
        return resource;
    }

    private static ProvisionContext zoneUpdate(String oldProps) throws Exception {
        return new ProvisionContext(engine(), "us-east-1", "000000000000", "dns-stack", "ZOLD", event -> { },
                MAPPER.readTree(oldProps));
    }

    private static Route53Service zoneService() {
        Route53Service service = mock(Route53Service.class);
        when(service.getHostedZone("ZOLD")).thenReturn(
                new HostedZone("ZOLD", "old.example.test.", "dns-stack/Zone", null, (VpcAssociation) null));
        return service;
    }

    @Test
    void aReplacementWhoseCreateFailsStillPointsAtThePriorZone() throws Exception {
        Route53Service service = zoneService();
        doThrow(new AwsException("InvalidDomainName", "bad", 400)).when(service)
                .createHostedZone(any(), any(), any(), any());
        StackResource resource = zone();

        assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service).provision(resource,
                MAPPER.readTree("{\"Name\":\"new.example.test\"}"), zoneUpdate("{\"Name\":\"old.example.test\"}")));

        assertEquals("ZOLD", resource.getPhysicalId());
    }

    @Test
    void aReplacementWhoseTagWriteFailsPutsBackThePriorZoneAndItsAttributes() throws Exception {
        Route53Service service = zoneService();
        HostedZone created = new HostedZone("ZNEW", "new.example.test.", "caller", null, (VpcAssociation) null);
        when(service.createHostedZone(any(), any(), any(), any()))
                .thenReturn(new Route53Service.CreateZoneResult(created, null));
        when(service.getAssignedNameServers("ZNEW")).thenReturn(List.of("ns-new"));
        doThrow(new AwsException("Throttling", "slow", 400)).when(service)
                .changeTagsForResource(eq("hostedzone"), eq("ZNEW"), any(), any());
        StackResource resource = zone();

        assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service).provision(resource,
                MAPPER.readTree("{\"Name\":\"new.example.test\",\"HostedZoneTags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}"),
                zoneUpdate("{\"Name\":\"old.example.test\"}")));

        verify(service).deleteHostedZone("ZNEW");
        assertEquals("ZOLD", resource.getPhysicalId());
        assertEquals("ZOLD", resource.getAttributes().get("Id"));
        assertEquals("ns-old", resource.getAttributes().get("NameServers"));
    }

    @Test
    void aBlankDelegationSetIdIsNoDelegationSet() throws Exception {
        Route53Service service = zoneService();
        HostedZone created = new HostedZone("ZNEW", "new.example.test.", "caller", null, (VpcAssociation) null);
        when(service.createHostedZone(any(), any(), any(), any()))
                .thenReturn(new Route53Service.CreateZoneResult(created, null));
        when(service.getAssignedNameServers(any())).thenReturn(List.of("ns"));

        new Route53CfnProvisioner(service).provision(zone(),
                MAPPER.readTree("{\"Name\":\"new.example.test\",\"DelegationSetId\":\"\"}"),
                zoneUpdate("{\"Name\":\"old.example.test\"}"));

        verify(service).createHostedZone(eq("new.example.test"), any(), any(), any());
        verify(service, never()).createHostedZone(any(), any(), any(), any(), any());
    }

    private static StackResource record(String name, String zoneId) {
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId(name);
        resource.getAttributes().put("__FlociRoute53RecordZoneId", zoneId);
        resource.getAttributes().put("__FlociRoute53RecordType", "A");
        return resource;
    }

    private static ResourceRecordSet recordSet(String name) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType("A");
        record.setTtl(300L);
        return record;
    }

    private static ProvisionContext recordUpdate(String priorName) {
        return new ProvisionContext(engine(), "us-east-1", "000000000000", "dns-stack", priorName);
    }

    private static JsonNode renamed() {
        return MAPPER.createObjectNode().put("HostedZoneId", "Z1").put("Name", "login.example.com")
                .put("Type", "A");
    }

    @Test
    void aFailedSupersedeRemovesTheRecordTheAttemptWrote() {
        Route53Service service = mock(Route53Service.class);
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(
                List.of(recordSet("auth.example.com."), recordSet("login.example.com.")));
        doThrow(new AwsException("Throttling", "slow", 400)).when(service).changeResourceRecordSets(eq("Z1"),
                argThat(changes -> changes != null && "DELETE".equals(changes.get(0).get("action"))
                        && ((ResourceRecordSet) changes.get(0).get("rrs")).getName().startsWith("auth")), any());

        assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service)
                .provision(record("auth.example.com", "Z1"), renamed(), recordUpdate("auth.example.com")));

        verify(service).changeResourceRecordSets(eq("Z1"), argThat(changes -> changes != null
                && "DELETE".equals(changes.get(0).get("action"))
                && ((ResourceRecordSet) changes.get(0).get("rrs")).getName().startsWith("login")), any());
    }

    @Test
    void aRollbackDeletesTheRecordTheUpdateWroteAndRestoresTheDisplacedOne() {
        Route53Service service = mock(Route53Service.class);
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(
                List.of(recordSet("auth.example.com."), recordSet("login.example.com.")));
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = record("auth.example.com", "Z1");
        owner.provision(resource, renamed(), recordUpdate("auth.example.com"));

        assertTrue(owner.rollbackUpdate(resource));

        assertEquals("auth.example.com", resource.getPhysicalId());
        verify(service).changeResourceRecordSets(eq("Z1"), argThat(changes -> changes != null
                && "UPSERT".equals(changes.get(0).get("action"))
                && ((ResourceRecordSet) changes.get(0).get("rrs")).getName().startsWith("auth")), any());
    }

    @Test
    void aRefusedDeleteFailsTheRollbackInsteadOfPassing() {
        Route53Service service = mock(Route53Service.class);
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(
                List.of(recordSet("auth.example.com."), recordSet("login.example.com.")));
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = record("auth.example.com", "Z1");
        owner.provision(resource, renamed(), recordUpdate("auth.example.com"));
        doThrow(new AwsException("InvalidChangeBatch", "changed outside", 400)).when(service)
                .changeResourceRecordSets(eq("Z1"), argThat(changes -> changes != null
                        && "DELETE".equals(changes.get(0).get("action"))), any());

        assertThrows(AwsException.class, () -> owner.rollbackUpdate(resource));
    }

    @Test
    void aRollbackThatCannotRestoreTheDisplacedRecordIsNotReportedAsDone() {
        Route53Service service = mock(Route53Service.class);
        // The prior record is not in its zone when the update starts: nothing to restore.
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(recordSet("login.example.com.")));
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = record("auth.example.com", "Z1");
        owner.provision(resource, renamed(), recordUpdate("auth.example.com"));

        assertFalse(owner.rollbackUpdate(resource));

        verify(service, never()).changeResourceRecordSets(eq("Z1"), argThat(changes -> changes != null
                && "DELETE".equals(changes.get(0).get("action"))
                && ((ResourceRecordSet) changes.get(0).get("rrs")).getName().startsWith("login")), any());
    }
}
