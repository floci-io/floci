package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Cognito user pool group CFN provisioner in isolation, with only CognitoService mocked. */
class CognitoUserPoolGroupCfnProvisionerTest {

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoUserPoolGroupCfnProvisioner provisioner =
            new CognitoUserPoolGroupCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    /**
     * CloudFormation hands a provisioner the prior physical id through the context, so an update
     * has to be modelled there and not only on the resource.
     */
    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private ObjectNode props(String userPoolId, String groupName) {
        ObjectNode props = mapper.createObjectNode();
        if (userPoolId != null) {
            props.put("UserPoolId", userPoolId);
        }
        if (groupName != null) {
            props.put("GroupName", groupName);
        }
        return props;
    }

    private StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("AdminGroup");
        r.setResourceType("AWS::Cognito::UserPoolGroup");
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void servesTheUserPoolGroupType() {
        assertEquals(Set.of("AWS::Cognito::UserPoolGroup"), provisioner.resourceTypes());
    }

    @Test
    void createsTheGroupAndRefResolvesToItsName() {
        ObjectNode props = props("us-east-1_pool", "admin");
        props.put("Description", "Back office");
        props.put("Precedence", 7);
        props.put("RoleArn", "arn:aws:iam::000000000000:role/admins");
        StackResource r = resource(null);

        provisioner.provision(r, props, ctx());

        verify(cognito).createGroup("us-east-1_pool", "admin", "Back office", 7,
                "arn:aws:iam::000000000000:role/admins");
        assertEquals("admin", r.getPhysicalId(), "Ref must resolve to the group name");
        assertEquals("us-east-1_pool", r.getAttributes().get("UserPoolId"),
                "delete needs the pool id, which the physical id cannot carry");
    }

    @Test
    void generatesAPhysicalNameWhenGroupNameIsAbsent() {
        StackResource r = resource(null);

        provisioner.provision(r, props("us-east-1_pool", null), ctx());

        assertTrue(r.getPhysicalId().startsWith("my-stack-AdminGroup-"), r.getPhysicalId());
        verify(cognito).createGroup(eq("us-east-1_pool"), eq(r.getPhysicalId()), isNull(), isNull(), isNull());
    }

    @Test
    void anUnnamedGroupKeepsItsGeneratedNameAcrossUpdates() {
        // GroupName is optional and CloudFormation generates one. Generating a fresh name on every
        // UpdateStack would create a second group and orphan the first, so the generated name has
        // to survive the update.
        StackResource created = resource(null);
        provisioner.provision(created, props("us-east-1_pool", null), ctx());
        String generated = created.getPhysicalId();

        StackResource updated = resource(generated);
        provisioner.provision(updated, props("us-east-1_pool", null), ctx(generated));

        assertEquals(generated, updated.getPhysicalId(),
                "an unnamed group must keep its generated name across updates");
        verify(cognito).updateGroup("us-east-1_pool", generated, null, null, null);
        verify(cognito, times(1)).createGroup(any(), any(), any(), any(), any());
    }

    @Test
    void reProvisioningTheSameGroupUpdatesInPlace() {
        // UpdateStack re-provisions every resource whether or not its properties changed, so an
        // unchanged group must reconcile rather than call CreateGroup again and fail the stack.
        StackResource r = resource("admin");

        provisioner.provision(r, props("us-east-1_pool", "admin"), ctx("admin"));

        verify(cognito).updateGroup("us-east-1_pool", "admin", null, null, null);
        verify(cognito, never()).createGroup(any(), any(), any(), any(), any());
    }

    @Test
    void renamingTheGroupCreatesUnderTheNewName() {
        StackResource r = resource("admin");

        provisioner.provision(r, props("us-east-1_pool", "operators"), ctx("admin"));

        verify(cognito).createGroup("us-east-1_pool", "operators", null, null, null);
        assertEquals("operators", r.getPhysicalId());
    }

    @Test
    void missingUserPoolIdFailsTheResourceRatherThanStubbingIt() {
        StackResource r = resource(null);

        assertThrows(IllegalArgumentException.class,
                () -> provisioner.provision(r, props(null, "admin"), ctx()));
        verifyNoInteractions(cognito);
    }

    @Test
    void aNonIntegerPrecedenceFailsTheResourceRatherThanBeingDropped() {
        ObjectNode props = props("us-east-1_pool", "admin");
        props.put("Precedence", "not-a-number");
        StackResource r = resource(null);

        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(r, props, ctx()));
        verifyNoInteractions(cognito);
    }

    @Test
    void deleteUsesTheStoredPoolIdAndTheGroupName() {
        StackResource r = resource("admin");
        r.setAttributes(new HashMap<>(Map.of("UserPoolId", "us-east-1_pool")));

        provisioner.delete(r, "us-east-1");

        verify(cognito).deleteGroup("us-east-1_pool", "admin");
    }

    @Test
    void deleteWithoutAStoredPoolIdIsANoOp() {
        // The CREATE-rollback shape: nothing was provisioned, so there is nothing to address.
        provisioner.delete(resource("admin"), "us-east-1");

        verifyNoInteractions(cognito);
    }

    @Test
    void deletingAGroupThatIsAlreadyGoneIsTolerated() {
        StackResource r = resource("admin");
        r.setAttributes(new HashMap<>(Map.of("UserPoolId", "us-east-1_pool")));
        doThrow(new AwsException("ResourceNotFoundException", "Group not found: admin", 400))
                .when(cognito).deleteGroup("us-east-1_pool", "admin");

        provisioner.delete(r, "us-east-1");

        verify(cognito).deleteGroup("us-east-1_pool", "admin");
    }

    @Test
    void deleteSurfacesAFailureThatIsNotAMissingGroup() {
        StackResource r = resource("admin");
        r.setAttributes(new HashMap<>(Map.of("UserPoolId", "us-east-1_pool")));
        doThrow(new AwsException("InternalFailure", "storage unavailable", 500))
                .when(cognito).deleteGroup("us-east-1_pool", "admin");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));

        assertEquals("InternalFailure", thrown.getErrorCode());
    }
}
