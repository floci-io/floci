package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code AWS::EC2::KeyPair} in isolation: the name backs {@code Ref} and the key's id and fingerprint
 * back {@code Fn::GetAtt}; a created key keeps its private key in the {@code SecureString} parameter
 * {@code /ec2/keypair/<KeyPairId>} and an imported one writes none; every property is create-only, so
 * an update either changes nothing, renames the key by replacing it, or is refused; and each way a key
 * leaves the stack takes its parameter with it.
 */
class Ec2KeyPairCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String CUSTOM_NAMED_REPLACEMENT = "CloudFormation cannot update a stack when a "
            + "custom-named resource requires replacing. Rename deploy and update the stack again.";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final SsmService ssm = mock(SsmService.class);
    private final Ec2KeyPairCfnProvisioner provisioner = new Ec2KeyPairCfnProvisioner(ec2, ssm);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aCreatedKeyBacksRefAndGetAttAndKeepsItsPrivateKeyInASecureStringParameter() throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));

        StackResource r = resource();
        provisioner.provision(r, props("{\"KeyName\": \"deploy\"}"), ctx(null));

        assertEquals("deploy", r.getPhysicalId());
        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
        assertEquals("fingerprint-key-1", r.getAttributes().get("KeyFingerprint"));
        verify(ssm).putParameter("/ec2/keypair/key-1", "private-key-1", "SecureString", null, false, REGION);
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertFalse(r.getAttributes().containsValue("private-key-1"),
                "the private key lives in the parameter only, never on the stack resource");
    }

    @ParameterizedTest
    @CsvSource({"ed25519,", ",ppk", "rsa,ppk", "ed25519,ppk", "rsa,pem"})
    void keyTypeAndKeyFormatReachCreateKeyPair(String keyType, String keyFormat) throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", keyType, keyFormat)).thenReturn(created("deploy", "key-1"));
        StringBuilder json = new StringBuilder("{\"KeyName\": \"deploy\"");
        if (keyType != null) {
            json.append(", \"KeyType\": \"").append(keyType).append('"');
        }
        if (keyFormat != null) {
            json.append(", \"KeyFormat\": \"").append(keyFormat).append('"');
        }

        provisioner.provision(resource(), props(json.append('}').toString()), ctx(null));

        verify(ec2).createKeyPair(REGION, "deploy", keyType, keyFormat);
        verify(ssm).putParameter("/ec2/keypair/key-1", "private-key-1", "SecureString", null, false, REGION);
    }

    @Test
    void anImportedKeyIgnoresKeyTypeAndKeyFormatAndWritesNoParameter() throws Exception {
        when(ec2.importKeyPair(REGION, "deploy", "ssh-ed25519 AAAA")).thenReturn(imported("deploy", "key-2"));

        StackResource r = resource();
        provisioner.provision(r, props("{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"ssh-ed25519 AAAA\","
                + " \"KeyType\": \"rsa\", \"KeyFormat\": \"ppk\"}"), ctx(null));

        assertEquals("deploy", r.getPhysicalId());
        assertEquals("key-2", r.getAttributes().get("KeyPairId"));
        assertEquals("fingerprint-key-2", r.getAttributes().get("KeyFingerprint"));
        verify(ec2, never()).createKeyPair(anyString(), anyString(), any(), any());
        verifyNoInteractions(ssm);
    }

    /** {@code AWS::NoValue} leaves the property out. */
    @Test
    void noValueLeavesAPropertyOut() throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));

        provisioner.provision(resource(), props("{\"KeyName\": \"deploy\","
                + " \"PublicKeyMaterial\": {\"Ref\": \"AWS::NoValue\"}, \"KeyType\": {\"Ref\": \"AWS::NoValue\"}}"),
                ctx(null));

        verify(ec2).createKeyPair(REGION, "deploy", null, null);
        verify(ec2, never()).importKeyPair(anyString(), anyString(), anyString());
    }

    /** An empty string a parameter supplies is a value, as the same string written literally is. */
    @Test
    void aBlankValueFromAParameterIsNotLeftOut() throws Exception {
        AwsException refused = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                props("{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": {\"Ref\": \"Blank\"}}"), ctx(null)));
        assertEquals("MissingParameter", refused.getErrorCode());
        assertEquals("The request must contain the parameter PublicKeyMaterial", refused.getMessage());
        AwsException badType = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                props("{\"KeyName\": \"deploy\", \"KeyType\": {\"Ref\": \"Blank\"}}"), ctx(null)));
        assertEquals("ValidationError", badType.getErrorCode());

        verifyNoInteractions(ec2, ssm);
    }

    /** Only an empty material is missing; whitespace is material, which ImportKeyPair judges itself. */
    @Test
    void whitespaceMaterialIsLeftToImportKeyPair() throws Exception {
        when(ec2.importKeyPair(REGION, "deploy", "   "))
                .thenThrow(new AwsException("InvalidKey.Format", "Key is not in valid OpenSSH public key format", 400));

        AwsException refused = assertThrows(AwsException.class, () -> provisioner.provision(resource(),
                props("{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"   \"}"), ctx(null)));

        assertEquals("InvalidKey.Format", refused.getErrorCode());
        verifyNoInteractions(ssm);
    }

    @Test
    void tagsAreWrittenToTheNewKeyBeforeItsParameter() throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));

        provisioner.provision(resource(), props("{\"KeyName\": \"deploy\","
                + " \"Tags\": [{\"Key\": \"team\", \"Value\": \"infra\"}]}"), ctx(null));

        InOrder order = inOrder(ec2, ssm);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        order.verify(ec2).createTags(eq(REGION), eq(List.of("key-1")), tags.capture());
        order.verify(ssm).putParameter("/ec2/keypair/key-1", "private-key-1", "SecureString", null, false, REGION);
        assertEquals(Map.of("team", "infra"),
                tags.getValue().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"KeyName\": \"\"}",
        "{\"KeyName\": \"deploy\", \"KeyType\": \"dsa\"}",
        "{\"KeyName\": \"deploy\", \"KeyFormat\": \"der\"}",
        "{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"ssh-ed25519 AAAA\", \"KeyType\": \"dsa\"}",
        "{\"KeyName\": \"deploy\", \"Tags\": [{\"Key\": \"aws:reserved\", \"Value\": \"x\"}]}",
        "{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"\"}"
    })
    void invalidPropertiesFailBeforeAnythingIsCreated(String json) {
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props(json), ctx(null)));

        assertTrue(Set.of("ValidationError", "InvalidParameterValue", "MissingParameter")
                .contains(failure.getErrorCode()), failure.getErrorCode());
        verifyNoInteractions(ec2, ssm);
    }

    @Test
    void aFailedParameterWriteDeletesTheNewKeyAndReportsTheWriteFailure() {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));
        AwsException writeFailure = new AwsException("InternalServerError", "simulated write failure", 500);
        when(ssm.putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenThrow(writeFailure);

        StackResource r = resource();
        assertSame(writeFailure, assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"KeyName\": \"deploy\"}"), ctx(null))));

        verify(ec2).deleteKeyPair(REGION, null, "key-1");
        verify(ssm, never()).deleteParameter(anyString(), anyString());
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void aFailedTagWriteDeletesTheNewKeyBeforeAnyParameterIsWritten() {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));
        doThrow(new AwsException("TagLimitExceeded", "simulated tag failure", 400))
                .when(ec2).createTags(anyString(), anyList(), anyList());

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("{\"KeyName\": \"deploy\","
                + " \"Tags\": [{\"Key\": \"team\", \"Value\": \"infra\"}]}"), ctx(null)));

        verify(ec2).deleteKeyPair(REGION, null, "key-1");
        verifyNoInteractions(ssm);
    }

    /** The engine's create rollback deletes a key the provision's own undo could not. */
    @Test
    void aKeyTheUndoCouldNotDeleteStaysOwnedForTheStackRollback() throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));
        AwsException tagFailure = new AwsException("TagLimitExceeded", "simulated tag failure", 400);
        doThrow(tagFailure).when(ec2).createTags(anyString(), anyList(), anyList());
        when(ec2.deleteKeyPair(REGION, null, "key-1"))
                .thenThrow(new AwsException("InternalServerError", "simulated delete failure", 500))
                .thenReturn(null);

        StackResource r = resource();
        assertSame(tagFailure, assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"KeyName\": \"deploy\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"infra\"}]}"),
                ctx(null))));
        assertEquals(1, tagFailure.getSuppressed().length);
        assertEquals("deploy", r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));

        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(created("deploy", "key-1")));
        provisioner.delete(r, REGION);

        verify(ec2, times(2)).deleteKeyPair(REGION, null, "key-1");
    }

    @Test
    void anUpdateThatChangesNoPropertyKeepsTheKey() throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", "ed25519", null)).thenReturn(created("deploy", "key-1"));
        String template = "{\"KeyName\": \"deploy\", \"KeyType\": \"ed25519\","
                + " \"Tags\": [{\"Key\": \"a\", \"Value\": \"1\"}, {\"Key\": \"b\", \"Value\": \"2\"}]}";
        StackResource r = resource();
        provisioner.provision(r, props(template), ctx(null));

        // Tags are a set: the same tags in another order are no change.
        provisioner.provision(r, props("{\"KeyName\": \"deploy\", \"KeyType\": \"ed25519\","
                + " \"Tags\": [{\"Key\": \"b\", \"Value\": \"2\"}, {\"Key\": \"a\", \"Value\": \"1\"}]}"),
                ctx("deploy"));

        assertEquals("deploy", r.getPhysicalId());
        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
        verify(ec2, times(1)).createKeyPair(anyString(), anyString(), any(), any());
        verify(ssm, times(1)).putParameter(anyString(), anyString(), anyString(), any(), anyBoolean(), anyString());
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertTrue(provisioner.rollbackUpdate(r), "an update that changed nothing has nothing to undo");
        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
    }

    /** A property the inputs record lacks counts as absent, so the template that leaves it out matches. */
    @Test
    void aRecordWithoutAPropertyStillMatchesATemplateThatLeavesItOut() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("deploy");
        r.getAttributes().put("KeyPairId", "key-1");
        r.getAttributes().put("__FlociKeyPairInputs", "{\"KeyName\":\"deploy\",\"KeyType\":null}");

        provisioner.provision(r, props("{\"KeyName\": \"deploy\"}"), ctx("deploy"));

        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
        verifyNoInteractions(ec2, ssm);
    }

    /** A property the inputs record lacks is absent, so a template that now sets it changes the key. */
    @Test
    void aRecordWithoutAPropertyDoesNotMatchATemplateThatSetsIt() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("deploy");
        r.getAttributes().put("KeyPairId", "key-1");
        r.getAttributes().put("__FlociKeyPairInputs", "{\"KeyName\":\"deploy\",\"KeyType\":null}");

        AwsException refused = assertThrows(AwsException.class, () -> provisioner.provision(r,
                props("{\"KeyName\": \"deploy\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"infra\"}]}"),
                ctx("deploy")));

        assertEquals(CUSTOM_NAMED_REPLACEMENT, refused.getMessage());
        verifyNoInteractions(ec2, ssm);
    }

    /** Dropping a property is a change too: the key made with it is not the key the template asks for. */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"KeyName\": \"deploy\", \"KeyType\": \"ed25519\"}",
        "{\"KeyName\": \"deploy\", \"KeyFormat\": \"ppk\"}",
        "{\"KeyName\": \"deploy\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"infra\"}]}"
    })
    void droppingAPropertyUnderTheSameNameIsRefusedToo(String json) throws Exception {
        when(ec2.createKeyPair(eq(REGION), eq("deploy"), any(), any())).thenReturn(created("deploy", "key-1"));
        StackResource r = resource();
        provisioner.provision(r, props(json), ctx(null));

        AwsException refused = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"KeyName\": \"deploy\"}"), ctx("deploy")));

        assertEquals(CUSTOM_NAMED_REPLACEMENT, refused.getMessage());
        verify(ec2, times(1)).createKeyPair(anyString(), anyString(), any(), any());
        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"KeyName\": \"deploy\", \"KeyType\": \"ed25519\"}",
        "{\"KeyName\": \"deploy\", \"KeyType\": \"rsa\"}",
        "{\"KeyName\": \"deploy\", \"KeyFormat\": \"ppk\"}",
        "{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"ssh-ed25519 AAAA\"}",
        "{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"\"}",
        "{\"KeyName\": \"deploy\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"infra\"}]}"
    })
    void changingAnotherPropertyUnderTheSameNameIsRefusedAsCloudFormationRefusesIt(String json) throws Exception {
        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));
        StackResource r = resource();
        provisioner.provision(r, props("{\"KeyName\": \"deploy\"}"), ctx(null));

        AwsException refused = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props(json), ctx("deploy")));

        assertEquals("ValidationError", refused.getErrorCode());
        assertEquals(CUSTOM_NAMED_REPLACEMENT, refused.getMessage());
        verify(ec2, times(1)).createKeyPair(anyString(), anyString(), any(), any());
        verify(ec2, never()).importKeyPair(anyString(), anyString(), anyString());
        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
    }

    @Test
    void aRenameCreatesTheNewKeyAndTheCommittedUpdateDeletesTheOldOneWithItsParameter() throws Exception {
        StackResource r = renamed();

        assertEquals("next", r.getPhysicalId());
        assertEquals("key-2", r.getAttributes().get("KeyPairId"));
        assertEquals("fingerprint-key-2", r.getAttributes().get("KeyFingerprint"));
        verify(ssm).putParameter("/ec2/keypair/key-2", "private-key-2", "SecureString", null, false, REGION);
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("first", provisioner.updateCleanupPhysicalId(r));

        when(ec2.describeKeyPairs(REGION, List.of("first"), List.of())).thenReturn(List.of(created("first", "key-1")));
        UpdateCleanupResult result = provisioner.completeUpdate(r);
        provisioner.clearUpdate(r);

        assertTrue(result.complete(), String.valueOf(result));
        verify(ssm).deleteParameter("/ec2/keypair/key-1", REGION);
        verify(ec2).deleteKeyPair(REGION, null, "key-1");
        verify(ec2, never()).deleteKeyPair(REGION, null, "key-2");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aRolledBackRenameNamesTheOriginalKeyAgainAndDeletesTheReplacement() throws Exception {
        StackResource r = renamed();
        when(ec2.describeKeyPairs(REGION, List.of("next"), List.of())).thenReturn(List.of(created("next", "key-2")));

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals("first", r.getPhysicalId());
        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
        assertEquals("fingerprint-key-1", r.getAttributes().get("KeyFingerprint"));
        verify(ssm).deleteParameter("/ec2/keypair/key-2", REGION);
        verify(ec2).deleteKeyPair(REGION, null, "key-2");
        verify(ec2, never()).deleteKeyPair(REGION, null, "key-1");
        assertFalse(provisioner.hasReplacementUpdate(r));

        // The original template is again no change to the key it names.
        provisioner.provision(r, props("{\"KeyName\": \"first\"}"), ctx("first"));
        verify(ec2, times(1)).createKeyPair(REGION, "first", null, null);
        assertEquals("key-1", r.getAttributes().get("KeyPairId"));
    }

    /** A rollback whose delete of the replacement fails is finished by ContinueUpdateRollback. */
    @Test
    void aReplacementTheRollbackCouldNotDeleteIsDeletedByTheRetry() throws Exception {
        StackResource r = renamed();
        when(ec2.describeKeyPairs(REGION, List.of("next"), List.of())).thenReturn(List.of(created("next", "key-2")));
        when(ec2.deleteKeyPair(REGION, null, "key-2"))
                .thenThrow(new AwsException("InternalServerError", "simulated delete failure", 500))
                .thenReturn(null);

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals("first", r.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2, times(2)).deleteKeyPair(REGION, null, "key-2");
        assertFalse(provisioner.hasReplacementUpdate(r));
        provisioner.provision(r, props("{\"KeyName\": \"first\"}"), ctx("first"));
        verify(ec2, times(1)).createKeyPair(REGION, "first", null, null);
    }

    /**
     * Pins the {@link ReplacementCleanup#endRollbackRetry} contract: a rollback of a later update
     * that failed before creating anything does not retry the delete an earlier rollback left, and
     * the next committed update still deletes that replacement. The engine does not reach this today,
     * since it restores the previous resource when a key pair update fails instead of rolling the
     * attempt back; it would once the provisioner kept its failed update state.
     */
    @Test
    void aLaterFailedUpdateDoesNotRetryTheDeleteAnEarlierRollbackLeft() throws Exception {
        StackResource r = renamed();
        when(ec2.describeKeyPairs(REGION, List.of("next"), List.of())).thenReturn(List.of(created("next", "key-2")));
        when(ec2.deleteKeyPair(REGION, null, "key-2"))
                .thenThrow(new AwsException("InternalServerError", "simulated delete failure", 500))
                .thenReturn(null);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"KeyName\": \"first\", \"KeyType\": \"ed25519\"}"), ctx("first")));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(ec2, times(1)).deleteKeyPair(REGION, null, "key-2");

        provisioner.provision(r, props("{\"KeyName\": \"first\"}"), ctx("first"));
        assertTrue(provisioner.completeUpdate(r).complete());
        verify(ec2, times(2)).deleteKeyPair(REGION, null, "key-2");
        verify(ec2, never()).deleteKeyPair(REGION, null, "key-1");
    }

    /** The cleanup goes by name, yet a displaced key deleted outside the stack still takes its parameter. */
    @Test
    void theCommittedRenameRemovesTheParameterOfADisplacedKeyDeletedOutsideTheStack() throws Exception {
        StackResource r = renamed();
        when(ec2.describeKeyPairs(REGION, List.of("first"), List.of()))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'first' does not exist", 400));

        assertTrue(provisioner.completeUpdate(r).complete());
        provisioner.clearUpdate(r);

        verify(ssm).deleteParameter("/ec2/keypair/key-1", REGION);
        verify(ssm, never()).deleteParameter("/ec2/keypair/key-2", REGION);
        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
        assertFalse(r.getAttributes().containsKey("__FlociKeyPairCreatedKeys"));
    }

    @Test
    void aRolledBackRenameRemovesTheParameterOfAReplacementDeletedOutsideTheStack() throws Exception {
        StackResource r = renamed();
        when(ec2.describeKeyPairs(REGION, List.of("next"), List.of()))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'next' does not exist", 400));

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals("first", r.getPhysicalId());
        verify(ssm).deleteParameter("/ec2/keypair/key-2", REGION);
        verify(ssm, never()).deleteParameter("/ec2/keypair/key-1", REGION);
        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
        assertFalse(r.getAttributes().containsKey("__FlociKeyPairCreatedKeys"));
    }

    /** The retry runs after the revert, when the resource records the original key, not the replacement. */
    @Test
    void theRollbackRetryStillRemovesTheParameterOfAReplacementGoneSinceTheFirstAttempt() throws Exception {
        StackResource r = renamed();
        when(ec2.describeKeyPairs(REGION, List.of("next"), List.of()))
                .thenReturn(List.of(created("next", "key-2")))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'next' does not exist", 400));
        when(ec2.deleteKeyPair(REGION, null, "key-2"))
                .thenThrow(new AwsException("InternalServerError", "simulated delete failure", 500));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertTrue(provisioner.rollbackUpdate(r));

        verify(ssm, times(2)).deleteParameter("/ec2/keypair/key-2", REGION);
        verify(ssm, never()).deleteParameter("/ec2/keypair/key-1", REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertFalse(r.getAttributes().containsKey("__FlociKeyPairCreatedKeys"));
    }

    @Test
    void aDisplacedImportedKeyDeletedOutsideTheStackLeavesParameterStoreAlone() throws Exception {
        when(ec2.importKeyPair(REGION, "first", "ssh-ed25519 AAAA")).thenReturn(imported("first", "key-1"));
        when(ec2.createKeyPair(REGION, "next", null, null)).thenReturn(created("next", "key-2"));
        StackResource r = resource();
        provisioner.provision(r, props("{\"KeyName\": \"first\", \"PublicKeyMaterial\": \"ssh-ed25519 AAAA\"}"), ctx(null));
        provisioner.provision(r, props("{\"KeyName\": \"next\"}"), ctx("first"));
        when(ec2.describeKeyPairs(REGION, List.of("first"), List.of()))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'first' does not exist", 400));

        assertTrue(provisioner.completeUpdate(r).complete());

        verify(ssm, never()).deleteParameter(anyString(), anyString());
    }

    /** A key deleted outside the stack still has its parameter removed when the stack is deleted. */
    @Test
    void stackDeleteRemovesTheParameterOfAKeyDeletedOutsideTheStack() throws Exception {
        StackResource created = provisioned("{\"KeyName\": \"deploy\"}");
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'deploy' does not exist", 400));

        provisioner.delete(created, REGION);

        verify(ssm).deleteParameter("/ec2/keypair/key-1", REGION);
        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
    }

    /**
     * The delete follows the name, as Cloud Control's DeleteResource names the key by it alone: a key
     * made under the name outside the stack is the one deleted, with its parameter, and the parameter
     * the stack's own key left is deleted too.
     */
    @Test
    void stackDeleteFollowsTheNameAndStillRemovesTheRecordedKeysParameter() throws Exception {
        StackResource created = provisioned("{\"KeyName\": \"deploy\"}");
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(created("deploy", "key-9")));

        provisioner.delete(created, REGION);

        verify(ssm).deleteParameter("/ec2/keypair/key-9", REGION);
        verify(ec2).deleteKeyPair(REGION, null, "key-9");
        verify(ssm).deleteParameter("/ec2/keypair/key-1", REGION);
    }

    /** An imported key has no parameter, whether or not it is still there. */
    @Test
    void stackDeleteOfAnImportedKeyLeavesParameterStoreAlone() throws Exception {
        when(ec2.importKeyPair(REGION, "deploy", "ssh-ed25519 AAAA")).thenReturn(imported("deploy", "key-2"));
        StackResource imported = resource();
        provisioner.provision(imported,
                props("{\"KeyName\": \"deploy\", \"PublicKeyMaterial\": \"ssh-ed25519 AAAA\"}"), ctx(null));
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(imported("deploy", "key-2")))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'deploy' does not exist", 400));

        provisioner.delete(imported, REGION);
        provisioner.delete(imported, REGION);

        verify(ec2, times(1)).deleteKeyPair(REGION, null, "key-2");
        verifyNoInteractions(ssm);
    }

    @Test
    void deletingACreatedKeyDeletesItsParameterThenTheKey() {
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(created("deploy", "key-1")));

        provisioner.delete("AWS::EC2::KeyPair", "deploy", REGION);

        InOrder order = inOrder(ssm, ec2);
        order.verify(ssm).deleteParameter("/ec2/keypair/key-1", REGION);
        order.verify(ec2).deleteKeyPair(REGION, null, "key-1");
    }

    @Test
    void deletingAnImportedKeyLeavesParameterStoreAlone() {
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(imported("deploy", "key-2")));

        provisioner.delete("AWS::EC2::KeyPair", "deploy", REGION);

        verify(ec2).deleteKeyPair(REGION, null, "key-2");
        verifyNoInteractions(ssm);
    }

    @Test
    void deletingAKeyThatIsAlreadyGoneSucceeds() {
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenThrow(new AwsException("InvalidKeyPair.NotFound", "The key pair 'deploy' does not exist", 400));

        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::KeyPair", "deploy", REGION));

        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
        verifyNoInteractions(ssm);
    }

    @Test
    void aParameterThatIsAlreadyGoneStillLetsTheKeyBeDeleted() {
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(created("deploy", "key-1")));
        doThrow(new AwsException("ParameterNotFound", "Parameter /ec2/keypair/key-1 not found.", 400))
                .when(ssm).deleteParameter("/ec2/keypair/key-1", REGION);

        provisioner.delete("AWS::EC2::KeyPair", "deploy", REGION);

        verify(ec2).deleteKeyPair(REGION, null, "key-1");
    }

    @Test
    void aParameterDeleteThatFailsKeepsTheKeyForTheRetry() {
        when(ec2.describeKeyPairs(REGION, List.of("deploy"), List.of()))
                .thenReturn(List.of(created("deploy", "key-1")));
        doThrow(new AwsException("AccessDeniedException", "simulated denial", 400))
                .when(ssm).deleteParameter("/ec2/keypair/key-1", REGION);

        assertThrows(AwsException.class, () -> provisioner.delete("AWS::EC2::KeyPair", "deploy", REGION));

        verify(ec2, never()).deleteKeyPair(anyString(), any(), any());
    }

    /** A stack created while this type was stubbed holds a stub, which names no key. */
    @Test
    void aStubFromBeforeThisTypeWasProvisionedIsReplacedByARealKey() throws Exception {
        StackResource stub = resource();
        stub.setPhysicalId("Key-0a1b2c3d");
        stub.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Key");
        provisioner.delete(stub, REGION);
        verifyNoInteractions(ec2, ssm);

        when(ec2.createKeyPair(REGION, "deploy", null, null)).thenReturn(created("deploy", "key-1"));
        provisioner.provision(stub, props("{\"KeyName\": \"deploy\"}"), ctx("Key-0a1b2c3d"));

        assertEquals("deploy", stub.getPhysicalId());
        assertEquals("key-1", stub.getAttributes().get("KeyPairId"));
        assertFalse(provisioner.hasReplacementUpdate(stub), "a stub created nothing, so nothing is owed a delete");
    }

    /** A resource whose key {@code deploy}, {@code key-1}, CreateKeyPair made from {@code json}. */
    private StackResource provisioned(String json) throws Exception {
        when(ec2.createKeyPair(eq(REGION), eq("deploy"), any(), any())).thenReturn(created("deploy", "key-1"));
        StackResource r = resource();
        provisioner.provision(r, props(json), ctx(null));
        return r;
    }

    /** A stack whose key {@code first} was renamed to {@code next} by an update that has not ended. */
    private StackResource renamed() throws Exception {
        when(ec2.createKeyPair(REGION, "first", null, null)).thenReturn(created("first", "key-1"));
        when(ec2.createKeyPair(REGION, "next", null, null)).thenReturn(created("next", "key-2"));
        StackResource r = resource();
        provisioner.provision(r, props("{\"KeyName\": \"first\"}"), ctx(null));
        provisioner.provision(r, props("{\"KeyName\": \"next\"}"), ctx("first"));
        return r;
    }

    private static KeyPair created(String name, String id) {
        KeyPair key = imported(name, id);
        key.setKeyMaterial("private-" + id);
        return key;
    }

    private static KeyPair imported(String name, String id) {
        KeyPair key = new KeyPair();
        key.setKeyName(name);
        key.setKeyPairId(id);
        key.setKeyFingerprint("fingerprint-" + id);
        return key;
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.omitNoValue(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return "AWS::NoValue".equals(node.path("Ref").asText()) ? MissingNode.getInstance() : node;
        });
        return new ProvisionContext(engine, REGION, "000000000000", "stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Key");
        r.setResourceType("AWS::EC2::KeyPair");
        return r;
    }
}
