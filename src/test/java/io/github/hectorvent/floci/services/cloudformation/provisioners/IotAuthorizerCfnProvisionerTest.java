package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotAuthorizerService;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IoT::Authorizer}: the name is the physical id and {@code Ref}, {@code Arn} the
 * authorizer ARN, an in-place update sends only the properties the template declares, a changed
 * name or {@code SigningDisabled} replaces the authorizer, and a delete deactivates it first.
 */
class IotAuthorizerCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String STACK = "my-stack";
    private static final String TYPE = "AWS::IoT::Authorizer";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:auth";
    private static final String OTHER_FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:auth-2";
    private static final String KEY = "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----";

    private final IotAuthorizerService service = mock(IotAuthorizerService.class);
    private final CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
    private final IotAuthorizerCfnProvisioner provisioner = new IotAuthorizerCfnProvisioner(service);
    /** What the mocked service holds, so describe and the tag calls see what earlier calls stored. */
    private final Map<String, IotAuthorizer> authorizers = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Like the real engine, AWS::NoValue resolves to an empty string.
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = resolveNode(i.getArgument(0));
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> resolveNode(i.getArgument(0)));
        when(service.createAuthorizer(anyString(), any(), eq(REGION))).thenAnswer(i -> {
            String name = i.getArgument(0);
            JsonNode body = i.getArgument(1);
            IotAuthorizer authorizer = new IotAuthorizer();
            authorizer.setAuthorizerName(name);
            authorizer.setAuthorizerArn(arn(name));
            authorizer.setAuthorizerFunctionArn(body.path("authorizerFunctionArn").asText());
            authorizer.setStatus(body.path("status").asText("INACTIVE"));
            authorizer.setSigningDisabled(body.path("signingDisabled").asBoolean(false));
            authorizer.setEnableCachingForHttp(body.path("enableCachingForHttp").asBoolean(false));
            authorizer.setTokenKeyName(body.path("tokenKeyName").asText(null));
            if (body.path("tokenSigningPublicKeys").isObject()) {
                authorizer.setTokenSigningPublicKeys(MAPPER.convertValue(body.get("tokenSigningPublicKeys"),
                        new TypeReference<Map<String, String>>() {}));
            }
            Map<String, String> tags = new TreeMap<>();
            body.path("tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
            authorizer.setTags(tags);
            authorizers.put(name, authorizer);
            return authorizer;
        });
        when(service.describeAuthorizer(anyString(), eq(REGION))).thenAnswer(i -> stored(i.getArgument(0)));
        when(service.updateAuthorizer(anyString(), any(), eq(REGION))).thenAnswer(i -> {
            IotAuthorizer authorizer = stored(i.getArgument(0));
            JsonNode body = i.getArgument(1);
            if (body.has("status")) {
                authorizer.setStatus(body.get("status").asText());
            }
            if (body.has("enableCachingForHttp")) {
                authorizer.setEnableCachingForHttp(body.get("enableCachingForHttp").asBoolean());
            }
            if (body.has("authorizerFunctionArn")) {
                authorizer.setAuthorizerFunctionArn(body.get("authorizerFunctionArn").asText());
            }
            return authorizer;
        });
        doAnswer(i -> {
            stored(nameOf(i.getArgument(0))).getTags().putAll(i.getArgument(1));
            return null;
        }).when(service).tagResource(anyString(), anyMap());
        doAnswer(i -> {
            stored(nameOf(i.getArgument(0))).getTags().keySet().removeAll(i.<List<String>>getArgument(1));
            return null;
        }).when(service).untagResource(anyString(), anyList());
    }

    @Test
    void servesOnlyTheAuthorizerType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createWithAnExplicitNameSendsTheDeclaredPropertiesAndSetsRefAndArn() {
        StackResource resource = create(props("auth")
                .put("SigningDisabled", true)
                .put("Status", "ACTIVE")
                .put("EnableCachingForHttp", true)
                .set("Tags", tags("team", "a")));

        verify(service).createAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "status": "ACTIVE", "signingDisabled": true,
                 "enableCachingForHttp": true, "tags": [{"Key": "team", "Value": "a"}]}
                """.formatted(FUNCTION)), REGION);
        assertEquals("auth", resource.getPhysicalId());
        assertEquals(arn("auth"), resource.getAttributes().get("Arn"));
        verify(service, never()).tagResource(anyString(), anyMap());
    }

    @Test
    void createWithSigningSendsTheTokenKeyNameAndKeys() {
        create(props("auth").put("TokenKeyName", "tok").set("TokenSigningPublicKeys", keys()));

        verify(service).createAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "tokenKeyName": "tok", "tokenSigningPublicKeys": {"k1": "%s"}}
                """.formatted(FUNCTION, KEY.replace("\n", "\\n"))), REGION);
    }

    @Test
    void aSigningKeyResolvedToNoValueIsNotSent() {
        ObjectNode keys = keys();
        keys.putObject("k2").putArray("Fn::If").add("HasSecondKey").add(KEY).add(noValue());

        create(props("auth").put("TokenKeyName", "tok").set("TokenSigningPublicKeys", keys));

        verify(service).createAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "tokenKeyName": "tok", "tokenSigningPublicKeys": {"k1": "%s"}}
                """.formatted(FUNCTION, KEY.replace("\n", "\\n"))), REGION);
    }

    @Test
    void aLiteralEmptySigningKeyStillReachesTheService() {
        create(props("auth").put("TokenKeyName", "tok").set("TokenSigningPublicKeys", keys().put("k2", "")));

        verify(service).createAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "tokenKeyName": "tok", "tokenSigningPublicKeys": {"k1": "%s", "k2": ""}}
                """.formatted(FUNCTION, KEY.replace("\n", "\\n"))), REGION);
    }

    @Test
    void omittedNameIsGeneratedWithinTheIotNameRule() {
        StackResource resource = resource();
        provisioner.provision(resource, props(null).put("SigningDisabled", true),
                new ProvisionContext(engine, REGION, ACCOUNT, "stack".repeat(30)));

        String name = resource.getPhysicalId();
        assertTrue(name.matches("Auth_[A-Za-z0-9]{12}"), name);
        verify(service).createAuthorizer(name, json("""
                {"authorizerFunctionArn": "%s", "signingDisabled": true}
                """.formatted(FUNCTION)), REGION);
        assertEquals(arn(name), resource.getAttributes().get("Arn"));
    }

    @Test
    void aLongLogicalIdIsCutSoTheGeneratedNameFitsTheIotLimit() {
        StackResource resource = resource();
        String logicalId = "Auth".repeat(50);
        resource.setLogicalId(logicalId);
        provisioner.provision(resource, props(null).put("SigningDisabled", true),
                new ProvisionContext(engine, REGION, ACCOUNT, STACK));

        String name = resource.getPhysicalId();
        assertTrue(name.length() <= 128, name);
        assertTrue(name.matches("[A-Za-z0-9]+_[A-Za-z0-9]{12}"), name);
        assertTrue(logicalId.startsWith(name.substring(0, name.length() - 13)), name);
    }

    @Test
    void aWhitespaceNameFromAnIntrinsicIsKeptInsteadOfGenerated() {
        ObjectNode properties = props(null);
        properties.putObject("AuthorizerName").putArray("Fn::If").add("HasCustomName").add("auth").add(" ");

        StackResource resource = create(properties);

        assertEquals(" ", resource.getPhysicalId());
        verify(service).createAuthorizer(" ", json("""
                {"authorizerFunctionArn": "%s"}
                """.formatted(FUNCTION)), REGION);
    }

    @Test
    void missingFunctionArnIsRefusedBeforeAnyCall() {
        ObjectNode properties = MAPPER.createObjectNode().put("AuthorizerName", "auth").put("SigningDisabled", true);

        AwsException error = assertThrows(AwsException.class, () -> create(properties));

        assertEquals("Model validation failed (#: required key [AuthorizerFunctionArn] not found)",
                error.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void inPlaceUpdateSendsOnlyTheDeclaredProperties() {
        StackResource resource = create(props("auth").put("SigningDisabled", true)
                .put("Status", "ACTIVE").put("EnableCachingForHttp", true));
        clearInvocations(service);

        update(resource, props("auth").put("SigningDisabled", true).put("Status", "INACTIVE"));

        verify(service).updateAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "status": "INACTIVE"}
                """.formatted(FUNCTION)), REGION);
        verify(service, never()).createAuthorizer(anyString(), any(), anyString());
        assertEquals("auth", resource.getPhysicalId());
        assertEquals(arn("auth"), resource.getAttributes().get("Arn"));
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void propertiesRemovedFromTheTemplateAreNotSent() {
        StackResource resource = create(props("auth").put("SigningDisabled", true)
                .put("Status", "ACTIVE").put("EnableCachingForHttp", true));
        clearInvocations(service);
        ObjectNode properties = props("auth").put("SigningDisabled", true);
        properties.set("Status", noValue());

        update(resource, properties);

        verify(service).updateAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s"}
                """.formatted(FUNCTION)), REGION);
    }

    @Test
    void aWhitespaceCachingFlagFromAnIntrinsicIsRefusedRatherThanReadAsFalse() {
        StackResource resource = create(props("auth").put("SigningDisabled", true).put("EnableCachingForHttp", true));
        clearInvocations(service);
        ObjectNode properties = props("auth").put("SigningDisabled", true);
        properties.putObject("EnableCachingForHttp").putArray("Fn::If").add("Cache").add(true).add(" ");

        AwsException error = assertThrows(AwsException.class, () -> update(resource, properties));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("AWS::IoT::Authorizer EnableCachingForHttp must be true or false, got  ", error.getMessage());
        verify(service, never()).updateAuthorizer(anyString(), any(), anyString());
        assertTrue(authorizers.get("auth").isEnableCachingForHttp());
    }

    @Test
    void inPlaceUpdateResendsTheDeclaredTokenProperties() {
        StackResource resource = create(props("auth").put("TokenKeyName", "tok").set("TokenSigningPublicKeys", keys()));
        clearInvocations(service);

        update(resource, props("auth").put("TokenKeyName", "tok").put("Status", "ACTIVE")
                .set("TokenSigningPublicKeys", keys()));

        verify(service).updateAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "status": "ACTIVE", "tokenKeyName": "tok",
                 "tokenSigningPublicKeys": {"k1": "%s"}}
                """.formatted(FUNCTION, KEY.replace("\n", "\\n"))), REGION);
    }

    @Test
    void inPlaceUpdateReconcilesTheTags() {
        ArrayNode created = tags("team", "a");
        created.addObject().put("Key", "stage").put("Value", "x");
        StackResource resource = create(props("auth").put("SigningDisabled", true).set("Tags", created));
        clearInvocations(service);
        ArrayNode desired = tags("team", "b");
        desired.addObject().put("Key", "env").put("Value", "dev");

        update(resource, props("auth").put("SigningDisabled", true).set("Tags", desired));

        verify(service).untagResource(arn("auth"), List.of("stage"));
        verify(service).tagResource(arn("auth"), Map.of("team", "b", "env", "dev"));
        assertEquals(Map.of("team", "b", "env", "dev"), authorizers.get("auth").getTags());
    }

    @Test
    void rollbackUpdateRestoresTheSnapshot() {
        StackResource resource = create(props("auth").put("SigningDisabled", true)
                .put("Status", "ACTIVE").set("Tags", tags("team", "a")));
        update(resource, props("auth").put("SigningDisabled", true).put("Status", "INACTIVE")
                .put("EnableCachingForHttp", true).put("AuthorizerFunctionArn", OTHER_FUNCTION)
                .set("Tags", tags("env", "dev")));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR));
        clearInvocations(service);

        assertTrue(provisioner.rollbackUpdate(resource));

        verify(service).updateAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "status": "ACTIVE", "enableCachingForHttp": false}
                """.formatted(FUNCTION)), REGION);
        IotAuthorizer restored = authorizers.get("auth");
        assertEquals("ACTIVE", restored.getStatus());
        assertFalse(restored.isEnableCachingForHttp());
        assertEquals(FUNCTION, restored.getAuthorizerFunctionArn());
        assertEquals(Map.of("team", "a"), restored.getTags());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackUpdateWithoutASnapshotChangesNothing() {
        StackResource resource = create(props("auth").put("SigningDisabled", true));
        clearInvocations(service);

        assertTrue(provisioner.rollbackUpdate(resource));

        verifyNoInteractions(service);
    }

    @Test
    void clearUpdateDropsTheSnapshot() {
        StackResource resource = create(props("auth").put("SigningDisabled", true));
        update(resource, props("auth").put("SigningDisabled", true).put("Status", "ACTIVE"));

        provisioner.clearUpdate(resource);

        assertFalse(resource.getAttributes().containsKey(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR));
    }

    /**
     * The stack puts back the resource it held before the attempt, which never carried the
     * snapshot, so an update that failed on its tags restores the authorizer itself.
     */
    @Test
    void anInPlaceUpdateThatFailedOnItsTagsRestoresTheAuthorizer() {
        StackResource resource = create(props("auth").put("SigningDisabled", true).put("Status", "ACTIVE"));
        AwsException failure = new AwsException("InternalFailureException", "tags down", 500);
        doThrow(failure).when(service).tagResource(anyString(), anyMap());

        AwsException thrown = assertThrows(AwsException.class, () -> update(resource, props("auth")
                .put("SigningDisabled", true).put("Status", "INACTIVE").set("Tags", tags("team", "a"))));

        assertSame(failure, thrown);
        verify(service).updateAuthorizer("auth", json("""
                {"authorizerFunctionArn": "%s", "status": "ACTIVE", "enableCachingForHttp": false}
                """.formatted(FUNCTION)), REGION);
        assertEquals("ACTIVE", authorizers.get("auth").getStatus());
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /**
     * A restore that fails too is recorded as a rollback failure and keeps the snapshot, so the
     * stack ends in UPDATE_ROLLBACK_FAILED while still reporting the update's own failure.
     */
    @Test
    void anUnwindThatCannotRestoreIsReportedAsARollbackFailure() {
        StackResource resource = create(props("auth").put("SigningDisabled", true).put("Status", "ACTIVE"));
        AwsException failure = new AwsException("InternalFailureException", "tags down", 500);
        doThrow(failure).when(service).tagResource(anyString(), anyMap());
        AwsException restoreFailure = new AwsException("InternalFailureException", "still down", 500);
        doThrow(restoreFailure).when(service).updateAuthorizer(eq("auth"), eq(json("""
                {"authorizerFunctionArn": "%s", "status": "ACTIVE", "enableCachingForHttp": false}
                """.formatted(FUNCTION))), eq(REGION));

        AwsException thrown = assertThrows(AwsException.class, () -> update(resource, props("auth")
                .put("SigningDisabled", true).put("Status", "INACTIVE").set("Tags", tags("team", "a"))));

        assertSame(failure, thrown);
        assertEquals(List.of(restoreFailure), List.of(thrown.getSuppressed()));
        assertEquals("Could not roll back the update of authorizer auth: still down",
                resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
    }

    @Test
    void renamingTheAuthorizerReplacesItAndDeletesTheOldOneOnCommit() {
        StackResource resource = create(props("auth").put("SigningDisabled", true).put("Status", "ACTIVE"));
        clearInvocations(service);

        update(resource, props("auth-2").put("SigningDisabled", true));

        assertEquals("auth-2", resource.getPhysicalId());
        assertEquals(arn("auth-2"), resource.getAttributes().get("Arn"));
        verify(service).createAuthorizer("auth-2", json("""
                {"authorizerFunctionArn": "%s", "signingDisabled": true}
                """.formatted(FUNCTION)), REGION);
        verify(service, never()).updateAuthorizer(anyString(), any(), anyString());
        verify(service, never()).deleteAuthorizer(anyString(), anyString());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals("auth", provisioner.updateCleanupPhysicalId(resource));

        commit(resource);

        InOrder order = inOrder(service);
        order.verify(service).updateAuthorizer("auth", json("{\"status\": \"INACTIVE\"}"), REGION);
        order.verify(service).deleteAuthorizer("auth", REGION);
        verify(service, never()).deleteAuthorizer("auth-2", REGION);
    }

    @Test
    void changingSigningDisabledReplacesAGeneratedAuthorizer() {
        StackResource resource = create(props(null).put("SigningDisabled", true).put("Status", "ACTIVE"));
        String original = resource.getPhysicalId();
        clearInvocations(service);

        update(resource, props(null).put("SigningDisabled", false).put("TokenKeyName", "tok")
                .set("TokenSigningPublicKeys", keys()));

        String replacement = resource.getPhysicalId();
        assertNotEquals(original, replacement);
        assertTrue(replacement.matches("Auth_[A-Za-z0-9]{12}"), replacement);
        verify(service).createAuthorizer(replacement, json("""
                {"authorizerFunctionArn": "%s", "signingDisabled": false, "tokenKeyName": "tok",
                 "tokenSigningPublicKeys": {"k1": "%s"}}
                """.formatted(FUNCTION, KEY.replace("\n", "\\n"))), REGION);
        assertEquals(original, provisioner.updateCleanupPhysicalId(resource));

        commit(resource);

        verify(service).deleteAuthorizer(original, REGION);
    }

    @Test
    void anUnchangedGeneratedNameIsKept() {
        StackResource resource = create(props(null).put("SigningDisabled", true));
        String generated = resource.getPhysicalId();
        clearInvocations(service);

        update(resource, props(null).put("SigningDisabled", true).put("Status", "ACTIVE"));

        assertEquals(generated, resource.getPhysicalId());
        verify(service, never()).createAuthorizer(anyString(), any(), anyString());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void droppingTheExplicitNameReplacesTheAuthorizer() {
        StackResource resource = create(props("auth").put("SigningDisabled", true));
        clearInvocations(service);

        update(resource, props(null).put("SigningDisabled", true));

        String generated = resource.getPhysicalId();
        assertTrue(generated.matches("Auth_[A-Za-z0-9]{12}"), generated);
        verify(service).createAuthorizer(eq(generated), any(), eq(REGION));
        assertEquals("auth", provisioner.updateCleanupPhysicalId(resource));
    }

    @Test
    void replacingAnExplicitlyNamedAuthorizerIsRefusedBeforeAnyChange() {
        StackResource resource = create(props("auth").put("SigningDisabled", true));
        clearInvocations(service);

        AwsException error = assertThrows(AwsException.class, () -> update(resource,
                props("auth").put("SigningDisabled", false).put("TokenKeyName", "tok")
                        .set("TokenSigningPublicKeys", keys())));

        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename auth and update the stack again.", error.getMessage());
        verify(service, never()).createAuthorizer(anyString(), any(), anyString());
        verify(service, never()).updateAuthorizer(anyString(), any(), anyString());
        verify(service, never()).deleteAuthorizer(anyString(), anyString());
        assertEquals("auth", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    /** Adding the generated name explicitly replaces the authorizer under the same name, which is refused. */
    @Test
    void declaringTheGeneratedNameExplicitlyIsRefusedBeforeAnyChange() {
        StackResource resource = create(props(null).put("SigningDisabled", true));
        String generated = resource.getPhysicalId();
        clearInvocations(service);

        AwsException error = assertThrows(AwsException.class,
                () -> update(resource, props(generated).put("SigningDisabled", true)));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename " + generated + " and update the stack again.", error.getMessage());
        verify(service, never()).createAuthorizer(anyString(), any(), anyString());
        verify(service, never()).updateAuthorizer(anyString(), any(), anyString());
    }

    /** A stack from before this provisioner holds the dispatcher's stub, which names no authorizer. */
    @Test
    void updatingADispatcherStubCreatesTheAuthorizerUnderAGeneratedName() {
        StackResource resource = resource();
        resource.setPhysicalId("Auth-1a2b3c4d");
        resource.getAttributes().put("Arn", "arn:aws:stub:::Auth");

        update(resource, props(null).put("SigningDisabled", true));

        String generated = resource.getPhysicalId();
        assertTrue(generated.matches("Auth_[A-Za-z0-9]{12}"), generated);
        verify(service).createAuthorizer(eq(generated), any(), eq(REGION));
        verify(service, never()).describeAuthorizer(anyString(), anyString());
        verify(service, never()).updateAuthorizer(anyString(), any(), anyString());
        assertEquals(arn(generated), resource.getAttributes().get("Arn"));
        assertNull(provisioner.updateCleanupPhysicalId(resource), "a stub created nothing, so no delete is owed for its id");
    }

    @Test
    void updatingADispatcherStubWithAnExplicitNameCreatesThatAuthorizer() {
        StackResource resource = resource();
        resource.setPhysicalId("Auth-1a2b3c4d");
        resource.getAttributes().put("Arn", "arn:aws:stub:::Auth");

        update(resource, props("auth").put("SigningDisabled", true));

        assertEquals("auth", resource.getPhysicalId());
        verify(service).createAuthorizer(eq("auth"), any(), eq(REGION));
        verify(service, never()).describeAuthorizer(anyString(), anyString());
    }

    @Test
    void aFailedStackUpdateDeletesTheReplacementAndRestoresThePriorAuthorizer() {
        StackResource resource = create(props("auth").put("SigningDisabled", true));
        update(resource, props("auth-2").put("SigningDisabled", true));
        clearInvocations(service);

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("auth", resource.getPhysicalId());
        assertEquals(arn("auth"), resource.getAttributes().get("Arn"));
        verify(service).deleteAuthorizer("auth-2", REGION);
        verify(service, never()).deleteAuthorizer("auth", REGION);
    }

    /** A stub migrated under the stub's own id created that authorizer, so a failed stack update deletes it. */
    @Test
    void aFailedStackUpdateDeletesTheAuthorizerAStubMigrationCreatedUnderTheStubId() {
        StackResource resource = resource();
        resource.setPhysicalId("Auth-1a2b3c4d");
        resource.getAttributes().put("Arn", "arn:aws:stub:::Auth");
        update(resource, props("Auth-1a2b3c4d").put("SigningDisabled", true));
        verify(service).createAuthorizer(eq("Auth-1a2b3c4d"), any(), eq(REGION));
        clearInvocations(service);

        assertTrue(provisioner.rollbackUpdate(resource));

        InOrder order = inOrder(service);
        order.verify(service).updateAuthorizer("Auth-1a2b3c4d", json("{\"status\": \"INACTIVE\"}"), REGION);
        order.verify(service).deleteAuthorizer("Auth-1a2b3c4d", REGION);
        assertEquals("Auth-1a2b3c4d", resource.getPhysicalId());
        assertEquals(Map.of("Arn", "arn:aws:stub:::Auth"), resource.getAttributes());
    }

    @Test
    void deleteDeactivatesThenDeletes() {
        authorizers.put("auth", new IotAuthorizer());

        provisioner.delete(TYPE, "auth", REGION);

        InOrder order = inOrder(service);
        order.verify(service).updateAuthorizer("auth", json("{\"status\": \"INACTIVE\"}"), REGION);
        order.verify(service).deleteAuthorizer("auth", REGION);
    }

    @Test
    void deleteToleratesAnAuthorizerThatIsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "Authorizer auth not found", 404))
                .when(service).updateAuthorizer(eq("auth"), any(), eq(REGION));

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "auth", REGION));
    }

    @Test
    void deletePropagatesOtherFailures() {
        authorizers.put("auth", new IotAuthorizer());
        AwsException conflict = new AwsException("DeleteConflictException",
                "Cannot delete default authorizer auth. Change default and retry delete.", 409);
        doThrow(conflict).when(service).deleteAuthorizer("auth", REGION);

        assertSame(conflict, assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "auth", REGION)));
    }

    private StackResource create(ObjectNode properties) {
        StackResource resource = resource();
        provisioner.provision(resource, properties, new ProvisionContext(engine, REGION, ACCOUNT, STACK));
        return resource;
    }

    private void update(StackResource resource, ObjectNode properties) {
        provisioner.provision(resource, properties,
                new ProvisionContext(engine, REGION, ACCOUNT, STACK, resource.getPhysicalId()));
    }

    private void commit(StackResource resource) {
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
    }

    private IotAuthorizer stored(String name) {
        IotAuthorizer authorizer = authorizers.get(name);
        if (authorizer == null) {
            throw new AwsException("ResourceNotFoundException", "Authorizer " + name + " not found", 404);
        }
        return authorizer;
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Auth");
        resource.setResourceType(TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static ObjectNode props(String name) {
        ObjectNode properties = MAPPER.createObjectNode();
        if (name != null) {
            properties.put("AuthorizerName", name);
        }
        return properties.put("AuthorizerFunctionArn", FUNCTION);
    }

    private static ArrayNode tags(String key, String value) {
        ArrayNode tags = MAPPER.createArrayNode();
        tags.addObject().put("Key", key).put("Value", value);
        return tags;
    }

    private static ObjectNode keys() {
        return MAPPER.createObjectNode().put("k1", KEY);
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonNode noValue() {
        return MAPPER.createObjectNode().put("Ref", "AWS::NoValue");
    }

    /** Like the real engine: AWS::NoValue becomes "", an object is walked; conditions here are all false. */
    private static JsonNode resolveNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        if (isNoValue(node)) {
            return TextNode.valueOf("");
        }
        if (node.has("Fn::If")) {
            return resolveNode(node.get("Fn::If").get(2));
        }
        ObjectNode resolved = MAPPER.createObjectNode();
        node.properties().forEach(field -> resolved.set(field.getKey(), resolveNode(field.getValue())));
        return resolved;
    }

    private static boolean isNoValue(JsonNode node) {
        return node.isObject() && "AWS::NoValue".equals(node.path("Ref").asText());
    }

    private static String nameOf(String arn) {
        return arn.substring(arn.indexOf("authorizer/") + "authorizer/".length());
    }

    private static String arn(String name) {
        return "arn:aws:iot:us-east-1:000000000000:authorizer/" + name;
    }
}
