package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The secret provisioner in isolation, mocking only SecretsManagerService. The update path is the
 * #2134 case: an identical UpdateStack must reconcile the named secret in place, never create it
 * again.
 */
class SecretsManagerCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:app/db-AbCdEf";

    private final SecretsManagerService secrets = mock(SecretsManagerService.class);
    private final SecretsManagerCfnProvisioner provisioner = new SecretsManagerCfnProvisioner(secrets);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        return new ProvisionContext(ProvisionContexts.passthroughEngine(),
                REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("DbSecret");
        r.setResourceType("AWS::SecretsManager::Secret");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Secret secret(String name, String description, Map<String, String> tags) {
        Secret s = new Secret();
        s.setName(name);
        s.setArn(ARN);
        s.setDescription(description);
        s.setTags(tags.entrySet().stream().map(e -> new Secret.Tag(e.getKey(), e.getValue())).toList());
        return s;
    }

    private ObjectNode props(String name, String value, Map<String, String> tags) {
        ObjectNode props = mapper.createObjectNode().put("Name", name).put("SecretString", value);
        ArrayNode tagNodes = props.putArray("Tags");
        tags.forEach((k, v) -> tagNodes.addObject().put("Key", k).put("Value", v));
        return props;
    }

    private static SecretVersion version(String value) {
        SecretVersion v = new SecretVersion();
        v.setSecretString(value);
        return v;
    }

    @Test
    void createPassesNameValueKmsKeyAndTags() {
        when(secrets.createSecret(eq("app/db"), eq("s3cret"), isNull(), eq("db creds"), eq("alias/app"),
                anyList(), eq(REGION))).thenReturn(secret("app/db", "db creds", Map.of()));
        ObjectNode props = props("app/db", "s3cret", Map.of("team", "a")).put("Description", "db creds")
                .put("KmsKeyId", "alias/app");

        StackResource r = resource();
        provisioner.provision(r, props, ctx(null));

        verify(secrets).createSecret(eq("app/db"), eq("s3cret"), isNull(), eq("db creds"), eq("alias/app"),
                eq(List.of(new Secret.Tag("team", "a"))), eq(REGION));
        assertEquals(ARN, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("app/db", r.getAttributes().get("Name"));
    }

    @Test
    void identicalUpdateReconcilesInPlaceAndCreatesNothing() {
        Secret existing = secret("app/db", "db creds", Map.of("team", "a"));
        when(secrets.describeSecret(ARN, REGION)).thenReturn(existing);
        when(secrets.updateSecret(eq(ARN), any(), any(), eq(REGION))).thenReturn(existing);
        when(secrets.getSecretValue(ARN, null, null, REGION)).thenReturn(version("s3cret"));
        ObjectNode props = props("app/db", "s3cret", Map.of("team", "a")).put("Description", "db creds");

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets, never()).createSecret(anyString(), any(), any(), any(), any(), anyList(), anyString());
        verify(secrets, never()).putSecretValue(anyString(), any(), any(), any(), anyString(), any());
        verify(secrets, never()).tagResource(anyString(), anyList(), anyString());
        verify(secrets, never()).untagResource(anyString(), anyList(), anyString());
        verify(secrets).updateSecret(ARN, "db creds", null, REGION);
        assertEquals(ARN, r.getPhysicalId());
    }

    @Test
    void updateDrivesValueAndTagsToTheTemplate() {
        Secret existing = secret("app/db", "old", Map.of("team", "a", "stale", "x"));
        when(secrets.describeSecret(ARN, REGION)).thenReturn(existing);
        when(secrets.updateSecret(eq(ARN), any(), any(), eq(REGION))).thenReturn(existing);
        when(secrets.getSecretValue(ARN, null, null, REGION)).thenReturn(version("old-value"));
        ObjectNode props = props("app/db", "new-value", Map.of("team", "b")).put("Description", "new");

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets).putSecretValue(ARN, "new-value", null, null, REGION, null);
        verify(secrets).untagResource(ARN, List.of("stale"), REGION);
        verify(secrets).tagResource(ARN, List.of(new Secret.Tag("team", "b")), REGION);
        verify(secrets, never()).createSecret(anyString(), any(), any(), any(), any(), anyList(), anyString());
    }

    @Test
    void generatedNameIsKeptAcrossUpdatesAndAnUnchangedGenerateConfigDoesNotRotate() {
        Secret existing = secret("my-stack-DbSecret-a1b2c3d4e5f6", null, Map.of());
        when(secrets.describeSecret(ARN, REGION)).thenReturn(existing);
        when(secrets.updateSecret(eq(ARN), any(), any(), eq(REGION))).thenReturn(existing);
        ObjectNode props = mapper.createObjectNode();
        props.putObject("GenerateSecretString").put("PasswordLength", 16);

        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("FlociSecretNameMode", "generated");
        r.getAttributes().put("FlociSecretGenerateIdentity", props.get("GenerateSecretString").toString());
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets, never()).createSecret(anyString(), any(), any(), any(), any(), anyList(), anyString());
        verify(secrets, never()).putSecretValue(anyString(), any(), any(), any(), anyString(), any());
        assertEquals("my-stack-DbSecret-a1b2c3d4e5f6", r.getAttributes().get("Name"));
        assertEquals("generated", r.getAttributes().get("FlociSecretNameMode"));
    }

    /** The registry's update handler holds GetRandomPassword: a changed generation config makes a new value. */
    @Test
    void aChangedGenerateConfigGeneratesANewVersion() {
        Secret existing = secret("app/db", null, Map.of());
        when(secrets.describeSecret(ARN, REGION)).thenReturn(existing);
        when(secrets.updateSecret(eq(ARN), any(), any(), eq(REGION))).thenReturn(existing);
        ObjectNode props = mapper.createObjectNode().put("Name", "app/db");
        props.putObject("GenerateSecretString").put("PasswordLength", 32).put("ExcludePunctuation", true);

        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("FlociSecretGenerateIdentity", "{\"PasswordLength\":16}");
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets).putSecretValue(eq(ARN), argThat((String value) -> value != null && value.length() == 32),
                isNull(), isNull(), eq(REGION), isNull());
        assertEquals(props.get("GenerateSecretString").toString(), r.getAttributes().get("FlociSecretGenerateIdentity"));
    }

    /** Switching from SecretString to GenerateSecretString is a change: the update generates a new value. */
    @Test
    void switchingFromSecretStringToGenerateGeneratesANewVersion() {
        Secret existing = secret("app/db", null, Map.of());
        when(secrets.describeSecret(ARN, REGION)).thenReturn(existing);
        when(secrets.updateSecret(eq(ARN), any(), any(), eq(REGION))).thenReturn(existing);
        when(secrets.getSecretValue(ARN, null, null, REGION)).thenReturn(version("s3cret"));

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, props("app/db", "s3cret", Map.of()), ctx(ARN));
        assertEquals("none", r.getAttributes().get("FlociSecretGenerateIdentity"));
        verify(secrets, never()).putSecretValue(anyString(), any(), any(), any(), anyString(), any());

        ObjectNode props = mapper.createObjectNode().put("Name", "app/db");
        props.putObject("GenerateSecretString").put("PasswordLength", 24);
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets).putSecretValue(eq(ARN), argThat((String value) -> value != null && value.length() == 24),
                isNull(), isNull(), eq(REGION), isNull());
        assertEquals(props.get("GenerateSecretString").toString(), r.getAttributes().get("FlociSecretGenerateIdentity"));
    }

    /**
     * A record from before the identity was tracked cannot show whether its prior template used a
     * literal SecretString or another generation policy, so it generates even when the current
     * value already has the configured length.
     */
    @Test
    void aLegacyRecordWithAGenerateConfigGenerates() {
        Secret existing = secret("app/db", null, Map.of());
        when(secrets.describeSecret(ARN, REGION)).thenReturn(existing);
        when(secrets.updateSecret(eq(ARN), any(), any(), eq(REGION))).thenReturn(existing);
        when(secrets.getSecretValue(ARN, null, null, REGION)).thenReturn(version("x".repeat(32)));
        ObjectNode props = mapper.createObjectNode().put("Name", "app/db");
        props.putObject("GenerateSecretString").put("PasswordLength", 32).put("ExcludePunctuation", true);

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets).putSecretValue(eq(ARN), argThat((String value) -> value != null && value.length() == 32),
                isNull(), isNull(), eq(REGION), isNull());
        assertEquals(props.get("GenerateSecretString").toString(), r.getAttributes().get("FlociSecretGenerateIdentity"));
    }

    /** Name is create-only: dropping an explicit name is a replacement under a generated name, as on AWS. */
    @Test
    void droppingAnExplicitNameIsAReplacement() {
        when(secrets.describeSecret(ARN, REGION)).thenReturn(secret("app/db", null, Map.of()));
        Secret replacement = new Secret();
        replacement.setName("my-stack-DbSecret-0123456789ab");
        replacement.setArn(ARN.replace("app/db", "my-stack-DbSecret-0123456789ab"));
        when(secrets.createSecret(anyString(), eq("s3cret"), isNull(), isNull(), isNull(), anyList(), eq(REGION)))
                .thenReturn(replacement);
        ObjectNode props = mapper.createObjectNode().put("SecretString", "s3cret");

        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("FlociSecretNameMode", "explicit");
        provisioner.provision(r, props, ctx(ARN));

        verify(secrets, never()).updateSecret(anyString(), any(), any(), anyString());
        verify(secrets).createSecret(argThat((String name) -> name.startsWith("my-stack-DbSecret-")), eq("s3cret"),
                isNull(), isNull(), isNull(), anyList(), eq(REGION));
        assertEquals(replacement.getArn(), r.getPhysicalId());
        assertEquals("generated", r.getAttributes().get("FlociSecretNameMode"));
    }

    /** A stack persisted before the mode was recorded: an explicit-looking prior name counts as explicit. */
    @Test
    void anUnrecordedPriorNameIsClassifiedByItsShape() {
        when(secrets.describeSecret(ARN, REGION)).thenReturn(secret("app/db", null, Map.of()));
        Secret replacement = new Secret();
        replacement.setName("my-stack-DbSecret-0123456789ab");
        replacement.setArn(ARN.replace("app/db", "my-stack-DbSecret-0123456789ab"));
        when(secrets.createSecret(anyString(), eq("s3cret"), isNull(), isNull(), isNull(), anyList(), eq(REGION)))
                .thenReturn(replacement);

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, mapper.createObjectNode().put("SecretString", "s3cret"), ctx(ARN));

        verify(secrets, never()).updateSecret(anyString(), any(), any(), anyString());
        assertEquals(replacement.getArn(), r.getPhysicalId());
    }

    @Test
    void aChangedNameIsAReplacement() {
        when(secrets.describeSecret(ARN, REGION)).thenReturn(secret("app/db", null, Map.of()));
        Secret replacement = new Secret();
        replacement.setName("app/db-v2");
        replacement.setArn(ARN.replace("app/db", "app/db-v2"));
        when(secrets.createSecret(eq("app/db-v2"), eq("s3cret"), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(replacement);

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, props("app/db-v2", "s3cret", Map.of()), ctx(ARN));

        verify(secrets, never()).updateSecret(anyString(), any(), any(), anyString());
        assertEquals(replacement.getArn(), r.getPhysicalId());
        assertEquals("app/db-v2", r.getAttributes().get("Name"));
    }

    @Test
    void aPriorSecretDeletedOutOfBandIsCreatedAfresh() {
        when(secrets.describeSecret(ARN, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 400));
        when(secrets.createSecret(eq("app/db"), eq("s3cret"), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(secret("app/db", null, Map.of()));

        StackResource r = resource();
        r.setPhysicalId(ARN);
        provisioner.provision(r, props("app/db", "s3cret", Map.of()), ctx(ARN));

        verify(secrets).createSecret(eq("app/db"), eq("s3cret"), isNull(), isNull(), isNull(), anyList(),
                eq(REGION));
        assertEquals(ARN, r.getPhysicalId());
    }

    // --- dropped Description, against a real in-memory SecretsManagerService ---

    private static final String WITH_DESCRIPTION = """
            {"Name":"app/db","SecretString":"v1","Description":"orig","KmsKeyId":"alias/app"}
            """;
    private static final String WITHOUT_DESCRIPTION = """
            {"Name":"app/db","SecretString":"v1","KmsKeyId":"alias/app"}
            """;

    private SecretsManagerService realSecrets() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        return new SecretsManagerService(storageFactory, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                new RegionResolver(REGION, "000000000000"), null, new ObjectMapper(), null);
    }

    private StackResource deploySecret(SecretsManagerService real, String properties) throws Exception {
        StackResource r = resource();
        new SecretsManagerCfnProvisioner(real).provision(r, mapper.readTree(properties), ctx(null));
        return r;
    }

    private void updateSecret(SecretsManagerService real, StackResource r, String previous, String current)
            throws Exception {
        new SecretsManagerCfnProvisioner(real).provision(r, mapper.readTree(current),
                ProvisionContexts.update(REGION, "000000000000", "my-stack",
                        r.getPhysicalId(), previous));
    }

    @Test
    void secretDescriptionDroppedFromTheTemplateIsClearedOnUpdate() throws Exception {
        SecretsManagerService real = realSecrets();
        StackResource r = deploySecret(real, WITH_DESCRIPTION);
        assertEquals("orig", real.describeSecret("app/db", REGION).getDescription());

        updateSecret(real, r, WITH_DESCRIPTION, WITHOUT_DESCRIPTION);

        assertNull(real.describeSecret("app/db", REGION).getDescription());
    }

    @Test
    void changedSecretDescriptionStillApplies() throws Exception {
        SecretsManagerService real = realSecrets();
        StackResource r = deploySecret(real, WITH_DESCRIPTION);

        updateSecret(real, r, WITH_DESCRIPTION, WITH_DESCRIPTION.replace("orig", "changed"));

        assertEquals("changed", real.describeSecret("app/db", REGION).getDescription());
    }

    @Test
    void emptySecretDescriptionIsStoredAsGivenAndALaterDropClearsIt() throws Exception {
        SecretsManagerService real = realSecrets();
        StackResource r = deploySecret(real, WITH_DESCRIPTION);
        String empty = WITH_DESCRIPTION.replace("\"orig\"", "\"\"");

        updateSecret(real, r, WITH_DESCRIPTION, empty);
        assertEquals("", real.describeSecret("app/db", REGION).getDescription());

        updateSecret(real, r, empty, WITHOUT_DESCRIPTION);
        assertNull(real.describeSecret("app/db", REGION).getDescription());
    }

    @Test
    void secretDescriptionSetOutsideTheStackSurvivesAnUpdateThatNeverDeclaredIt() throws Exception {
        SecretsManagerService real = realSecrets();
        StackResource r = deploySecret(real, WITHOUT_DESCRIPTION);
        real.updateSecret("app/db", "outside", null, REGION);

        updateSecret(real, r, WITHOUT_DESCRIPTION, WITHOUT_DESCRIPTION.replace("\"v1\"", "\"v2\""));

        assertEquals("outside", real.describeSecret("app/db", REGION).getDescription());
        assertEquals("v2", real.getSecretValue("app/db", null, null, REGION).getSecretString());
    }

    @Test
    void droppingTheSecretDescriptionKeepsTheValueAndTheKmsKey() throws Exception {
        SecretsManagerService real = realSecrets();
        StackResource r = deploySecret(real, WITH_DESCRIPTION);

        updateSecret(real, r, WITH_DESCRIPTION, WITHOUT_DESCRIPTION);

        assertEquals("v1", real.getSecretValue("app/db", null, null, REGION).getSecretString());
        assertEquals("alias/app", real.describeSecret("app/db", REGION).getKmsKeyId());
    }
}
