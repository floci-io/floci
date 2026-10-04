package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The Cognito user pool user CFN provisioner in isolation, with only CognitoService mocked: Ref is
 * the username Cognito reports, an unchanged template is a no-op, a changed custom-named user is
 * refused, any other change replaces the user, and every delete goes to the user's own pool.
 */
class CognitoUserPoolUserCfnProvisionerTest {

    private static final String TYPE = "AWS::Cognito::UserPoolUser";
    private static final String CREATE_ONLY_ATTR = "__FlociCreateOnly";
    private static final String POOLS_ATTR = "__FlociCognitoUserPools";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoUserPoolUserCfnProvisioner provisioner = new CognitoUserPoolUserCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void servesTheUserPoolUserType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createPassesUsernameAttributesAndMessageActionThrough() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one", "MessageAction": "SUPPRESS",
                 "ForceAliasCreation": true, "DesiredDeliveryMediums": ["EMAIL"],
                 "UserAttributes": [{"Name": "email", "Value": "one@example.com"},
                                    {"Name": "name", "Value": "One"}]}"""), ctx(null));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("email", "one@example.com");
        attributes.put("name", "One");
        verify(cognito).adminCreateUser("pool-a", "user-one", attributes, null, "SUPPRESS", true);
        assertEquals("user-one", r.getPhysicalId(), "Ref must resolve to the username");
        assertTrue(r.getAttributes().keySet().stream().allMatch(key -> key.startsWith("__Floci")),
                "AWS::Cognito::UserPoolUser exposes no Fn::GetAtt attribute");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anEmailAsUsernamePoolRefsTheCanonicalUsernameCognitoReturns() throws Exception {
        CognitoUser created = new CognitoUser();
        created.setUsername("d275a484-c0c1-70b3-46da-d9dafa5205e3");
        when(cognito.adminCreateUser(anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(created);
        StackResource r = resource(null);

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "alias.user@example.com",
                 "UserAttributes": [{"Name": "email", "Value": "alias.user@example.com"}]}"""), ctx(null));

        verify(cognito).adminCreateUser("pool-a", "alias.user@example.com",
                Map.of("email", "alias.user@example.com"), null, null, false);
        assertEquals("d275a484-c0c1-70b3-46da-d9dafa5205e3", r.getPhysicalId());
    }

    @Test
    void aUserWithoutUsernameGetsAGeneratedName() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a"}"""), ctx(null));

        assertThat(r.getPhysicalId(), matchesPattern("my-stack-User-[a-z0-9]{12}"));
        verify(cognito).adminCreateUser("pool-a", r.getPhysicalId(), Map.of(), null, null, false);
    }

    @Test
    void missingUserPoolIdIsAValidationErrorAndCreatesNothing() throws Exception {
        StackResource r = resource(null);

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("""
                        {"Username": "user-one"}"""), ctx(null)));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertEquals("AWS::Cognito::UserPoolUser requires UserPoolId", thrown.getMessage());
        verifyNoInteractions(cognito);
    }

    @Test
    void resendForAnExistingUserFailsWithoutAdoptingIt() throws Exception {
        CognitoUser existing = new CognitoUser();
        existing.setUsername("alice");
        when(cognito.adminGetUser("pool-a", "alice")).thenReturn(existing);
        StackResource r = resource(null);

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "alice", "MessageAction": "RESEND"}"""), ctx(null)));

        assertEquals("AlreadyExists", thrown.getErrorCode());
        assertEquals("Resource of type 'AWS::Cognito::UserPoolUser' with identifier 'pool-a|alice' already exists.",
                thrown.getMessage());
        verify(cognito, never()).adminCreateUser(any(), any(), any(), any(), any(), anyBoolean());
        assertNull(r.getPhysicalId());
    }

    @Test
    void resendForAMissingUserFailsUserNotFound() throws Exception {
        when(cognito.adminGetUser("pool-a", "alice"))
                .thenThrow(new AwsException("UserNotFoundException", "User does not exist.", 400));
        StackResource r = resource(null);

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "alice", "MessageAction": "RESEND"}"""), ctx(null)));

        assertEquals("UserNotFoundException", thrown.getErrorCode());
        verify(cognito, never()).adminCreateUser(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void anUnchangedTemplateLeavesTheUserAlone() throws Exception {
        stubCreateEchoingTheUsername();
        String template = """
                {"UserPoolId": "pool-a", "Username": "user-one",
                 "UserAttributes": [{"Name": "email", "Value": "one@example.com"}]}""";
        StackResource r = resource(null);
        provisioner.provision(r, props(template), ctx(null));
        clearInvocations(cognito);

        provisioner.provision(r, props(template), ctx("user-one"));

        verifyNoInteractions(cognito);
        assertEquals("user-one", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));

        provisioner.delete(r, "us-east-1");
        verify(cognito).adminDeleteUser("pool-a", r.getPhysicalId());
    }

    @Test
    void changingACustomNamedUserIsRefusedWithoutTouchingIt() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one",
                 "UserAttributes": [{"Name": "email", "Value": "one@example.com"}]}"""), ctx(null));
        clearInvocations(cognito);

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one",
                 "UserAttributes": [{"Name": "email", "Value": "two@example.com"}]}"""), ctx("user-one")));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename user-one and update the stack again.", thrown.getMessage());
        verifyNoInteractions(cognito);
    }

    @Test
    void renamingTheUserReplacesItAndCleanupDeletesTheOldOne() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one"}"""), ctx(null));

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one-b"}"""), ctx("user-one"));

        verify(cognito).adminCreateUser("pool-a", "user-one-b", Map.of(), null, null, false);
        assertEquals("user-one-b", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("user-one", provisioner.updateCleanupPhysicalId(r));

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.complete());
        verify(cognito).adminDeleteUser("pool-a", "user-one");
        verify(cognito, never()).adminDeleteUser(any(), eq("user-one-b"));
    }

    @Test
    void changingAGeneratedNameUserReplacesItUnderANewGeneratedName() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "UserAttributes": [{"Name": "name", "Value": "One"}]}"""), ctx(null));
        String first = r.getPhysicalId();

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "UserAttributes": [{"Name": "name", "Value": "Two"}]}"""), ctx(first));

        assertNotEquals(first, r.getPhysicalId());
        assertThat(r.getPhysicalId(), matchesPattern("my-stack-User-[a-z0-9]{12}"));
        verify(cognito).adminCreateUser("pool-a", r.getPhysicalId(), Map.of("name", "Two"), null, null, false);
        assertTrue(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void cleanupDeletesTheDisplacedUserInItsOwnPool() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a"}"""), ctx(null));
        String first = r.getPhysicalId();

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-b"}"""), ctx(first));
        provisioner.completeUpdate(r);

        verify(cognito).adminDeleteUser("pool-a", first);
        verify(cognito, never()).adminDeleteUser(eq("pool-b"), any());

        provisioner.delete(r, "us-east-1");
        verify(cognito).adminDeleteUser("pool-b", r.getPhysicalId());
    }

    @Test
    void rollingBackAReplacementDeletesItAndRestoresThePriorUser() throws Exception {
        stubCreateEchoingTheUsername();
        String original = """
                {"UserPoolId": "pool-a", "Username": "user-one"}""";
        StackResource r = resource(null);
        provisioner.provision(r, props(original), ctx(null));
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-b", "Username": "user-one-b"}"""), ctx("user-one"));
        clearInvocations(cognito);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito).adminDeleteUser("pool-b", "user-one-b");
        verifyNoMoreInteractions(cognito);
        assertEquals("user-one", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));

        // The createOnly record is the prior one again, so re-applying the original template is a no-op.
        clearInvocations(cognito);
        provisioner.provision(r, props(original), ctx("user-one"));
        verifyNoInteractions(cognito);

        provisioner.delete(r, "us-east-1");
        verify(cognito).adminDeleteUser("pool-a", r.getPhysicalId());
    }

    @Test
    void anOlderUserStillOwedADeleteIsDeletedInItsOwnPool() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-a"}"""), ctx(null));
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-b", "Username": "user-b"}"""), ctx("user-a"));
        doThrow(new AwsException("InternalFailure", "storage unavailable", 500)).doNothing()
                .when(cognito).adminDeleteUser("pool-b", "user-b");
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-c", "Username": "user-c"}"""), ctx("user-a"));
        provisioner.completeUpdate(r);

        verify(cognito, times(2)).adminDeleteUser("pool-b", "user-b");
        verify(cognito, never()).adminDeleteUser("pool-c", "user-b");
        verify(cognito).adminDeleteUser("pool-a", "user-a");
    }

    @Test
    void poolRecordKeepsOnlyTheCurrentUserAndUsersStillOwedADelete() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "alice"}"""), ctx(null));
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-b", "Username": "bob"}"""), ctx("alice"));
        provisioner.completeUpdate(r);
        provisioner.clearUpdate(r);
        verify(cognito).adminDeleteUser("pool-a", "alice");

        provisioner.provision(r, props("""
                {"UserPoolId": "pool-c", "Username": "carol"}"""), ctx("bob"));

        JsonNode pools = mapper.readTree(r.getAttributes().get(POOLS_ATTR));
        Set<String> recorded = new HashSet<>();
        pools.fieldNames().forEachRemaining(recorded::add);
        assertEquals(Set.of("bob", "carol"), recorded, "a user deleted by a committed cleanup must leave the record");
        assertEquals("pool-b", pools.path("bob").asText());
        assertEquals("pool-c", pools.path("carol").asText());

        provisioner.completeUpdate(r);

        verify(cognito).adminDeleteUser("pool-b", "bob");
        verify(cognito, never()).adminDeleteUser(any(), eq("carol"));
    }

    @Test
    void reorderedPropertiesOfACustomNamedUserAreNotAChange() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one",
                 "UserAttributes": [{"Name": "email", "Value": "one@example.com"}]}"""), ctx(null));
        clearInvocations(cognito);

        provisioner.provision(r, props("""
                {"UserAttributes": [{"Value": "one@example.com", "Name": "email"}],
                 "Username": "user-one", "UserPoolId": "pool-a"}"""), ctx("user-one"));

        verifyNoInteractions(cognito);
        assertEquals("user-one", r.getPhysicalId());
    }

    @Test
    void aRollbackWhoseDeleteFailsStillRestoresThePriorCreateOnlyRecord() throws Exception {
        stubCreateEchoingTheUsername();
        String original = """
                {"UserPoolId": "pool-a", "Username": "user-one"}""";
        StackResource r = resource(null);
        provisioner.provision(r, props(original), ctx(null));
        String priorRecord = r.getAttributes().get(CREATE_ONLY_ATTR);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one-b"}"""), ctx("user-one"));
        doThrow(new AwsException("InternalFailure", "storage unavailable", 500))
                .when(cognito).adminDeleteUser("pool-a", "user-one-b");

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals(priorRecord, r.getAttributes().get(CREATE_ONLY_ATTR));
        clearInvocations(cognito);
        provisioner.provision(r, props(original), ctx("user-one"));
        verifyNoInteractions(cognito);
    }

    @Test
    void rollingBackWithoutAReplacementHasNothingToUndo() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one"}"""), ctx(null));
        clearInvocations(cognito);

        assertTrue(provisioner.rollbackUpdate(r));

        verifyNoInteractions(cognito);
        assertEquals("user-one", r.getPhysicalId());
    }

    @Test
    void deleteUsesTheUsersPoolAndToleratesAUserOrPoolAlreadyGone() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one"}"""), ctx(null));
        doThrow(new AwsException("UserNotFoundException", "User does not exist.", 400))
                .doThrow(new AwsException("ResourceNotFoundException", "User pool pool-a does not exist.", 400))
                .when(cognito).adminDeleteUser("pool-a", "user-one");

        provisioner.delete(r, "us-east-1");
        provisioner.delete(r, "us-east-1");

        verify(cognito, times(2)).adminDeleteUser("pool-a", "user-one");
    }

    @Test
    void deleteSurfacesAFailureThatIsNotAMissingUser() throws Exception {
        stubCreateEchoingTheUsername();
        StackResource r = resource(null);
        provisioner.provision(r, props("""
                {"UserPoolId": "pool-a", "Username": "user-one"}"""), ctx(null));
        doThrow(new AwsException("InternalFailure", "storage unavailable", 500))
                .when(cognito).adminDeleteUser("pool-a", "user-one");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));

        assertEquals("InternalFailure", thrown.getErrorCode());
    }

    private void stubCreateEchoingTheUsername() {
        when(cognito.adminCreateUser(anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    CognitoUser user = new CognitoUser();
                    user.setUsername(inv.getArgument(1));
                    return user;
                });
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.deepCopy();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("User");
        r.setResourceType(TYPE);
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
