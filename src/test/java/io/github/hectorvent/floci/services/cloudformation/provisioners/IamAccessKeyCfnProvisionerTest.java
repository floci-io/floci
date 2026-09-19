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
 * {@code AWS::IAM::AccessKey} in isolation: the id backs Ref and Fn::GetAtt, the secret is
 * published, an unchanged update reuses the key, a UserName change or a missing UserName is
 * rejected, and delete removes the key using the stored user name.
 */
class IamAccessKeyCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::AccessKey";
    private static final String USER_NAME_ATTR = "__FlociAccessKeyUserName";

    private final IamService iam = mock(IamService.class);
    private final IamAccessKeyCfnProvisioner provisioner = new IamAccessKeyCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesTheIdAndSecretAndStoresTheUser() throws Exception {
        AccessKey key = new AccessKey();
        key.setAccessKeyId("AKIA123");
        key.setSecretAccessKey("secret-xyz");
        when(iam.createAccessKey("alice")).thenReturn(key);

        StackResource r = resource();
        provisioner.provision(r, props("{\"UserName\": \"alice\"}"), ctx(null));

        assertEquals("AKIA123", r.getPhysicalId());
        assertEquals("AKIA123", r.getAttributes().get("Id"));
        assertEquals("secret-xyz", r.getAttributes().get("SecretAccessKey"));
        assertEquals("alice", r.getAttributes().get(USER_NAME_ATTR));
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
        r.setPhysicalId("AKIA123");
        r.getAttributes().put("Id", "AKIA123");
        r.getAttributes().put("SecretAccessKey", "secret-xyz");
        r.getAttributes().put(USER_NAME_ATTR, "alice");

        provisioner.provision(r, props("{\"UserName\": \"alice\"}"), ctx("AKIA123"));

        assertEquals("AKIA123", r.getPhysicalId());
        verify(iam, never()).createAccessKey(anyString());
    }

    @Test
    void changingTheUserOnUpdateIsRejected() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("AKIA123");
        r.getAttributes().put(USER_NAME_ATTR, "alice");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"UserName\": \"bob\"}"), ctx("AKIA123")));
        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("replacement"), failure.getMessage());
    }

    @Test
    void deleteRemovesTheKeyUsingTheStoredUserName() {
        StackResource r = resource();
        r.setPhysicalId("AKIA123");
        r.getAttributes().put(USER_NAME_ATTR, "alice");

        provisioner.delete(r, "us-east-1");

        verify(iam).deleteAccessKey("alice", "AKIA123");
    }

    @Test
    void deleteToleratesAKeyAlreadyGone() {
        StackResource r = resource();
        r.setPhysicalId("AKIA123");
        r.getAttributes().put(USER_NAME_ATTR, "alice");
        doThrow(new AwsException("NoSuchEntity", "gone", 404))
                .when(iam).deleteAccessKey("alice", "AKIA123");

        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));
    }

    @Test
    void deleteSkipsWhenNoUserNameWasStored() {
        StackResource r = resource();
        r.setPhysicalId("AKIA123");

        provisioner.delete(r, "us-east-1");

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
