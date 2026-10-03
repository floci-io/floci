package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Cognito::UserPoolUserToGroupAttachment} in isolation: Ref is
 * {@code <UserPoolId>|<GroupName>|<Username>}, every property is createOnly so any change is a
 * replacement, and delete removes the membership from the id alone.
 */
class CognitoUserPoolUserToGroupAttachmentCfnProvisionerTest {

    private static final String TYPE = "AWS::Cognito::UserPoolUserToGroupAttachment";
    private static final String POOL = "us-east-1_pool";
    private static final String ID_A = POOL + "|grp-a|user-one";
    private static final String ID_B = POOL + "|grp-b|user-one";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoUserPoolUserToGroupAttachmentCfnProvisioner provisioner =
            new CognitoUserPoolUserToGroupAttachmentCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void servesTheAttachmentType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createAddsTheUserToTheGroupAndRefIsTheCompositeId() {
        StackResource r = resource(null);

        provisioner.provision(r, props(POOL, "grp-a", "user-one"), ctx(null));

        verify(cognito).adminAddUserToGroup(POOL, "grp-a", "user-one");
        assertEquals(ID_A, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UserPoolId", "GroupName", "Username"})
    void eachRequiredPropertyIsValidated(String missing) {
        ObjectNode props = props(POOL, "grp-a", "user-one");
        props.remove(missing);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(null), props, ctx(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(TYPE + " requires " + missing, failure.getMessage());
        verifyNoInteractions(cognito);
    }

    @Test
    void anUnchangedUpdateKeepsTheIdAndCallsNothingOnCognito() {
        StackResource r = resource(ID_A);

        provisioner.provision(r, props(POOL, "grp-a", "user-one"), ctx(ID_A));

        assertEquals(ID_A, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
        verifyNoInteractions(cognito);
    }

    @Test
    void aGroupNameChangeIsAReplacementWhoseCleanupRemovesOnlyTheOldMembership() {
        StackResource r = resource(ID_A);

        provisioner.provision(r, props(POOL, "grp-b", "user-one"), ctx(ID_A));

        verify(cognito).adminAddUserToGroup(POOL, "grp-b", "user-one");
        assertEquals(ID_B, r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(ID_A, provisioner.updateCleanupPhysicalId(r));

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.complete());
        verify(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");
        verify(cognito, never()).adminRemoveUserFromGroup(POOL, "grp-b", "user-one");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAReplacementRemovesTheNewMembershipAndRestoresThePriorId() {
        StackResource r = resource(ID_A);
        provisioner.provision(r, props(POOL, "grp-b", "user-one"), ctx(ID_A));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito).adminRemoveUserFromGroup(POOL, "grp-b", "user-one");
        verify(cognito, never()).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");
        assertEquals(ID_A, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAnUpdateThatReplacedNothingIsDone() {
        StackResource r = resource(ID_A);
        provisioner.provision(r, props(POOL, "grp-a", "user-one"), ctx(ID_A));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito, never()).adminRemoveUserFromGroup(any(), any(), any());
        assertEquals(ID_A, r.getPhysicalId());
    }

    @Test
    void deleteRemovesTheMembershipFromTheIdAlone() {
        provisioner.delete(TYPE, ID_A, "us-east-1");

        verify(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UserNotFoundException", "ResourceNotFoundException"})
    void deleteToleratesAMembershipThatIsAlreadyGone(String code) {
        doThrow(new AwsException(code, "gone", 400))
                .when(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");

        assertDoesNotThrow(() -> provisioner.delete(TYPE, ID_A, "us-east-1"));
    }

    @Test
    void deleteSurfacesAnyOtherFailure() {
        doThrow(new AwsException("InternalFailure", "storage unavailable", 500))
                .when(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, ID_A, "us-east-1"));

        assertEquals("InternalFailure", thrown.getErrorCode());
    }

    @Test
    void deleteOfAMalformedIdCallsNothing() {
        provisioner.delete(TYPE, "not-a-composite-id", "us-east-1");

        verifyNoInteractions(cognito);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private ObjectNode props(String userPoolId, String groupName, String username) {
        ObjectNode props = mapper.createObjectNode();
        props.put("UserPoolId", userPoolId);
        props.put("GroupName", groupName);
        props.put("Username", username);
        return props;
    }

    private static StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Attach");
        r.setResourceType(TYPE);
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
