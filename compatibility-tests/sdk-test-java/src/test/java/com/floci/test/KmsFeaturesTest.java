package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.GetKeyPolicyResponse;
import software.amazon.awssdk.services.kms.model.ListResourceTagsResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for KMS fixes:
 *   #269 — CreateKey applies Tags at creation time
 *   #258 — GetKeyPolicy returns the stored policy
 *   #259 — PutKeyPolicy updates the key policy
 */
@DisplayName("KMS features (#258 #259 #269)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KmsFeaturesTest {

    private static KmsClient kms;

    @BeforeAll
    static void setup() {
        kms = TestFixtures.kmsClient();
    }

    @AfterAll
    static void cleanup() {
        if (kms != null) kms.close();
    }

    // ── Issue #269 — CreateKey applies Tags ───────────────────────────────────

    @Test
    @Order(10)
    void createKeyWithTagsStoresTags() {
        CreateKeyResponse resp = kms.createKey(b -> b
                .description("tagged-key")
                .tags(
                        software.amazon.awssdk.services.kms.model.Tag.builder().tagKey("env").tagValue("prod").build(),
                        software.amazon.awssdk.services.kms.model.Tag.builder().tagKey("team").tagValue("platform").build()
                ));
        String keyId = resp.keyMetadata().keyId();

        ListResourceTagsResponse tags = kms.listResourceTags(b -> b.keyId(keyId));
        Map<String, String> tagMap = tags.tags().stream()
                .collect(java.util.stream.Collectors.toMap(
                        software.amazon.awssdk.services.kms.model.Tag::tagKey,
                        software.amazon.awssdk.services.kms.model.Tag::tagValue));

        assertThat(tagMap).containsEntry("env", "prod");
        assertThat(tagMap).containsEntry("team", "platform");

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(11)
    void createKeyWithoutTagsHasEmptyTagList() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("no-tags-key"));
        String keyId = resp.keyMetadata().keyId();

        ListResourceTagsResponse tags = kms.listResourceTags(b -> b.keyId(keyId));
        assertThat(tags.tags()).isEmpty();

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    // ── Issue #258 — GetKeyPolicy ─────────────────────────────────────────────

    @Test
    @Order(20)
    void createKeyWithoutPolicyReturnsDefaultPolicy() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("default-policy-key"));
        String keyId = resp.keyMetadata().keyId();

        GetKeyPolicyResponse policyResp = kms.getKeyPolicy(b -> b.keyId(keyId));
        assertThat(policyResp.policy()).isNotBlank();
        assertThat(policyResp.policyName()).isEqualTo("default");
        assertThat(policyResp.policy()).contains("kms:*");

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(21)
    void createKeyWithPolicyStoresAndReturnsPolicy() {
        String customPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Custom\"," +
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
                "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";

        CreateKeyResponse resp = kms.createKey(b -> b
                .description("custom-policy-key")
                .policy(customPolicy));
        String keyId = resp.keyMetadata().keyId();

        GetKeyPolicyResponse policyResp = kms.getKeyPolicy(b -> b.keyId(keyId));
        assertThat(policyResp.policy()).isEqualTo(customPolicy);
        assertThat(policyResp.policyName()).isEqualTo("default");

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    // ── Issue #259 — PutKeyPolicy ─────────────────────────────────────────────

    @Test
    @Order(30)
    void putKeyPolicyUpdatesPolicy() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("put-policy-key"));
        String keyId = resp.keyMetadata().keyId();

        String newPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Updated\"," +
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
                "\"Action\":\"kms:Decrypt\",\"Resource\":\"*\"}]}";

        kms.putKeyPolicy(b -> b.keyId(keyId).policy(newPolicy));

        GetKeyPolicyResponse policyResp = kms.getKeyPolicy(b -> b.keyId(keyId));
        assertThat(policyResp.policy()).isEqualTo(newPolicy);

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }

    @Test
    @Order(31)
    void putKeyPolicyRoundTrip() {
        CreateKeyResponse resp = kms.createKey(b -> b.description("round-trip-key"));
        String keyId = resp.keyMetadata().keyId();

        // Get initial policy
        String initial = kms.getKeyPolicy(b -> b.keyId(keyId)).policy();
        assertThat(initial).isNotBlank();

        // Put a new policy
        String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"RoundTrip\"," +
                "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
                "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";
        kms.putKeyPolicy(b -> b.keyId(keyId).policy(updated));

        // Verify change persisted
        assertThat(kms.getKeyPolicy(b -> b.keyId(keyId)).policy()).isEqualTo(updated);
        assertThat(kms.getKeyPolicy(b -> b.keyId(keyId)).policy()).isNotEqualTo(initial);

        kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
    }
}
