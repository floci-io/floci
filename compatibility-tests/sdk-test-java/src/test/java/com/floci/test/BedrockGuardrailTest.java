package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.CreateGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.CreateGuardrailVersionResponse;
import software.amazon.awssdk.services.bedrock.model.GetGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.GuardrailContentFilterType;
import software.amazon.awssdk.services.bedrock.model.GuardrailFilterStrength;
import software.amazon.awssdk.services.bedrock.model.GuardrailStatus;
import software.amazon.awssdk.services.bedrock.model.GuardrailTopicType;
import software.amazon.awssdk.services.bedrock.model.ListGuardrailsResponse;
import software.amazon.awssdk.services.bedrock.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrock.model.Tag;
import software.amazon.awssdk.services.bedrock.model.TooManyTagsException;
import software.amazon.awssdk.services.bedrock.model.UpdateGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.ValidationException;
import software.amazon.awssdk.services.kms.KmsClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bedrock guardrails")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockGuardrailTest {

    private static BedrockClient bedrock;
    private static String name;
    private static String guardrailId;
    private static String guardrailArn;
    private static String kmsKeyId;
    private static String kmsKeyArn;
    private static String kmsAlias;

    @BeforeAll
    static void setup() {
        bedrock = TestFixtures.bedrockClient();
        name = "sdk-test-guardrail-" + System.currentTimeMillis();
        kmsAlias = "alias/sdk-test-guardrail-" + System.currentTimeMillis();
        try (KmsClient kms = TestFixtures.kmsClient()) {
            var key = kms.createKey(r -> r.description("sdk compatibility guardrail key")).keyMetadata();
            kmsKeyId = key.keyId();
            kmsKeyArn = key.arn();
            kms.createAlias(r -> r.aliasName(kmsAlias).targetKeyId(kmsKeyId));
        }
    }

    @AfterAll
    static void cleanup() {
        if (bedrock != null) {
            if (guardrailId != null) {
                try {
                    bedrock.deleteGuardrail(r -> r.guardrailIdentifier(guardrailId));
                } catch (Exception e) {
                    System.out.println("Guardrail cleanup skipped: " + e.getMessage());
                }
            }
            bedrock.close();
        }
    }

    @Test
    @Order(1)
    void createGuardrail() {
        CreateGuardrailResponse response = bedrock.createGuardrail(r -> r
                .name(name)
                .description("created by the sdk compatibility suite")
                .blockedInputMessaging("Input blocked.")
                .blockedOutputsMessaging("Output blocked.")
                .kmsKeyId(kmsKeyId)
                .topicPolicyConfig(t -> t.topicsConfig(topic -> topic
                        .name("Investments")
                        .definition("Advice about buying or selling securities.")
                        .type(GuardrailTopicType.DENY)))
                .contentPolicyConfig(c -> c.filtersConfig(f -> f
                        .type(GuardrailContentFilterType.HATE)
                        .inputStrength(GuardrailFilterStrength.HIGH)
                        .outputStrength(GuardrailFilterStrength.HIGH)))
                .tags(Tag.builder().key("suite").value("sdk-compat").build()));

        guardrailId = response.guardrailId();
        guardrailArn = response.guardrailArn();

        assertThat(guardrailId).isNotBlank();
        assertThat(guardrailArn).contains(":bedrock:").contains(":guardrail/" + guardrailId);
        assertThat(response.version()).isEqualTo("DRAFT");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(2)
    void getGuardrailReadsBackTheReadShapes() {
        GetGuardrailResponse response = bedrock.getGuardrail(r -> r.guardrailIdentifier(guardrailId));

        assertThat(response.name()).isEqualTo(name);
        assertThat(response.guardrailId()).isEqualTo(guardrailId);
        assertThat(response.guardrailArn()).isEqualTo(guardrailArn);
        assertThat(response.version()).isEqualTo("DRAFT");
        // The terraform provider's create waiter polls until the guardrail leaves CREATING.
        assertThat(response.status()).isEqualTo(GuardrailStatus.READY);
        assertThat(response.blockedInputMessaging()).isEqualTo("Input blocked.");
        assertThat(response.blockedOutputsMessaging()).isEqualTo("Output blocked.");
        // A bare key id on the request comes back as a full KMS key ARN, which is the
        // only shape the GetGuardrail response model accepts.
        assertThat(response.kmsKeyArn()).isEqualTo(kmsKeyArn);
        assertThat(response.topicPolicy().topics()).singleElement()
                .satisfies(t -> assertThat(t.name()).isEqualTo("Investments"));
        assertThat(response.contentPolicy().filters()).singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(GuardrailContentFilterType.HATE));
    }

    @Test
    @Order(3)
    void updateGuardrail() {
        UpdateGuardrailResponse response = bedrock.updateGuardrail(r -> r
                .guardrailIdentifier(guardrailId)
                .name(name)
                .description("updated by the sdk compatibility suite")
                .blockedInputMessaging("Input still blocked.")
                .blockedOutputsMessaging("Output still blocked.")
                .kmsKeyId(kmsAlias));

        assertThat(response.guardrailId()).isEqualTo(guardrailId);
        assertThat(response.version()).isEqualTo("DRAFT");
        assertThat(response.updatedAt()).isNotNull();

        GetGuardrailResponse updated = bedrock.getGuardrail(r -> r.guardrailIdentifier(guardrailId));

        assertThat(updated.description()).isEqualTo("updated by the sdk compatibility suite");
        // An alias resolves to the same key ARN a bare key id did.
        assertThat(updated.kmsKeyArn()).isEqualTo(kmsKeyArn);
    }

    @Test
    @Order(4)
    void createGuardrailRejectsAnOverLongBlockedMessaging() {
        assertThatThrownBy(() -> bedrock.createGuardrail(r -> r
                .name(name + "-toolong")
                .blockedInputMessaging("x".repeat(501))
                .blockedOutputsMessaging("Output blocked.")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @Order(5)
    void createGuardrailVersionSnapshotsTheDraft() {
        CreateGuardrailVersionResponse response = bedrock.createGuardrailVersion(r -> r
                .guardrailIdentifier(guardrailId)
                .description("first published version"));

        assertThat(response.guardrailId()).isEqualTo(guardrailId);
        assertThat(response.version()).isEqualTo("1");

        GetGuardrailResponse published = bedrock.getGuardrail(r -> r
                .guardrailIdentifier(guardrailId).guardrailVersion("1"));

        assertThat(published.version()).isEqualTo("1");
        assertThat(published.description()).isEqualTo("first published version");
        assertThat(published.blockedInputMessaging()).isEqualTo("Input still blocked.");
    }

    @Test
    @Order(6)
    void listGuardrailsReturnsTheDraft() {
        ListGuardrailsResponse response = bedrock.listGuardrails(r -> r.maxResults(100));

        assertThat(response.guardrails())
                .anySatisfy(g -> {
                    assertThat(g.id()).isEqualTo(guardrailId);
                    assertThat(g.arn()).isEqualTo(guardrailArn);
                    assertThat(g.status()).isEqualTo(GuardrailStatus.READY);
                });
    }

    @Test
    @Order(7)
    void tagResourceRoundTrip() {
        bedrock.tagResource(r -> r.resourceARN(guardrailArn)
                .tags(Tag.builder().key("env").value("test").build()));

        assertThat(bedrock.listTagsForResource(r -> r.resourceARN(guardrailArn)).tags())
                .anySatisfy(t -> {
                    assertThat(t.key()).isEqualTo("env");
                    assertThat(t.value()).isEqualTo("test");
                });

        bedrock.untagResource(r -> r.resourceARN(guardrailArn).tagKeys("env"));

        assertThat(bedrock.listTagsForResource(r -> r.resourceARN(guardrailArn)).tags())
                .noneSatisfy(t -> assertThat(t.key()).isEqualTo("env"));
    }

    @Test
    @Order(8)
    void tagResourceRejectsMoreTagsThanOneResourceMayHold() {
        // The 50 tag limit belongs to the resource, so it is measured against the total left
        // after the merge rather than the size of the incoming request.
        bedrock.tagResource(r -> r.resourceARN(guardrailArn).tags(tags("bulk-", 49)));

        assertThatThrownBy(() -> bedrock.tagResource(r -> r.resourceARN(guardrailArn)
                .tags(Tag.builder().key("one-too-many").value("x").build())))
                .isInstanceOf(TooManyTagsException.class)
                .satisfies(e -> assertThat(((TooManyTagsException) e).resourceName()).isEqualTo(guardrailArn));

        bedrock.untagResource(r -> r.resourceARN(guardrailArn)
                .tagKeys(tags("bulk-", 49).stream().map(Tag::key).toList()));
    }

    private static List<Tag> tags(String prefix, int count) {
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tags.add(Tag.builder().key(prefix + i).value("v" + i).build());
        }
        return tags;
    }

    @Test
    @Order(9)
    void deleteGuardrailThenGetThrowsResourceNotFound() {
        bedrock.deleteGuardrail(r -> r.guardrailIdentifier(guardrailId));

        // The terraform provider's delete waiter matches this typed exception to treat
        // the guardrail as gone.
        String deletedId = guardrailId;
        assertThatThrownBy(() -> bedrock.getGuardrail(r -> r.guardrailIdentifier(deletedId)))
                .isInstanceOf(ResourceNotFoundException.class);

        guardrailId = null;
    }
}
