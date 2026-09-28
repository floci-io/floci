package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::AccessKey} in isolation: the id backs Ref and Fn::GetAtt, an unchanged update
 * reuses the key, a Serial bump rotates it by replacement, a missing UserName is rejected, and
 * delete removes the key by recovering the owning user from the id.
 */
class IamAccessKeyCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::AccessKey";
    private static final String SERIAL_ATTR = "__FlociAccessKeySerial";

    private final IamService iam = mock(IamService.class);
    private final IamAccessKeyCfnProvisioner provisioner = new IamAccessKeyCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesTheIdAndSecret() throws Exception {
        AccessKey key = new AccessKey();
        key.setAccessKeyId("AKIA1");
        key.setSecretAccessKey("secret-1");
        when(iam.createAccessKey("alice")).thenReturn(key);

        StackResource r = resource();
        provisioner.provision(r, props("{\"UserName\": \"alice\"}"), ctx(null));

        assertEquals("AKIA1", r.getPhysicalId());
        assertEquals("AKIA1", r.getAttributes().get("Id"));
        assertEquals("secret-1", r.getAttributes().get("SecretAccessKey"));
        verify(iam).createAccessKey("alice");
    }

    @Test
    void missingUserNameIsRejected() {
        StackResource r = resource();
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{}"), ctx(null)));
        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam, never()).createAccessKey(anyString());
    }

    @Test
    void anUnchangedUpdateReusesTheExistingKey() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("AKIA1");
        r.getAttributes().put("Id", "AKIA1");
        r.getAttributes().put("SecretAccessKey", "secret-1");
        r.getAttributes().put(SERIAL_ATTR, "");
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.of("alice"));

        provisioner.provision(r, props("{\"UserName\": \"alice\"}"), ctx("AKIA1"));

        assertEquals("AKIA1", r.getPhysicalId());
        verify(iam, never()).createAccessKey(anyString());
    }

    @Test
    void createWithInactiveStatusDeactivatesTheKey() throws Exception {
        AccessKey key = new AccessKey();
        key.setAccessKeyId("AKIA1");
        key.setSecretAccessKey("secret-1");
        when(iam.createAccessKey("alice")).thenReturn(key);

        provisioner.provision(resource(), props("{\"UserName\": \"alice\", \"Status\": \"Inactive\"}"), ctx(null));

        verify(iam).createAccessKey("alice");
        verify(iam).updateAccessKey("alice", "AKIA1", "Inactive");
    }

    @Test
    void anUnchangedUpdateReconcilesStatus() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("AKIA1");
        r.getAttributes().put(SERIAL_ATTR, "");
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.of("alice"));

        provisioner.provision(r, props("{\"UserName\": \"alice\", \"Status\": \"Inactive\"}"), ctx("AKIA1"));

        verify(iam, never()).createAccessKey(anyString());
        verify(iam).updateAccessKey("alice", "AKIA1", "Inactive");
    }

    @Test
    void anUpdateThatOmitsStatusLeavesItAlone() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("AKIA1");
        r.getAttributes().put(SERIAL_ATTR, "");
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.of("alice"));

        provisioner.provision(r, props("{\"UserName\": \"alice\"}"), ctx("AKIA1"));

        verify(iam, never()).createAccessKey(anyString());
        verify(iam, never()).updateAccessKey(anyString(), anyString(), anyString());
    }

    @Test
    void aBogusStatusIsRejectedBeforeCreatingAKey() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(), props("{\"UserName\": \"alice\", \"Status\": \"Bogus\"}"), ctx(null)));

        assertEquals("ValidationError", e.getErrorCode());
        verify(iam, never()).createAccessKey(anyString());
    }

    @Test
    void bumpingSerialOnUpdateRotatesTheKey() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("AKIA1");
        r.getAttributes().put("Id", "AKIA1");
        r.getAttributes().put(SERIAL_ATTR, "1");
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.of("alice"));
        AccessKey rotated = new AccessKey();
        rotated.setAccessKeyId("AKIA2");
        rotated.setSecretAccessKey("secret-2");
        when(iam.createAccessKey("alice")).thenReturn(rotated);

        provisioner.provision(r, props("{\"UserName\": \"alice\", \"Serial\": 2}"), ctx("AKIA1"));

        assertEquals("AKIA2", r.getPhysicalId());
        verify(iam).createAccessKey("alice");
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("AKIA1", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void deleteRemovesTheKeyRecoveringUserFromId() {
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.of("alice"));

        provisioner.delete(TYPE, "AKIA1", "us-east-1");

        verify(iam).deleteAccessKey("alice", "AKIA1");
    }

    @Test
    void deleteToleratesAKeyAlreadyGone() {
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.of("alice"));
        doThrow(new AwsException("NoSuchEntity", "gone", 404))
                .when(iam).deleteAccessKey("alice", "AKIA1");

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "AKIA1", "us-east-1"));
    }

    @Test
    void deleteSkipsWhenTheKeyIsUnknown() {
        when(iam.findUserNameByAccessKeyId("AKIA1")).thenReturn(Optional.empty());

        provisioner.delete(TYPE, "AKIA1", "us-east-1");

        verify(iam, never()).deleteAccessKey(anyString(), anyString());
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Key");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
