package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Namespace;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * The Cloud Map namespace types and {@code AWS::ServiceDiscovery::Service} in isolation: the ids
 * back Ref and Fn::GetAtt, a textual TTL reaches the service as a number, an unchanged createOnly
 * set updates in place, a changed one replaces, a rolled back replacement puts the createOnly record
 * back, a rolled back in-place update puts the prior description, DnsConfig, health check and tags
 * back from its snapshot, a committed update drops that snapshot, and delete tolerates only the
 * not-found codes.
 */
class CloudMapCfnProvisionerTest {

    private static final String PRIVATE_DNS_NAMESPACE = "AWS::ServiceDiscovery::PrivateDnsNamespace";
    private static final String PUBLIC_DNS_NAMESPACE = "AWS::ServiceDiscovery::PublicDnsNamespace";
    private static final String HTTP_NAMESPACE = "AWS::ServiceDiscovery::HttpNamespace";
    private static final String SERVICE = "AWS::ServiceDiscovery::Service";
    private static final String SNAPSHOT_ATTR = "__FlociCloudMapUpdateSnapshot";
    private static final String NS_ARN = "arn:aws:servicediscovery:us-east-1:000000000000:namespace/ns-1";
    private static final String SRV_ARN = "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-1";
    private static final String SRV2_ARN = "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-2";

    private final CloudMapService cloudMap = mock(CloudMapService.class);
    private final CloudMapCfnProvisioner provisioner = new CloudMapCfnProvisioner(cloudMap);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void namespaceCreatePublishesIdArnAndHostedZoneId() throws Exception {
        stubNamespaceCreate();
        StackResource r = resource(PRIVATE_DNS_NAMESPACE, "Ns");

        provisioner.provision(r, props("""
                {"Name": "svc.internal", "Vpc": "vpc-1", "Description": "d1",
                 "Tags": [{"Key": "k1", "Value": "v1"}]}"""), ctx(null));

        verify(cloudMap).createPrivateDnsNamespace("svc.internal", "vpc-1", null, "d1",
                Map.of("k1", "v1"), "us-east-1");
        assertEquals("ns-1", r.getPhysicalId());
        assertEquals("ns-1", r.getAttributes().get("Id"));
        assertEquals(NS_ARN, r.getAttributes().get("Arn"));
        assertEquals("Z0123456789ABC", r.getAttributes().get("HostedZoneId"));
    }

    @Test
    void httpNamespaceCreatePublishesIdAndArn() throws Exception {
        Operation op = stubNamespace(null);
        when(cloudMap.createHttpNamespace(any(), any(), any(), any(), any())).thenReturn(op);
        StackResource r = resource(HTTP_NAMESPACE, "Http");

        provisioner.provision(r, props("""
                {"Name": "svc-http", "Description": "d1"}"""), ctx(null));

        verify(cloudMap).createHttpNamespace("svc-http", null, "d1", Map.of(), "us-east-1");
        assertEquals("ns-1", r.getPhysicalId());
        assertEquals("ns-1", r.getAttributes().get("Id"));
        assertEquals(NS_ARN, r.getAttributes().get("Arn"));
        assertFalse(r.getAttributes().containsKey("HostedZoneId"));
    }

    @Test
    void publicDnsNamespaceCreatePublishesIdArnAndHostedZoneId() throws Exception {
        Operation op = stubNamespace("Z0123456789ABC");
        when(cloudMap.createPublicDnsNamespace(any(), any(), any(), any(), any())).thenReturn(op);
        StackResource r = resource(PUBLIC_DNS_NAMESPACE, "Pub");

        provisioner.provision(r, props("""
                {"Name": "example.com"}"""), ctx(null));

        verify(cloudMap).createPublicDnsNamespace("example.com", null, null, Map.of(), "us-east-1");
        assertEquals("ns-1", r.getPhysicalId());
        assertEquals("ns-1", r.getAttributes().get("Id"));
        assertEquals(NS_ARN, r.getAttributes().get("Arn"));
        assertEquals("Z0123456789ABC", r.getAttributes().get("HostedZoneId"));
    }

    @Test
    void serviceCreatePassesATextualTtlAsANumber() throws Exception {
        stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "Svc");

        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1", "Description": "svc",
                 "DnsConfig": {"NamespaceId": "ns-1", "RoutingPolicy": "MULTIVALUE",
                               "DnsRecords": [{"Type": "A", "TTL": "60"}]},
                 "HealthCheckCustomConfig": {"FailureThreshold": "1"}}"""), ctx(null));

        ArgumentCaptor<String> dnsConfig = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> customConfig = ArgumentCaptor.forClass(String.class);
        verify(cloudMap).createService(any(), any(), any(), any(), dnsConfig.capture(), any(),
                customConfig.capture(), any(), any(), any());
        assertEquals("{\"NamespaceId\":\"ns-1\",\"RoutingPolicy\":\"MULTIVALUE\","
                + "\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}", dnsConfig.getValue());
        assertEquals("{\"FailureThreshold\":1}", customConfig.getValue());
        assertEquals("srv-1", r.getPhysicalId());
        assertEquals("srv-1", r.getAttributes().get("Id"));
        assertEquals(SRV_ARN, r.getAttributes().get("Arn"));
        assertEquals("main", r.getAttributes().get("Name"));
    }

    @Test
    void serviceWithoutNameGetsALogicalIdBasedName() throws Exception {
        stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "SvcNoName");

        provisioner.provision(r, props("""
                {"DnsConfig": {"NamespaceId": "ns-1", "DnsRecords": [{"Type": "A", "TTL": 30}]}}"""), ctx(null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> namespaceId = ArgumentCaptor.forClass(String.class);
        verify(cloudMap).createService(name.capture(), namespaceId.capture(), any(), any(), any(), any(),
                any(), any(), any(), any());
        assertThat(name.getValue(), matchesPattern("SvcNoName-[0-9a-f]{12}"));
        assertEquals("ns-1", namespaceId.getValue());
        assertEquals(name.getValue(), r.getAttributes().get("Name"));
    }

    @Test
    void namespaceUpdateWithUnchangedNameAndVpcUpdatesInPlace() throws Exception {
        stubNamespaceCreate();
        StackResource r = resource(PRIVATE_DNS_NAMESPACE, "Ns");
        provisioner.provision(r, props("""
                {"Name": "svc.internal", "Vpc": "vpc-1", "Description": "d1",
                 "Tags": [{"Key": "k1", "Value": "v1"}]}"""), ctx(null));
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("k1", "v1");
        stored.put("stale", "x");
        when(cloudMap.listTagsForResource(NS_ARN)).thenReturn(stored);

        provisioner.provision(r, props("""
                {"Name": "svc.internal", "Vpc": "vpc-1", "Description": "d2",
                 "Tags": [{"Key": "k1", "Value": "v2"}]}"""), ctx("ns-1"));

        verify(cloudMap, times(1)).createPrivateDnsNamespace(any(), any(), any(), any(), any(), any());
        verify(cloudMap).updateNamespace("ns-1", "d2");
        verify(cloudMap).untagResource(NS_ARN, List.of("stale"));
        verify(cloudMap).tagResource(NS_ARN, Map.of("k1", "v2"));
        assertEquals("ns-1", r.getPhysicalId());
        assertEquals("ns-1", r.getAttributes().get("Id"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void serviceUpdateWithUnchangedCreateOnlyPropertiesUpdatesInPlace() throws Exception {
        stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "Svc");
        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1", "Description": "d1",
                 "DnsConfig": {"DnsRecords": [{"Type": "A", "TTL": 60}]}}"""), ctx(null));

        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1", "Description": "d2",
                 "DnsConfig": {"DnsRecords": [{"Type": "A", "TTL": "120"}]},
                 "HealthCheckConfig": {"Type": "HTTP", "FailureThreshold": "1"}}"""),
                ctx("srv-1"));

        verify(cloudMap, times(1)).createService(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(cloudMap).updateService("srv-1", "d2", "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":120}]}",
                "{\"Type\":\"HTTP\",\"FailureThreshold\":1}");
        verify(cloudMap).tagResource(SRV_ARN, Map.of());
        assertEquals("srv-1", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void serviceUpdateWithAChangedNameReplacesTheService() throws Exception {
        stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "Svc");
        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1"}"""), ctx(null));
        stubServiceCreate("srv-2", SRV2_ARN);

        provisioner.provision(r, props("""
                {"Name": "main2", "NamespaceId": "ns-1"}"""), ctx("srv-1"));

        verify(cloudMap, times(2)).createService(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(cloudMap, never()).updateService(anyString(), any(), any(), any());
        assertEquals("srv-2", r.getPhysicalId());
        assertEquals("main2", r.getAttributes().get("Name"));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("srv-1", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void rolledBackReplacementRestoresTheCreateOnlyRecordSoARetryReplacesAgain() throws Exception {
        stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "Svc");
        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1"}"""), ctx(null));
        String createdRecord = r.getAttributes().get("__FlociCreateOnly");
        stubServiceCreate("srv-2", SRV2_ARN);
        provisioner.provision(r, props("""
                {"Name": "main2", "NamespaceId": "ns-1"}"""), ctx("srv-1"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cloudMap).deleteService("srv-2");
        assertEquals("srv-1", r.getPhysicalId());
        assertEquals(createdRecord, r.getAttributes().get("__FlociCreateOnly"));
        assertFalse(r.getAttributes().containsKey("__FlociCreateOnlyPrior"));

        provisioner.provision(r, props("""
                {"Name": "main2", "NamespaceId": "ns-1"}"""), ctx("srv-1"));

        verify(cloudMap, times(2)).createService(eq("main2"), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(cloudMap, never()).updateService(anyString(), any(), any(), any());
        assertEquals("srv-2", r.getPhysicalId());
    }

    @Test
    void serviceInPlaceUpdateRollsBackToTheSnapshot() throws Exception {
        Service stored = stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "Svc");
        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1"}"""), ctx(null));
        stored.setDescription("d1");
        stored.setDnsConfig("{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}");
        stored.setHealthCheckConfig("{\"Type\":\"HTTP\",\"ResourcePath\":\"/\",\"FailureThreshold\":1}");
        stored.setTags(new HashMap<>(Map.of("k1", "v1")));
        when(cloudMap.listTagsForResource(SRV_ARN)).thenReturn(Map.of("k1", "v2"));

        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1", "Description": "d2",
                 "DnsConfig": {"DnsRecords": [{"Type": "A", "TTL": "120"}]},
                 "HealthCheckConfig": {"Type": "HTTP", "ResourcePath": "/health", "FailureThreshold": 2},
                 "Tags": [{"Key": "k1", "Value": "v2"}]}"""), ctx("srv-1"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cloudMap).updateService("srv-1", "d1", "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":60}]}",
                "{\"Type\":\"HTTP\",\"ResourcePath\":\"/\",\"FailureThreshold\":1}");
        verify(cloudMap).tagResource(SRV_ARN, Map.of("k1", "v1"));
        assertFalse(r.getAttributes().containsKey(SNAPSHOT_ATTR));
        assertEquals("srv-1", r.getPhysicalId());
    }

    @Test
    void namespaceInPlaceUpdateRollsBackToTheSnapshot() throws Exception {
        stubNamespaceCreate();
        Namespace stored = new Namespace();
        stored.setId("ns-1");
        stored.setArn(NS_ARN);
        stored.setDescription("d1");
        stored.setTags(new HashMap<>(Map.of("k1", "v1")));
        when(cloudMap.getNamespace("ns-1")).thenReturn(stored);
        StackResource r = resource(PRIVATE_DNS_NAMESPACE, "Ns");
        provisioner.provision(r, props("""
                {"Name": "svc.internal", "Vpc": "vpc-1", "Description": "d1",
                 "Tags": [{"Key": "k1", "Value": "v1"}]}"""), ctx(null));

        provisioner.provision(r, props("""
                {"Name": "svc.internal", "Vpc": "vpc-1", "Description": "d2",
                 "Tags": [{"Key": "k1", "Value": "v2"}]}"""), ctx("ns-1"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cloudMap).updateNamespace("ns-1", "d2");
        verify(cloudMap).updateNamespace("ns-1", "d1");
        verify(cloudMap).tagResource(NS_ARN, Map.of("k1", "v1"));
        assertFalse(r.getAttributes().containsKey(SNAPSHOT_ATTR));
        assertEquals("ns-1", r.getPhysicalId());
    }

    @Test
    void committedUpdateDropsTheSnapshot() throws Exception {
        stubServiceCreate("srv-1", SRV_ARN);
        StackResource r = resource(SERVICE, "Svc");
        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1"}"""), ctx(null));
        provisioner.provision(r, props("""
                {"Name": "main", "NamespaceId": "ns-1", "Description": "d2"}"""), ctx("srv-1"));
        assertTrue(r.getAttributes().containsKey(SNAPSHOT_ATTR));

        provisioner.completeUpdate(r);

        assertFalse(r.getAttributes().containsKey(SNAPSHOT_ATTR));
    }

    @Test
    void deleteNamespaceToleratesNamespaceNotFound() {
        doThrow(new AwsException("NamespaceNotFound", "gone", 404))
                .when(cloudMap).deleteNamespace("ns-1", "us-east-1");

        assertDoesNotThrow(() -> provisioner.delete(PRIVATE_DNS_NAMESPACE, "ns-1", "us-east-1"));
        verify(cloudMap).deleteNamespace("ns-1", "us-east-1");
    }

    @Test
    void deleteServiceToleratesServiceNotFound() {
        doThrow(new AwsException("ServiceNotFound", "gone", 404)).when(cloudMap).deleteService("srv-1");

        assertDoesNotThrow(() -> provisioner.delete(SERVICE, "srv-1", "us-east-1"));
        verify(cloudMap).deleteService("srv-1");
    }

    @Test
    void deleteServiceWithInstancesPropagatesResourceInUse() {
        doThrow(new AwsException("ResourceInUse", "has instances", 400)).when(cloudMap).deleteService("srv-1");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(SERVICE, "srv-1", "us-east-1"));
        assertEquals("ResourceInUse", failure.getErrorCode());
    }

    private void stubNamespaceCreate() {
        Operation op = stubNamespace("Z0123456789ABC");
        when(cloudMap.createPrivateDnsNamespace(any(), any(), any(), any(), any(), any())).thenReturn(op);
    }

    /** Stubs the lookup of namespace ns-1 and returns the create operation that targets it. */
    private Operation stubNamespace(String hostedZoneId) {
        Operation op = new Operation();
        op.getTargets().put("NAMESPACE", "ns-1");
        Namespace ns = new Namespace();
        ns.setId("ns-1");
        ns.setArn(NS_ARN);
        ns.setHostedZoneId(hostedZoneId);
        when(cloudMap.getNamespace("ns-1")).thenReturn(ns);
        return op;
    }

    /** Stubs the create and the lookup of one service, returning the instance both hand out. */
    private Service stubServiceCreate(String id, String arn) {
        Service service = new Service();
        service.setId(id);
        service.setArn(arn);
        when(cloudMap.createService(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    service.setName(inv.getArgument(0));
                    return service;
                });
        when(cloudMap.getService(id)).thenReturn(service);
        return service;
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        // The engine rebuilds plain objects, and a Ref to a Number parameter resolves to text such as
        // "60", which the templates above write literally.
        when(engine.resolveNode(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.deepCopy();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
