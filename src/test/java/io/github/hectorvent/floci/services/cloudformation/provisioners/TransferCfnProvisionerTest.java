package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.transfer.TransferService;
import io.github.hectorvent.floci.services.transfer.model.HomeDirectoryMapping;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferCfnProvisionerTest {

    private static final String SERVER_ID = "s-12345678901234567";

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

    @Test
    void userCreateRecordsArnRefAndExactAttributes() throws Exception {
        User user = user("alice");
        when(transfer.createUser("s-12345678901234567", "us-east-1", "alice", "role-arn", "/b/home", null,
                List.of(), Map.of())).thenReturn(user);
        StackResource resource = userResource();

        provisioner.provision(resource, mapper.readTree("""
                {"ServerId":"s-12345678901234567","UserName":"alice","Role":"role-arn","HomeDirectory":"/b/home",
                 "SshPublicKeys":["ssh-rsa AAAA"]}"""), context(null));

        assertEquals(user.getArn(), resource.getPhysicalId());
        assertEquals(Map.of("Arn", user.getArn(),
                "__FlociTransferServerTemplateTagKeys", "[]",
                "__FlociTransferUserTemplateSshKeys", "[\"ssh-rsa AAAA\"]"), resource.getAttributes());
        verify(transfer).importSshPublicKey("s-12345678901234567", "alice", "ssh-rsa AAAA");
    }

    @Test
    void userUpdateWithSameKeyUpdatesInPlace() throws Exception {
        User user = user("alice");
        when(transfer.getUser(SERVER_ID, "alice")).thenReturn(user);
        when(transfer.listTagsForResource(user.getArn())).thenReturn(Map.of());
        when(transfer.updateUser(eq(SERVER_ID), eq("alice"), eq("role-2"), eq("/"), eq("PATH"), any()))
                .thenReturn(user);
        StackResource resource = userResource();

        provisioner.provision(resource, mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\",\"Role\":\"role-2\"}"),
                context(user.getArn()));

        assertEquals(user.getArn(), resource.getPhysicalId());
        verify(transfer, never()).createUser(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void userUpdateClearsHomeDirectoryMappingsRemovedFromTemplate() throws Exception {
        User user = user("alice");
        user.setHomeDirectoryType("LOGICAL");
        user.setHomeDirectoryMappings(List.of(new HomeDirectoryMapping("/a", "/b/a")));
        when(transfer.getUser(SERVER_ID, "alice")).thenReturn(user);
        when(transfer.listTagsForResource(user.getArn())).thenReturn(Map.of());
        when(transfer.updateUser(any(), any(), any(), any(), any(), any())).thenReturn(user);

        provisioner.provision(userResource(), mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\",\"Role\":\"role-2\"}"),
                context(user.getArn()));

        verify(transfer).updateUser(SERVER_ID, "alice", "role-2", "/", "PATH", List.of());
    }

    @Test
    void userUpdateKeepsSshKeysAddedOutsideTheStack() throws Exception {
        User user = user("alice");
        user.setSshPublicKeys(List.of(new SshPublicKey("key-managed", "ssh-rsa OLD", null),
                new SshPublicKey("key-manual", "ssh-rsa MANUAL", null)));
        when(transfer.getUser(SERVER_ID, "alice")).thenReturn(user);
        when(transfer.listTagsForResource(user.getArn())).thenReturn(Map.of());
        when(transfer.updateUser(any(), any(), any(), any(), any(), any())).thenReturn(user);
        StackResource resource = userResource();
        resource.getAttributes().put("__FlociTransferUserTemplateSshKeys", "[\"ssh-rsa OLD\"]");

        provisioner.provision(resource, mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\",\"Role\":\"r\","
                        + "\"SshPublicKeys\":[\"ssh-rsa NEW\"]}"), context(user.getArn()));

        verify(transfer).deleteSshPublicKey(SERVER_ID, "alice", "key-managed");
        verify(transfer, never()).deleteSshPublicKey(SERVER_ID, "alice", "key-manual");
        verify(transfer).importSshPublicKey(SERVER_ID, "alice", "ssh-rsa NEW");
        assertEquals("[\"ssh-rsa NEW\"]", resource.getAttributes().get("__FlociTransferUserTemplateSshKeys"));
    }

    @Test
    void userUpdateWithoutPriorKeyRecordDeletesNoKeys() throws Exception {
        User user = user("alice");
        user.setSshPublicKeys(List.of(new SshPublicKey("key-1", "ssh-rsa ANY", null)));
        when(transfer.getUser(SERVER_ID, "alice")).thenReturn(user);
        when(transfer.listTagsForResource(user.getArn())).thenReturn(Map.of());
        when(transfer.updateUser(any(), any(), any(), any(), any(), any())).thenReturn(user);

        provisioner.provision(userResource(), mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\",\"Role\":\"r\"}"),
                context(user.getArn()));

        verify(transfer, never()).deleteSshPublicKey(any(), any(), any());
    }

    @Test
    void userCreateRemovesUserWhenKeyImportFails() throws Exception {
        User user = user("alice");
        when(transfer.createUser(eq(SERVER_ID), eq("us-east-1"), eq("alice"), eq("r"), any(), any(), any(),
                any())).thenReturn(user);
        AwsException importFailure = new AwsException("InvalidRequestException", "bad key", 400);
        when(transfer.importSshPublicKey(SERVER_ID, "alice", "ssh-rsa BAD")).thenThrow(importFailure);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(userResource(),
                mapper.readTree("{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\","
                        + "\"Role\":\"r\",\"SshPublicKeys\":[\"ssh-rsa BAD\"]}"), context(null)));

        assertSame(importFailure, failure);
        verify(transfer).deleteUser(SERVER_ID, "alice");
    }

    @Test
    void userCreateKeepsOriginalErrorWhenCleanupFails() throws Exception {
        User user = user("alice");
        when(transfer.createUser(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(user);
        AwsException importFailure = new AwsException("InvalidRequestException", "bad key", 400);
        when(transfer.importSshPublicKey(any(), any(), any())).thenThrow(importFailure);
        AwsException cleanupFailure = new AwsException("InternalFailure", "boom", 500);
        doThrow(cleanupFailure).when(transfer).deleteUser(SERVER_ID, "alice");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(userResource(),
                mapper.readTree("{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\","
                        + "\"Role\":\"r\",\"SshPublicKeys\":[\"ssh-rsa BAD\"]}"), context(null)));

        assertSame(importFailure, failure);
        assertEquals(cleanupFailure, failure.getSuppressed()[0]);
    }

    @Test
    void userRollbackHookRestoresPriorStateAfterLaterResourceFails() throws Exception {
        User user = user("alice");
        user.setRole("role-1");
        user.setHomeDirectory("/b/old");
        user.setHomeDirectoryType("LOGICAL");
        user.setHomeDirectoryMappings(List.of(new HomeDirectoryMapping("/a", "/b/a")));
        Instant imported = Instant.parse("2026-01-02T03:04:05Z");
        user.setSshPublicKeys(List.of(new SshPublicKey("key-old", "ssh-rsa OLD", imported)));
        when(transfer.getUser(SERVER_ID, "alice")).thenReturn(user);
        when(transfer.listTagsForResource(user.getArn())).thenReturn(Map.of("env", "old"));
        User updated = user("alice");
        updated.setSshPublicKeys(List.of(new SshPublicKey("key-new", "ssh-rsa NEW", null),
                new SshPublicKey("key-outside", "ssh-rsa OUTSIDE", null)));
        when(transfer.updateUser(any(), any(), any(), any(), any(), any())).thenReturn(updated);
        StackResource resource = userResource();
        resource.getAttributes().put("__FlociTransferUserTemplateSshKeys", "[\"ssh-rsa OLD\"]");
        resource.getAttributes().put("__FlociTransferServerTemplateTagKeys", "[\"env\"]");

        provisioner.provision(resource, mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\",\"Role\":\"role-2\","
                        + "\"SshPublicKeys\":[\"ssh-rsa NEW\"],\"Tags\":[{\"Key\":\"team\",\"Value\":\"x\"}]}"),
                context(user.getArn()));
        assertTrue(provisioner.rollbackUpdate(resource));

        ArgumentCaptor<List<HomeDirectoryMapping>> restored = ArgumentCaptor.forClass(List.class);
        verify(transfer).updateUser(eq(SERVER_ID), eq("alice"), eq("role-1"), eq("/b/old"), eq("LOGICAL"),
                restored.capture());
        assertEquals("/a", restored.getValue().get(0).getEntry());
        verify(transfer, atLeastOnce()).deleteSshPublicKey(SERVER_ID, "alice", "key-new");
        verify(transfer, never()).deleteSshPublicKey(SERVER_ID, "alice", "key-outside");
        verify(transfer, never()).importSshPublicKey(SERVER_ID, "alice", "ssh-rsa OLD");
        ArgumentCaptor<SshPublicKey> restoredKey = ArgumentCaptor.forClass(SshPublicKey.class);
        verify(transfer).restoreSshPublicKey(eq(SERVER_ID), eq("alice"), restoredKey.capture());
        assertEquals("key-old", restoredKey.getValue().getSshPublicKeyId());
        assertEquals("ssh-rsa OLD", restoredKey.getValue().getSshPublicKeyBody());
        assertEquals(imported, restoredKey.getValue().getDateImported());
        verify(transfer).untagResource(user.getArn(), List.of("team"));
        assertNull(resource.getAttributes().get("__FlociTransferUserUpdateSnapshot"));
        assertEquals("[\"ssh-rsa OLD\"]", resource.getAttributes().get("__FlociTransferUserTemplateSshKeys"));
        assertEquals("[\"env\"]", resource.getAttributes().get("__FlociTransferServerTemplateTagKeys"));
    }

    @Test
    void userUpdateFailureRestoresPriorStateEagerly() throws Exception {
        User user = user("alice");
        user.setRole("role-1");
        user.setHomeDirectory("/");
        user.setHomeDirectoryType("PATH");
        when(transfer.getUser(SERVER_ID, "alice")).thenReturn(user);
        when(transfer.listTagsForResource(user.getArn())).thenReturn(Map.of());
        when(transfer.updateUser(any(), any(), any(), any(), any(), any())).thenReturn(user);
        doThrow(new AwsException("InvalidRequestException", "tag rejected", 400))
                .when(transfer).tagResource(user.getArn(), Map.of("new", "v"));
        StackResource resource = userResource();

        assertThrows(AwsException.class, () -> provisioner.provision(resource, mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"alice\",\"Role\":\"role-2\","
                        + "\"Tags\":[{\"Key\":\"new\",\"Value\":\"v\"}]}"), context(user.getArn())));

        verify(transfer).updateUser(eq(SERVER_ID), eq("alice"), eq("role-1"), eq("/"), eq("PATH"), any());
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
    }

    @Test
    void userUserNameChangeCreatesReplacement() throws Exception {
        User replacement = user("bob");
        when(transfer.createUser("s-12345678901234567", "us-east-1", "bob", "role-arn", null, null, List.of(),
                Map.of())).thenReturn(replacement);
        StackResource resource = userResource();

        provisioner.provision(resource, mapper.readTree(
                "{\"ServerId\":\"s-12345678901234567\",\"UserName\":\"bob\",\"Role\":\"role-arn\"}"),
                context(user("alice").getArn()));

        assertEquals(replacement.getArn(), resource.getPhysicalId());
        verify(transfer, never()).updateUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void userRejectsUnsupportedPropertyAndMissingRole() throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(userResource(), mapper.readTree(
                "{\"ServerId\":\"s-1\",\"UserName\":\"a\",\"Role\":\"r\",\"Policy\":\"{}\"}"), context(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(userResource(), mapper.readTree(
                "{\"ServerId\":\"s-1\",\"UserName\":\"a\"}"), context(null)));
    }

    @Test
    void userDeleteToleratesAlreadyGoneOnly() {
        User user = user("alice");
        provisioner.delete("AWS::Transfer::User", user.getArn(), "us-east-1");
        verify(transfer).deleteUser("s-12345678901234567", "alice");

        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(transfer).deleteUser("s-12345678901234567", "alice");
        provisioner.delete("AWS::Transfer::User", user.getArn(), "us-east-1");

        doThrow(new AwsException("InternalServiceError", "boom", 500))
                .when(transfer).deleteUser("s-12345678901234567", "alice");
        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Transfer::User", user.getArn(), "us-east-1"));
    }

    private StackResource userResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("User");
        resource.setResourceType("AWS::Transfer::User");
        return resource;
    }

    private User user(String name) {
        User user = new User();
        user.setUserName(name);
        user.setArn("arn:aws:transfer:us-east-1:000000000000:user/s-12345678901234567/" + name);
        return user;
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
