package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Cognito::UserPoolUserToGroupAttachment} in isolation: Ref is
 * {@code <UserPoolId>|<GroupName>|<Username>}, every property is createOnly so any change is a
 * replacement, a user already in the group is refused as AlreadyExists, a change that keeps the Ref is
 * refused as a custom-named replacement, and delete removes the membership from its recorded parts.
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

    @BeforeEach
    void everyUserExistsUnderItsOwnNameAndEveryGroupStartsEmpty() {
        when(cognito.adminGetUser(any(), any())).thenAnswer(inv -> user(inv.getArgument(1)));
        when(cognito.getGroup(any(), any())).thenAnswer(inv -> group(inv.getArgument(1)));
    }

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
    void createRefusesAUserAlreadyInTheGroupNamingTheCanonicalUser() {
        when(cognito.adminGetUser("pool", "alice@example.com")).thenReturn(user("alice"));
        when(cognito.getGroup("pool", "g")).thenReturn(group("g", "alice"));
        StackResource r = resource(null);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("pool", "g", "alice@example.com"), ctx(null)));

        assertEquals("AlreadyExists", failure.getErrorCode());
        assertEquals("User with name alice already exists in Group g.", failure.getMessage());
        verify(cognito, never()).adminAddUserToGroup(any(), any(), any());
    }

    @Test
    void anAliasToCanonicalReplacementOfAMemberFailsAndItsRollbackRemovesNothing() {
        when(cognito.adminGetUser("pool", "alice@example.com")).thenReturn(user("alice"));
        StackResource r = created("pool", "g", "alice@example.com");
        String aliasId = "pool|g|alice@example.com";
        when(cognito.getGroup("pool", "g")).thenReturn(group("g", "alice"));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("pool", "g", "alice"), ctx(aliasId)));

        assertEquals("AlreadyExists", failure.getErrorCode());
        assertEquals("User with name alice already exists in Group g.", failure.getMessage());
        assertEquals(aliasId, r.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito, never()).adminRemoveUserFromGroup(any(), any(), any());
        assertEquals(aliasId, r.getPhysicalId());
    }

    @Test
    void aChangeThatKeepsTheRefIsRefusedAsACustomNamedReplacement() {
        StackResource r = created("pool", "a", "b|c");
        clearInvocations(cognito);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("pool", "a|b", "c"), ctx("pool|a|b|c")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename pool|a|b|c and update the stack again.", failure.getMessage());
        assertEquals("pool|a|b|c", r.getPhysicalId());
        verify(cognito, never()).adminAddUserToGroup(any(), any(), any());
        verify(cognito, never()).adminRemoveUserFromGroup(any(), any(), any());
    }

    @Test
    void deleteRemovesAMembershipWhoseGroupNameHoldsAPipe() {
        StackResource r = created("pool", "a|b", "c");

        provisioner.delete(r, "us-east-1");

        verify(cognito).adminRemoveUserFromGroup("pool", "a|b", "c");
    }

    @Test
    void aReplacementAwayFromAPipedGroupNameRemovesTheRecordedMembership() {
        StackResource r = created("pool", "a|b", "c");
        provisioner.provision(r, props("pool", "x", "c"), ctx("pool|a|b|c"));

        assertTrue(provisioner.completeUpdate(r).complete());

        verify(cognito).adminRemoveUserFromGroup("pool", "a|b", "c");
        verify(cognito, never()).adminRemoveUserFromGroup("pool", "a", "b|c");
        verify(cognito, never()).adminRemoveUserFromGroup("pool", "x", "c");
    }

    @Test
    void theMembershipRecordKeepsOnlyTheCurrentIdAndTheIdsStillOwedADelete() throws Exception {
        String idC = POOL + "|grp-c|user-one";
        StackResource r = created(POOL, "grp-a", "user-one");
        provisioner.provision(r, props(POOL, "grp-b", "user-one"), ctx(ID_A));
        assertTrue(provisioner.completeUpdate(r).complete());
        provisioner.clearUpdate(r);

        provisioner.provision(r, props(POOL, "grp-c", "user-one"), ctx(ID_B));

        assertEquals(Set.of(ID_B, idC), recordedIds(r));
        assertTrue(provisioner.completeUpdate(r).complete());
        provisioner.clearUpdate(r);
        verify(cognito).adminRemoveUserFromGroup(POOL, "grp-b", "user-one");
        provisioner.provision(r, props(POOL, "grp-c", "user-one"), ctx(idC));
        assertEquals(Set.of(idC), recordedIds(r));
    }

    @Test
    void aGroupNameChangeIsAReplacementWhoseCleanupRemovesOnlyTheOldMembership() {
        StackResource r = created(POOL, "grp-a", "user-one");

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
        StackResource r = created(POOL, "grp-a", "user-one");
        provisioner.provision(r, props(POOL, "grp-b", "user-one"), ctx(ID_A));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito).adminRemoveUserFromGroup(POOL, "grp-b", "user-one");
        verify(cognito, never()).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");
        assertEquals(ID_A, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aReplacementWhoseRollbackDeleteFailsIsRemovedFromItsRecordedPartsByTheNextCleanup() {
        String priorId = POOL + "|g|alice";
        StackResource r = created(POOL, "g", "alice");
        provisioner.provision(r, props(POOL, "b|c", "alice"), ctx(priorId));
        doThrow(new AwsException("InternalErrorException", "storage unavailable", 500)).doNothing()
                .when(cognito).adminRemoveUserFromGroup(POOL, "b|c", "alice");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("InternalErrorException", failure.getErrorCode());
        assertEquals(priorId, r.getPhysicalId());

        assertTrue(provisioner.completeUpdate(r).complete());

        verify(cognito, times(2)).adminRemoveUserFromGroup(POOL, "b|c", "alice");
        verify(cognito, never()).adminRemoveUserFromGroup(POOL, "g", "alice");
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
    void deleteRemovesTheRecordedMembership() {
        StackResource r = created(POOL, "grp-a", "user-one");

        provisioner.delete(r, "us-east-1");

        verify(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UserNotFoundException", "ResourceNotFoundException"})
    void deleteToleratesAMembershipThatIsAlreadyGone(String code) {
        StackResource r = created(POOL, "grp-a", "user-one");
        doThrow(new AwsException(code, "gone", 400))
                .when(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");

        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));
    }

    @Test
    void deleteSurfacesAnyOtherFailure() {
        StackResource r = created(POOL, "grp-a", "user-one");
        doThrow(new AwsException("InternalFailure", "storage unavailable", 500))
                .when(cognito).adminRemoveUserFromGroup(POOL, "grp-a", "user-one");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.delete(r, "us-east-1"));

        assertEquals("InternalFailure", thrown.getErrorCode());
    }

    @Test
    void deleteOfAnIdNeverRecordedRemovesTheMembershipItNames() {
        provisioner.delete(resource(POOL + "|grp|alice"), "us-east-1");

        verify(cognito).adminRemoveUserFromGroup(POOL, "grp", "alice");
    }

    @Test
    void deleteOfAnUnrecordedIdHoldingAnExtraPipeIsRefused() {
        StackResource r = resource(POOL + "|a|b|c");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));

        assertEquals("InvalidRequest", failure.getErrorCode());
        verifyNoInteractions(cognito);
    }

    @Test
    void deleteOfAnIdThatIsNotACompositeCallsNothing() {
        assertDoesNotThrow(() -> provisioner.delete(resource("stub-id"), "us-east-1"));

        verifyNoInteractions(cognito);
    }

    private StackResource created(String userPoolId, String groupName, String username) {
        StackResource r = resource(null);
        provisioner.provision(r, props(userPoolId, groupName, username), ctx(null));
        return r;
    }

    private Set<String> recordedIds(StackResource r) throws Exception {
        Set<String> ids = new HashSet<>();
        mapper.readTree(r.getAttributes().get("__FlociCognitoMemberships")).fieldNames().forEachRemaining(ids::add);
        return ids;
    }

    private static CognitoUser user(String username) {
        CognitoUser user = new CognitoUser();
        user.setUsername(username);
        return user;
    }

    private static CognitoGroup group(String groupName, String... members) {
        CognitoGroup group = new CognitoGroup();
        group.setGroupName(groupName);
        group.setUserNames(List.of(members));
        return group;
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
