package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.transfer.TransferService;
import io.github.hectorvent.floci.services.transfer.model.Server;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferCfnProvisionerTest {

    private final TransferService transfer = mock(TransferService.class);
    private final TransferCfnProvisioner provisioner = new TransferCfnProvisioner(transfer);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createUsesTransferDefaultsAndRecordsExactAttributes() throws Exception {
        Server server = server();
        when(transfer.createServer(eq("us-east-1"), eq("S3"), eq(List.of("SFTP")), eq("PUBLIC"),
                eq(null), eq("SERVICE_MANAGED"), eq(null), eq(null),
                eq("TransferSecurityPolicy-2020-06"), eq(Map.of()))).thenReturn(server);
        StackResource resource = resource();

        provisioner.provision(resource, mapper.readTree("{}"), context(null));

        assertEquals(server.getArn(), resource.getPhysicalId());
        assertEquals(server.getServerId(), resource.getAttributes().get("ServerId"));
        assertEquals(server.getArn(), resource.getAttributes().get("Arn"));
        assertEquals("ONLINE", resource.getAttributes().get("State"));
    }

    @Test
    void updateUsesArnAndClearsRemovedOptionalProperties() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        when(transfer.getServer(server.getServerId())).thenReturn(server);
        when(transfer.replaceServerConfiguration(eq(server.getServerId()), eq(List.of("SFTP")),
                eq("PUBLIC"), eq(null), eq(null), eq(null),
                eq("TransferSecurityPolicy-2020-06"))).thenReturn(server);
        when(transfer.listTagsForResource(server.getArn())).thenReturn(Map.of("stale", "value"));
        StackResource resource = resource();
        resource.getAttributes().put("__FlociTransferServerTemplateTagKeys", "[\"stale\"]");

        provisioner.provision(resource, mapper.readTree("{}"), context(server.getArn()));

        assertEquals(server.getArn(), resource.getPhysicalId());
        verify(transfer).untagResource(server.getArn(), List.of("stale"));
    }

    @Test
    void updateLegacyServerIdMigratesRefWithoutCreatingAnotherServer() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        server.setState("OFFLINE");
        when(transfer.getServer(server.getServerId())).thenReturn(server);
        when(transfer.replaceServerConfiguration(eq(server.getServerId()), any(), any(), any(), any(), any(), any()))
                .thenReturn(server);
        when(transfer.listTagsForResource(server.getArn())).thenReturn(Map.of());
        StackResource resource = resource();
        resource.setPhysicalId(server.getServerId());

        provisioner.provision(resource, mapper.readTree("{}"), context(server.getServerId()));

        assertEquals(server.getArn(), resource.getPhysicalId());
        assertEquals(server.getServerId(), resource.getAttributes().get("ServerId"));
        assertEquals("OFFLINE", resource.getAttributes().get("State"));
        verify(transfer, never()).createServer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void committedUpdateClearsRollbackSnapshot() {
        StackResource resource = resource();
        resource.getAttributes().put("__FlociTransferServerUpdateSnapshot", "previous-state");
        resource.setStatus("UPDATE_COMPLETE");

        UpdateCleanupResult result = provisioner.completeUpdate(resource);

        assertFalse(result.applicable());
        assertNull(resource.getAttributes().get("__FlociTransferServerUpdateSnapshot"));
    }

    @Test
    void updatePreservesTagsAddedOutsideTheTemplate() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        when(transfer.getServer(server.getServerId())).thenReturn(server);
        when(transfer.replaceServerConfiguration(eq(server.getServerId()), any(), any(), any(), any(), any(), any()))
                .thenReturn(server);
        when(transfer.listTagsForResource(server.getArn()))
                .thenReturn(Map.of("managed", "old", "external", "keep"));
        StackResource resource = resource();
        resource.getAttributes().put("__FlociTransferServerTemplateTagKeys", "[\"managed\"]");

        provisioner.provision(resource, mapper.readTree("{}"), context(server.getServerId()));

        verify(transfer).untagResource(server.getArn(), List.of("managed"));
        verify(transfer, never()).untagResource(server.getArn(), List.of("external"));
        assertEquals("[]", resource.getAttributes().get("__FlociTransferServerTemplateTagKeys"));
    }

    @Test
    void structuredEndpointDetailsRetainBooleanAndListTypes() throws Exception {
        Server server = server();
        when(transfer.createServer(eq("us-east-1"), eq("S3"), eq(List.of("SFTP")), eq("VPC"),
                any(), eq("SERVICE_MANAGED"), eq(null), eq(null),
                eq("TransferSecurityPolicy-2020-06"), eq(Map.of()))).thenReturn(server);
        JsonNode props = mapper.readTree("""
                {"EndpointType":"VPC","EndpointDetails":{"SubnetIds":["subnet-1"],"AddressAllocationIds":[],"DualStack":true}}
                """);

        provisioner.provision(resource(), props, context(null));

        verify(transfer).createServer(eq("us-east-1"), eq("S3"), eq(List.of("SFTP")), eq("VPC"),
                eq(Map.of("SubnetIds", List.of("subnet-1"), "AddressAllocationIds", List.of(), "DualStack", true)),
                eq("SERVICE_MANAGED"), eq(null), eq(null), eq("TransferSecurityPolicy-2020-06"), eq(Map.of()));
    }

    @Test
    void immutablePropertyChangeFailsBeforeMutation() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        when(transfer.getServer(server.getServerId())).thenReturn(server);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                mapper.readTree("{\"Domain\":\"EFS\"}"), context(server.getServerId())));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(transfer, never()).replaceServerConfiguration(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void identityProviderTypeChangeIsRejectedAsFlociLimitation() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        when(transfer.getServer(server.getServerId())).thenReturn(server);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                mapper.readTree("{\"IdentityProviderType\":\"AWS_LAMBDA\"}"), context(server.getArn())));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("Updating IdentityProviderType is not supported by Floci.", failure.getMessage());
        verify(transfer, never()).replaceServerConfiguration(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedTagUpdateRestoresPriorServerConfiguration() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        server.setProtocols(List.of("SFTP"));
        server.setEndpointType("PUBLIC");
        server.setLoggingRole("original-role");
        server.setSecurityPolicyName("TransferSecurityPolicy-2020-06");
        when(transfer.getServer(server.getServerId())).thenReturn(server);
        when(transfer.replaceServerConfiguration(eq(server.getServerId()), any(), any(), any(), any(), any(), any()))
                .thenReturn(server);
        when(transfer.listTagsForResource(server.getArn()))
                .thenReturn(Map.of("old", "value"), Map.of("new", "value"));
        doThrow(new AwsException("InvalidRequestException", "tag rejected", 400))
                .when(transfer).tagResource(server.getArn(), Map.of("new", "value"));
        StackResource resource = resource();
        resource.setPhysicalId(server.getArn());
        JsonNode props = mapper.readTree("""
                {"LoggingRole":"new-role", "Tags":[{"Key":"new", "Value":"value"}]}
                """);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context(server.getArn())));

        assertEquals("InvalidRequestException", failure.getErrorCode());
        verify(transfer).replaceServerConfiguration(server.getServerId(), List.of("SFTP"), "PUBLIC", null,
                null, "new-role", "TransferSecurityPolicy-2020-06");
        verify(transfer).replaceServerConfiguration(server.getServerId(), List.of("SFTP"), "PUBLIC", null,
                null, "original-role", "TransferSecurityPolicy-2020-06");
        verify(transfer).tagResource(server.getArn(), Map.of("old", "value"));
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        assertEquals(server.getArn(), resource.getPhysicalId());
        assertEquals("ONLINE", resource.getAttributes().get("State"));
    }

    @Test
    void rollbackHookRestoresPriorConfigurationAfterLaterResourceFails() throws Exception {
        Server server = server();
        server.setDomain("S3");
        server.setIdentityProviderType("SERVICE_MANAGED");
        server.setProtocols(List.of("SFTP"));
        server.setEndpointType("PUBLIC");
        server.setLoggingRole("original-role");
        server.setSecurityPolicyName("TransferSecurityPolicy-2020-06");
        when(transfer.getServer(server.getServerId())).thenReturn(server);
        when(transfer.replaceServerConfiguration(eq(server.getServerId()), any(), any(), any(), any(), any(), any()))
                .thenReturn(server);
        when(transfer.listTagsForResource(server.getArn())).thenReturn(Map.of());
        StackResource resource = resource();

        provisioner.provision(resource, mapper.readTree("{\"LoggingRole\":\"new-role\"}"),
                context(server.getArn()));
        provisioner.rollbackUpdate(resource);

        verify(transfer).replaceServerConfiguration(server.getServerId(), List.of("SFTP"), "PUBLIC", null,
                null, "original-role", "TransferSecurityPolicy-2020-06");
        assertNull(resource.getAttributes().get("__FlociTransferServerUpdateSnapshot"));
        assertEquals(server.getArn(), resource.getPhysicalId());
        assertEquals(server.getServerId(), resource.getAttributes().get("ServerId"));
        assertEquals("ONLINE", resource.getAttributes().get("State"));
    }

    @Test
    void unsupportedPropertyAndEmptyProtocolsFailExplicitly() throws Exception {
        AwsException unsupported = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                mapper.readTree("{\"WorkflowDetails\":{}}"), context(null)));
        assertEquals("ValidationError", unsupported.getErrorCode());

        AwsException empty = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                mapper.readTree("{\"Protocols\":[]}"), context(null)));
        assertEquals("ValidationError", empty.getErrorCode());
    }

    @Test
    void nullProtocolPropertyUsesDefault() throws Exception {
        Server server = server();
        when(transfer.createServer(eq("us-east-1"), eq("S3"), eq(List.of("SFTP")), eq("PUBLIC"),
                eq(null), eq("SERVICE_MANAGED"), eq(null), eq(null),
                eq("TransferSecurityPolicy-2020-06"), eq(Map.of()))).thenReturn(server);

        provisioner.provision(resource(), mapper.readTree("{\"Protocols\":null}"), context(null));

        verify(transfer).createServer(eq("us-east-1"), eq("S3"), eq(List.of("SFTP")), eq("PUBLIC"),
                eq(null), eq("SERVICE_MANAGED"), eq(null), eq(null),
                eq("TransferSecurityPolicy-2020-06"), eq(Map.of()));
    }

    @Test
    void deleteToleratesOnlyMissingServer() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(transfer).deleteServer("s-123");
        doThrow(new AwsException("ConflictException", "busy", 409))
                .when(transfer).deleteServer("s-456");

        provisioner.delete("AWS::Transfer::Server", "s-123", "us-east-1");
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Transfer::Server", "s-456", "us-east-1"));
        assertEquals("ConflictException", failure.getErrorCode());
    }

    @Test
    void deleteArnResolvesServerIdAcrossPartitions() {
        provisioner.delete("AWS::Transfer::Server",
                "arn:aws-cn:transfer:cn-north-1:000000000000:server/s-123", "cn-north-1");

        verify(transfer).deleteServer("s-123");
    }

    @Test
    void deleteRejectsArnForAnotherResourceType() {
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete("AWS::Transfer::Server",
                "arn:aws:transfer:us-east-1:000000000000:user/s-123/user", "us-east-1"));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(transfer, never()).deleteServer(any());
    }

    private ProvisionContext context(String priorId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> ((JsonNode) invocation.getArgument(0)).asText());
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(item.asText());
            }
            return values;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "stack", priorId);
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Server");
        resource.setResourceType("AWS::Transfer::Server");
        return resource;
    }

    private Server server() {
        Server server = new Server();
        server.setServerId("s-12345678901234567");
        server.setArn("arn:aws:transfer:us-east-1:000000000000:server/s-12345678901234567");
        server.setState("ONLINE");
        return server;
    }
}
