package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsManagerSelectorTest {

    private static final String BASE =
            "arn:aws:secretsmanager:us-east-2:000000000000:secret:app/config-AbCdEf";

    @Test
    void plainArnPassesThroughVerbatim() {
        var sel = SecretsManagerSelector.parse(BASE);
        assertEquals(BASE, sel.secretId());
        assertNull(sel.jsonKey());
        assertNull(sel.versionStage());
        assertNull(sel.versionId());
    }

    @Test
    void jsonKeyOnlySelector() {
        // CDK Secret.fromSecretsManager(secret, 'token') emits ":token::".
        var sel = SecretsManagerSelector.parse(BASE + ":token::");
        assertEquals(BASE, sel.secretId());
        assertEquals("token", sel.jsonKey());
        assertNull(sel.versionStage());
        assertNull(sel.versionId());
    }

    @Test
    void emptyJsonKeyWithVersionIdMeansWholeSecret() {
        // CDK fromSecretsManagerVersion(secret, {versionId}) with no field emits ":::id".
        var sel = SecretsManagerSelector.parse(BASE + ":::v123");
        assertEquals(BASE, sel.secretId());
        assertNull(sel.jsonKey());
        assertNull(sel.versionStage());
        assertEquals("v123", sel.versionId());
    }

    @Test
    void emptyJsonKeyWithVersionStage() {
        var sel = SecretsManagerSelector.parse(BASE + "::AWSPREVIOUS:");
        assertNull(sel.jsonKey());
        assertEquals("AWSPREVIOUS", sel.versionStage());
        assertNull(sel.versionId());
    }

    @Test
    void fullSelector() {
        var sel = SecretsManagerSelector.parse(BASE + ":token:AWSPREVIOUS:v123");
        assertEquals(BASE, sel.secretId());
        assertEquals("token", sel.jsonKey());
        assertEquals("AWSPREVIOUS", sel.versionStage());
        assertEquals("v123", sel.versionId());
    }

    @Test
    void partialArnBaseIsRebuiltWithoutSuffix() {
        String partial = "arn:aws:secretsmanager:us-east-2:000000000000:secret:app/config";
        var sel = SecretsManagerSelector.parse(partial + ":token::");
        assertEquals(partial, sel.secretId());
        assertEquals("token", sel.jsonKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 3 segments: the hand-written ":field" form CDK never emits; the agent rejects it.
            BASE + ":token",
            // 4 segments: also covers a CFN dynamic-reference style ":SecretString:key" mixup.
            BASE + ":SecretString:token",
            // 6 segments.
            BASE + ":token:AWSPREVIOUS:v123:extra",
    })
    void invalidSegmentCountsRejectedWithAgentMessage(String valueFrom) {
        AwsException e = assertThrows(AwsException.class, () -> SecretsManagerSelector.parse(valueFrom));
        assertTrue(e.getMessage().contains(
                "an invalid ARN format for the AWS Secrets Manager secret was specified"));
    }

    @Test
    void extractsTopLevelJsonKey() {
        assertEquals("expected-value",
                SecretsManagerSelector.extractJsonKey("{\"token\":\"expected-value\"}", "token"));
    }

    @Test
    void nonStringJsonValuesAreStringified() {
        assertEquals("10000000",
                SecretsManagerSelector.extractJsonKey("{\"port\":10000000}", "port"));
        assertEquals("true",
                SecretsManagerSelector.extractJsonKey("{\"enabled\":true}", "enabled"));
    }

    @Test
    void objectAndArrayValuesSerializeAsJson() {
        // Third deliberate divergence from the real agent: Go's %v would inject
        // "map[a:1 b:2]" for a nested object; JSON is parseable by the consumer.
        assertEquals("{\"a\":1,\"b\":2}",
                SecretsManagerSelector.extractJsonKey("{\"nested\":{\"a\":1,\"b\":2}}", "nested"));
        assertEquals("[1,2,3]",
                SecretsManagerSelector.extractJsonKey("{\"list\":[1,2,3]}", "list"));
    }

    @Test
    void missingJsonKeyIsAnError() {
        AwsException e = assertThrows(AwsException.class,
                () -> SecretsManagerSelector.extractJsonKey("{\"other\":\"x\"}", "token"));
        assertTrue(e.getMessage().contains("did not contain json key token"));
    }

    @Test
    void nonJsonSecretWithKeyIsAnError() {
        AwsException e = assertThrows(AwsException.class,
                () -> SecretsManagerSelector.extractJsonKey("not-json", "token"));
        assertTrue(e.getMessage().contains("not valid JSON"));
    }
}
