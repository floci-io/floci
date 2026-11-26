package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrock.model.EnforcedGuardrailConfiguration;
import io.floci.aws.services.bedrock.model.Guardrail;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class BedrockServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String KEY_ID = "8f2b1c4e-0a7d-4e5f-9b31-6c8d2e4f7a90";
    private static final String KEY_ARN = "arn:aws:kms:" + REGION + ":" + ACCOUNT + ":key/" + KEY_ID;

    private final ObjectMapper mapper = new ObjectMapper();
    private KmsService kmsService;
    private BedrockService service;

    @BeforeEach
    void setUp() {
        kmsService = mock(KmsService.class);
        service = new BedrockService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), mapper, kmsService);
    }

    /** Registers one usable KMS key that answers to every form the AWS model accepts. */
    private void knownKey(String... forms) {
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setArn(KEY_ARN);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        for (String form : forms) {
            doReturn(key).when(kmsService).describeKey(form, REGION);
        }
    }

    private ObjectNode createRequest(String name) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", name);
        request.put("blockedInputMessaging", "input blocked");
        request.put("blockedOutputsMessaging", "output blocked");
        return request;
    }

    private Guardrail create(String name) {
        return service.createGuardrail(createRequest(name), REGION);
    }

    @Test
    void createProducesADraftWithAModelShapedIdAndArn() {
        Guardrail guardrail = create("my-guardrail");

        assertTrue(guardrail.getGuardrailId().matches("[a-z0-9]+"), guardrail.getGuardrailId());
        assertEquals(BedrockService.DRAFT_VERSION, guardrail.getVersion());
        assertEquals("arn:aws:bedrock:" + REGION + ":" + ACCOUNT + ":guardrail/" + guardrail.getGuardrailId(),
                guardrail.getGuardrailArn());
        assertNotNull(guardrail.getCreatedAt());
        assertEquals(guardrail.getCreatedAt(), guardrail.getUpdatedAt());
    }

    @Test
    void createRenamesPolicyConfigMembersToTheirReadShapes() {
        ObjectNode request = createRequest("policies");
        request.putObject("topicPolicyConfig").putArray("topicsConfig").addObject()
                .put("name", "Investments").put("type", "DENY");
        request.putObject("contentPolicyConfig").putArray("filtersConfig").addObject()
                .put("type", "HATE").put("inputStrength", "HIGH");
        request.putObject("wordPolicyConfig").putArray("wordsConfig").addObject().put("text", "forbidden");
        request.putObject("sensitiveInformationPolicyConfig").putArray("piiEntitiesConfig").addObject()
                .put("type", "EMAIL").put("action", "BLOCK");

        Guardrail guardrail = service.createGuardrail(request, REGION);

        assertEquals("Investments", guardrail.getTopicPolicy().path("topics").path(0).path("name").asText());
        assertEquals("HATE", guardrail.getContentPolicy().path("filters").path(0).path("type").asText());
        assertEquals("forbidden", guardrail.getWordPolicy().path("words").path(0).path("text").asText());
        assertEquals("EMAIL",
                guardrail.getSensitiveInformationPolicy().path("piiEntities").path(0).path("type").asText());
    }

    @Test
    void createRejectsAnEmptyRequiredMember() {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "no-messaging");
        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createRejectsANameOutsideTheModelPattern() {
        AwsException error = assertThrows(AwsException.class, () -> create("not a valid name"));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void createRejectsADuplicateName() {
        create("duplicate");
        AwsException error = assertThrows(AwsException.class, () -> create("duplicate"));
        assertEquals("ConflictException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void getResolvesAnArnAsWellAsAnId() {
        Guardrail created = create("by-arn");
        Guardrail byArn = service.getGuardrail(created.getGuardrailArn(), null, REGION);
        assertEquals(created.getGuardrailId(), byArn.getGuardrailId());
    }

    @Test
    void getRejectsAMalformedIdentifier() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getGuardrail("arn:aws:bedrock", null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void getMissingGuardrailRaisesResourceNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.getGuardrail("doesnotexist", null, REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void updateWritesTheDraftAndLeavesPublishedVersionsAlone() {
        Guardrail created = create("versioned");
        service.createGuardrailVersion(created.getGuardrailId(), "first cut", REGION);

        ObjectNode update = createRequest("versioned");
        update.put("description", "after the update");
        service.updateGuardrail(created.getGuardrailId(), update, REGION);

        assertEquals("after the update",
                service.getGuardrail(created.getGuardrailId(), null, REGION).getDescription());
        assertEquals("first cut",
                service.getGuardrail(created.getGuardrailId(), "1", REGION).getDescription());
    }

    @Test
    void versionsAreNumberedFromOne() {
        Guardrail created = create("counting");
        assertEquals("1", service.createGuardrailVersion(created.getGuardrailId(), null, REGION).getVersion());
        assertEquals("2", service.createGuardrailVersion(created.getGuardrailId(), null, REGION).getVersion());
    }

    @Test
    void listWithoutAnIdentifierReturnsOneDraftPerGuardrail() {
        Guardrail first = create("first");
        create("second");
        service.createGuardrailVersion(first.getGuardrailId(), null, REGION);

        List<Guardrail> listed = service.listGuardrails(null, null, null, REGION).items();

        assertEquals(2, listed.size());
        assertTrue(listed.stream().allMatch(g -> BedrockService.DRAFT_VERSION.equals(g.getVersion())));
    }

    @Test
    void listWithAnIdentifierReturnsEveryVersion() {
        Guardrail created = create("all-versions");
        service.createGuardrailVersion(created.getGuardrailId(), null, REGION);

        List<Guardrail> listed = service.listGuardrails(created.getGuardrailId(), null, null, REGION).items();

        assertEquals(2, listed.size());
        assertEquals(List.of("1", BedrockService.DRAFT_VERSION),
                listed.stream().map(Guardrail::getVersion).toList());
    }

    @Test
    void listPagesOnMaxResultsAndResumesFromTheToken() {
        create("alpha");
        create("beta");
        create("gamma");

        PaginatedResult<Guardrail> firstPage = service.listGuardrails(null, 2, null, REGION);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<Guardrail> secondPage =
                service.listGuardrails(null, 2, firstPage.nextToken(), REGION);
        assertEquals(1, secondPage.items().size());
        assertNull(secondPage.nextToken());
    }

    @Test
    void listRejectsAMaxResultsOutsideTheModelRange() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listGuardrails(null, 0, null, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void deleteWithoutAVersionRemovesEveryVersion() {
        Guardrail created = create("gone");
        service.createGuardrailVersion(created.getGuardrailId(), null, REGION);

        service.deleteGuardrail(created.getGuardrailId(), null, REGION);

        assertThrows(AwsException.class, () -> service.getGuardrail(created.getGuardrailId(), null, REGION));
        assertThrows(AwsException.class, () -> service.getGuardrail(created.getGuardrailId(), "1", REGION));
    }

    @Test
    void deleteRejectsANonNumericalVersion() {
        Guardrail created = create("draft-delete");
        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteGuardrail(created.getGuardrailId(), BedrockService.DRAFT_VERSION, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void tagsRoundTripThroughTheArn() {
        Guardrail created = create("tagged");
        String arn = created.getGuardrailArn();

        service.tagResource(arn, Map.of("team", "ai"), REGION);
        service.tagResource(arn, Map.of("env", "test"), REGION);
        assertEquals(Map.of("team", "ai", "env", "test"), service.listTags(arn, REGION));

        service.untagResource(arn, List.of("env"), REGION);
        assertEquals(Map.of("team", "ai"), service.listTags(arn, REGION));
    }

    @Test
    void taggingAnUnknownResourceRaisesResourceNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listTags("arn:aws:bedrock:" + REGION + ":" + ACCOUNT + ":guardrail/missing", REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    // Length constraints from the AWS model

    @Test
    void createAcceptsADescriptionExactlyAtTheModelCap() {
        ObjectNode request = createRequest("description-at-cap");
        request.put("description", "d".repeat(200));

        assertEquals(200, service.createGuardrail(request, REGION).getDescription().length());
    }

    @Test
    void createRejectsADescriptionOverTheModelCap() {
        ObjectNode request = createRequest("description-over-cap");
        request.put("description", "d".repeat(201));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains("description"), error.getMessage());
    }

    @Test
    void createRejectsAnEmptyDescription() {
        ObjectNode request = createRequest("empty-description");
        request.put("description", "");

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void createAcceptsBlockedMessagingExactlyAtTheModelCap() {
        ObjectNode request = createRequest("messaging-at-cap");
        request.put("blockedInputMessaging", "i".repeat(500));
        request.put("blockedOutputsMessaging", "o".repeat(500));

        Guardrail guardrail = service.createGuardrail(request, REGION);

        assertEquals(500, guardrail.getBlockedInputMessaging().length());
        assertEquals(500, guardrail.getBlockedOutputsMessaging().length());
    }

    @Test
    void createRejectsBlockedInputMessagingOverTheModelCap() {
        ObjectNode request = createRequest("input-over-cap");
        request.put("blockedInputMessaging", "i".repeat(501));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("blockedInputMessaging"), error.getMessage());
    }

    @Test
    void createRejectsBlockedOutputsMessagingOverTheModelCap() {
        ObjectNode request = createRequest("outputs-over-cap");
        request.put("blockedOutputsMessaging", "o".repeat(501));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("blockedOutputsMessaging"), error.getMessage());
    }

    @Test
    void createAcceptsANameExactlyAtTheModelCap() {
        assertEquals(50, create("n".repeat(50)).getName().length());
    }

    @Test
    void createRejectsANameOverTheModelCap() {
        AwsException error = assertThrows(AwsException.class, () -> create("n".repeat(51)));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void updateEnforcesTheSameLengthCapsAsCreate() {
        Guardrail created = create("update-caps");

        ObjectNode request = createRequest("update-caps");
        request.put("description", "d".repeat(201));
        AwsException error = assertThrows(AwsException.class,
                () -> service.updateGuardrail(created.getGuardrailId(), request, REGION));
        assertEquals("ValidationException", error.getErrorCode());

        ObjectNode overLong = createRequest("update-caps");
        overLong.put("blockedOutputsMessaging", "o".repeat(501));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.updateGuardrail(created.getGuardrailId(), overLong, REGION)).getErrorCode());
    }

    // Tag limits from the AWS model

    private ArrayNode tagArray(int count) {
        ArrayNode tags = mapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            ObjectNode tag = tags.addObject();
            tag.put("key", "key-" + i);
            tag.put("value", "value-" + i);
        }
        return tags;
    }

    private Map<String, String> tagMap(String prefix, int count) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < count; i++) {
            tags.put(prefix + i, "value-" + i);
        }
        return tags;
    }

    @Test
    void parseTagListAcceptsATagListExactlyAtTheModelItemCap() {
        assertEquals(200, BedrockService.parseTagList(tagArray(200)).size());
    }

    @Test
    void parseTagListRejectsATagListOverTheModelItemCap() {
        AwsException error = assertThrows(AwsException.class,
                () -> BedrockService.parseTagList(tagArray(201)));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains("200"), error.getMessage());
    }

    @Test
    void createAcceptsTagsExactlyAtThePerResourceLimit() {
        ObjectNode request = createRequest("tags-at-limit");
        request.set("tags", tagArray(50));

        assertEquals(50, service.createGuardrail(request, REGION).getTags().size());
    }

    @Test
    void createRejectsMoreTagsThanOneResourceMayHold() {
        ObjectNode request = createRequest("tags-over-limit");
        request.set("tags", tagArray(51));

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("TooManyTagsException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertNotNull(error.getExtendedData().get("resourceName"));
    }

    @Test
    void createRejectsATagListOverTheModelItemCapBeforeTheResourceLimit() {
        ObjectNode request = createRequest("tags-over-item-cap");
        request.set("tags", tagArray(201));

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION)).getErrorCode());
    }

    @Test
    void tagResourceAcceptsTagsExactlyAtThePerResourceLimit() {
        String arn = create("tagging-at-limit").getGuardrailArn();

        service.tagResource(arn, tagMap("key-", 50), REGION);

        assertEquals(50, service.listTags(arn, REGION).size());
    }

    @Test
    void tagResourceRejectsMoreTagsThanOneResourceMayHold() {
        Guardrail created = create("tagging-over-limit");

        AwsException error = assertThrows(AwsException.class,
                () -> service.tagResource(created.getGuardrailArn(), tagMap("key-", 51), REGION));
        assertEquals("TooManyTagsException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(created.getGuardrailArn(), error.getExtendedData().get("resourceName"));
        assertTrue(service.listTags(created.getGuardrailArn(), REGION).isEmpty());
    }

    @Test
    void tagResourceCountsTheTotalLeftOnTheResourceAfterTheMerge() {
        String arn = create("tagging-merge").getGuardrailArn();
        service.tagResource(arn, tagMap("existing-", 40), REGION);

        service.tagResource(arn, tagMap("added-", 10), REGION);
        assertEquals(50, service.listTags(arn, REGION).size());

        AwsException error = assertThrows(AwsException.class,
                () -> service.tagResource(arn, Map.of("one-too-many", "x"), REGION));
        assertEquals("TooManyTagsException", error.getErrorCode());
        assertEquals(50, service.listTags(arn, REGION).size());
    }

    @Test
    void tagResourceDoesNotCountAKeyItOnlyReplaces() {
        String arn = create("tagging-replace").getGuardrailArn();
        service.tagResource(arn, tagMap("key-", 50), REGION);

        service.tagResource(arn, Map.of("key-0", "replaced"), REGION);

        assertEquals(50, service.listTags(arn, REGION).size());
        assertEquals("replaced", service.listTags(arn, REGION).get("key-0"));
    }

    // kmsKeyId normalisation

    @Test
    void createResolvesABareKeyIdToTheKeyArn() {
        knownKey(KEY_ID);
        ObjectNode request = createRequest("kms-by-id");
        request.put("kmsKeyId", KEY_ID);

        assertEquals(KEY_ARN, service.createGuardrail(request, REGION).getKmsKeyArn());
    }

    @Test
    void createResolvesAnAliasToTheKeyArn() {
        knownKey("alias/guardrails");
        ObjectNode request = createRequest("kms-by-alias");
        request.put("kmsKeyId", "alias/guardrails");

        assertEquals(KEY_ARN, service.createGuardrail(request, REGION).getKmsKeyArn());
    }

    @Test
    void createKeepsAFullKeyArn() {
        knownKey(KEY_ARN);
        ObjectNode request = createRequest("kms-by-arn");
        request.put("kmsKeyId", KEY_ARN);

        assertEquals(KEY_ARN, service.createGuardrail(request, REGION).getKmsKeyArn());
    }

    @Test
    void createWithoutAKmsKeyLeavesTheArnUnset() {
        assertNull(create("kms-absent").getKmsKeyArn());
    }

    @Test
    void createRejectsAKmsKeyThatDoesNotResolve() {
        doThrow(new AwsException("NotFoundException", "Key not found: missing", 404))
                .when(kmsService).describeKey("missing", REGION);
        ObjectNode request = createRequest("kms-missing");
        request.put("kmsKeyId", "missing");

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createRejectsAKmsKeyPendingDeletion() {
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setArn(KEY_ARN);
        key.setEnabled(false);
        key.setKeyState("PendingDeletion");
        doReturn(key).when(kmsService).describeKey(KEY_ID, REGION);
        ObjectNode request = createRequest("kms-pending");
        request.put("kmsKeyId", KEY_ID);

        AwsException error = assertThrows(AwsException.class,
                () -> service.createGuardrail(request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void updateResolvesTheKeyAndTheVersionSnapshotKeepsIt() {
        knownKey(KEY_ID, "alias/guardrails");
        Guardrail created = create("kms-on-update");

        ObjectNode request = createRequest("kms-on-update");
        request.put("kmsKeyId", "alias/guardrails");
        assertEquals(KEY_ARN, service.updateGuardrail(created.getGuardrailId(), request, REGION).getKmsKeyArn());

        assertEquals(KEY_ARN,
                service.createGuardrailVersion(created.getGuardrailId(), null, REGION).getKmsKeyArn());
    }

    @Test
    void updateRejectingTheKmsKeyAppliesNothing() {
        doThrow(new AwsException("NotFoundException", "Key not found: missing", 404))
                .when(kmsService).describeKey("missing", REGION);
        Guardrail created = create("kms-rejected");

        ObjectNode request = createRequest("kms-rejected-renamed");
        request.put("description", "should not stick");
        request.put("blockedInputMessaging", "input blocked v2");
        request.put("blockedOutputsMessaging", "output blocked v2");
        request.put("kmsKeyId", "missing");
        AwsException error = assertThrows(AwsException.class,
                () -> service.updateGuardrail(created.getGuardrailId(), request, REGION));
        assertEquals("ValidationException", error.getErrorCode());

        Guardrail draft = service.getGuardrail(created.getGuardrailId(), null, REGION);
        assertEquals("kms-rejected", draft.getName());
        assertNull(draft.getDescription());
        assertEquals("input blocked", draft.getBlockedInputMessaging());
        assertEquals("output blocked", draft.getBlockedOutputsMessaging());
    }

    @Test
    void guardrailsAreScopedToTheirRegion() {
        Guardrail created = create("regional");
        assertThrows(AwsException.class,
                () -> service.getGuardrail(created.getGuardrailId(), null, "eu-west-1"));
        assertTrue(service.listGuardrails(null, null, null, "eu-west-1").items().isEmpty());
    }

    private static final String PROFILE_ARN_US_EAST_2 =
            "arn:aws:bedrock:us-east-2:" + ACCOUNT + ":guardrail-profile/us.guardrail.v1:0";

    private static String rootPolicy(String resourceArn, String action) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"%s","Resource":"%s"}]}
                """.formatted(ACCOUNT, action, resourceArn);
    }

    @Test
    void guardrailResourcePolicyLivesOnTheDraftAndNotOnItsVersions() {
        Guardrail created = create("with-policy");
        String policy = rootPolicy(created.getGuardrailArn(), "bedrock:ApplyGuardrail");

        assertNull(service.getResourcePolicy(created.getGuardrailArn(), REGION));
        service.putResourcePolicy(created.getGuardrailArn(), policy, REGION);
        Guardrail version = service.createGuardrailVersion(created.getGuardrailId(), null, REGION);

        assertEquals(policy, service.getResourcePolicy(created.getGuardrailArn(), REGION));
        assertNull(version.getResourcePolicy());
        service.deleteResourcePolicy(created.getGuardrailArn(), REGION);
        assertNull(service.getResourcePolicy(created.getGuardrailArn(), REGION));
    }

    @Test
    void guardrailResourcePolicyIsFoundByItsExactArnOnly() {
        Guardrail created = create("exact-arn");
        String policy = rootPolicy(created.getGuardrailArn(), "bedrock:ApplyGuardrail");

        for (String other : List.of(created.getGuardrailId(),
                created.getGuardrailArn().replace(ACCOUNT, "111122223333"),
                created.getGuardrailArn().replace(":guardrail/", ":custom-model/"))) {
            AwsException e = assertThrows(AwsException.class, () -> service.getResourcePolicy(other, REGION));
            assertEquals("ResourceNotFoundException", e.getErrorCode(), other);
        }
        AwsException otherRegion = assertThrows(AwsException.class,
                () -> service.putResourcePolicy(created.getGuardrailArn(), policy, "eu-west-1"));
        assertEquals("ResourceNotFoundException", otherRegion.getErrorCode());
    }

    @Test
    void guardrailProfilePolicyIsKeptUnderTheRegionThatStoredIt() {
        String policy = rootPolicy(PROFILE_ARN_US_EAST_2, "bedrock:ApplyGuardrail");

        service.putResourcePolicy(PROFILE_ARN_US_EAST_2, policy, REGION);

        assertEquals(policy, service.getResourcePolicy(PROFILE_ARN_US_EAST_2, REGION));
        assertEquals("{}", service.getResourcePolicy(PROFILE_ARN_US_EAST_2, "us-east-2"));
    }

    @Test
    void deletingAGuardrailProfilePolicyLeavesTheEmptyPolicy() {
        assertEquals("{}", service.getResourcePolicy(PROFILE_ARN_US_EAST_2, REGION));
        service.putResourcePolicy(PROFILE_ARN_US_EAST_2, rootPolicy(PROFILE_ARN_US_EAST_2, "bedrock:ApplyGuardrail"),
                REGION);

        service.deleteResourcePolicy(PROFILE_ARN_US_EAST_2, REGION);

        assertEquals("{}", service.getResourcePolicy(PROFILE_ARN_US_EAST_2, REGION));
    }

    @Test
    void guardrailProfilePolicyAllowsOnlyApplyGuardrail() {
        AwsException e = assertThrows(AwsException.class, () -> service.putResourcePolicy(PROFILE_ARN_US_EAST_2,
                rootPolicy(PROFILE_ARN_US_EAST_2, "bedrock:GetGuardrail"), REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void guardrailProfileOfAnotherAccountOrPartitionOrWithAMalformedIdIsNotFound() {
        String chinaProfile = "arn:aws-cn:bedrock:cn-north-1:" + ACCOUNT + ":guardrail-profile/cn.guardrail.v1:0";
        for (String arn : List.of(PROFILE_ARN_US_EAST_2.replace(ACCOUNT, "111122223333"),
                PROFILE_ARN_US_EAST_2.replace(":" + ACCOUNT + ":", "::"),
                PROFILE_ARN_US_EAST_2.replace("us.guardrail.v1:0", "not-a-profile"),
                chinaProfile,
                chinaProfile.replace("arn:aws-cn:", "arn:aws:"))) {
            AwsException e = assertThrows(AwsException.class, () -> service.getResourcePolicy(arn, REGION));
            assertEquals("ResourceNotFoundException", e.getErrorCode(), arn);
            AwsException put = assertThrows(AwsException.class, () -> service.putResourcePolicy(arn,
                    rootPolicy(arn, "bedrock:ApplyGuardrail"), REGION));
            assertEquals("ResourceNotFoundException", put.getErrorCode(), arn);
        }
    }
    private static final String CALLER = "arn:aws:iam::" + ACCOUNT + ":user/admin";

    private ObjectNode enforcedConfigRequest(String guardrailIdentifier, String version) {
        return BedrockEnforcedGuardrailFixtures.request(mapper, guardrailIdentifier, version);
    }

    private Guardrail publishedGuardrail(String name) {
        return BedrockEnforcedGuardrailFixtures.publish(service, REGION, createRequest(name));
    }

    @Test
    void enforcedConfigurationRecordsTheGuardrailVersionAndCaller() {
        Guardrail version = publishedGuardrail("enforced");
        ObjectNode request = enforcedConfigRequest(version.getGuardrailId(), "1");
        ObjectNode inferenceConfig = (ObjectNode) request.get("guardrailInferenceConfig");
        inferenceConfig.putObject("selectiveContentGuarding").put("system", "SELECTIVE").put("messages", "COMPREHENSIVE");
        ObjectNode modelEnforcement = inferenceConfig.putObject("modelEnforcement");
        modelEnforcement.putArray("includedModels").add("ALL");
        modelEnforcement.putArray("excludedModels").add("anthropic.claude-v2");

        EnforcedGuardrailConfiguration created = service.putEnforcedGuardrailConfiguration(request, CALLER, REGION);

        assertTrue(created.getConfigId().matches("[a-z0-9]+"), created.getConfigId());
        List<EnforcedGuardrailConfiguration> listed = service.listEnforcedGuardrailConfigurations(null, REGION).items();
        assertEquals(1, listed.size());
        EnforcedGuardrailConfiguration stored = listed.get(0);
        assertEquals(created.getConfigId(), stored.getConfigId());
        assertEquals(version.getGuardrailId(), stored.getGuardrailId());
        assertEquals(version.getGuardrailArn(), stored.getGuardrailArn());
        assertEquals("1", stored.getGuardrailVersion());
        assertEquals("SELECTIVE", stored.getSelectiveContentGuarding().get("system").asText());
        assertEquals("ALL", stored.getModelEnforcement().get("includedModels").get(0).asText());
        assertEquals(CALLER, stored.getCreatedBy());
        assertEquals(CALLER, stored.getUpdatedBy());
        assertNotNull(stored.getCreatedAt());
    }

    @Test
    void enforcedConfigurationAcceptsTheGuardrailArnAsIdentifier() {
        Guardrail version = publishedGuardrail("enforced-by-arn");

        EnforcedGuardrailConfiguration created = service.putEnforcedGuardrailConfiguration(
                enforcedConfigRequest(version.getGuardrailArn(), "1"), CALLER, REGION);

        assertEquals(version.getGuardrailId(), created.getGuardrailId());
    }

    @Test
    void putWithAConfigIdReplacesThatConfigurationAndKeepsItsCreator() {
        Guardrail version = publishedGuardrail("enforced-update");
        service.createGuardrailVersion(version.getGuardrailId(), null, REGION);
        EnforcedGuardrailConfiguration created = service.putEnforcedGuardrailConfiguration(
                enforcedConfigRequest(version.getGuardrailId(), "1"), CALLER, REGION);

        ObjectNode update = enforcedConfigRequest(version.getGuardrailId(), "2");
        update.put("configId", created.getConfigId());
        String otherCaller = "arn:aws:iam::" + ACCOUNT + ":role/ops";
        service.putEnforcedGuardrailConfiguration(update, otherCaller, REGION);

        List<EnforcedGuardrailConfiguration> listed = service.listEnforcedGuardrailConfigurations(null, REGION).items();
        assertEquals(1, listed.size());
        assertEquals("2", listed.get(0).getGuardrailVersion());
        assertEquals(CALLER, listed.get(0).getCreatedBy());
        assertEquals(otherCaller, listed.get(0).getUpdatedBy());
    }

    @Test
    void enforcedConfigurationRejectsTheDraftVersion() {
        Guardrail draft = create("enforced-draft");

        AwsException e = assertThrows(AwsException.class, () -> service.putEnforcedGuardrailConfiguration(
                enforcedConfigRequest(draft.getGuardrailId(), BedrockService.DRAFT_VERSION), CALLER, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void enforcedConfigurationRejectsAVersionTheGuardrailDoesNotHold() {
        Guardrail version = publishedGuardrail("enforced-missing-version");

        AwsException e = assertThrows(AwsException.class, () -> service.putEnforcedGuardrailConfiguration(
                enforcedConfigRequest(version.getGuardrailId(), "7"), CALLER, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void putWithAnUnknownConfigIdIsNotFound() {
        Guardrail version = publishedGuardrail("enforced-unknown-config");
        ObjectNode request = enforcedConfigRequest(version.getGuardrailId(), "1");
        request.put("configId", "doesnotexist");

        AwsException e = assertThrows(AwsException.class,
                () -> service.putEnforcedGuardrailConfiguration(request, CALLER, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void enforcedConfigurationRejectsAnIncompleteModelEnforcementAndAnUnknownGuardingMode() {
        Guardrail version = publishedGuardrail("enforced-invalid");
        ObjectNode noIncludedModels = enforcedConfigRequest(version.getGuardrailId(), "1");
        ((ObjectNode) noIncludedModels.get("guardrailInferenceConfig")).putObject("modelEnforcement")
                .putArray("excludedModels");
        ObjectNode unknownMode = enforcedConfigRequest(version.getGuardrailId(), "1");
        ((ObjectNode) unknownMode.get("guardrailInferenceConfig")).putObject("selectiveContentGuarding")
                .put("system", "SOMETIMES");

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.putEnforcedGuardrailConfiguration(noIncludedModels, CALLER, REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.putEnforcedGuardrailConfiguration(unknownMode, CALLER, REGION)).getErrorCode());
    }

    @Test
    void enforcedConfigurationsAreScopedToTheirRegion() {
        Guardrail version = publishedGuardrail("enforced-regional");
        service.putEnforcedGuardrailConfiguration(enforcedConfigRequest(version.getGuardrailId(), "1"), CALLER, REGION);

        assertTrue(service.listEnforcedGuardrailConfigurations(null, "eu-west-1").items().isEmpty());
    }

    @Test
    void enforcedConfigurationsAreListedOnePerPage() {
        Guardrail version = publishedGuardrail("enforced-paged");
        service.putEnforcedGuardrailConfiguration(enforcedConfigRequest(version.getGuardrailId(), "1"), CALLER, REGION);
        service.putEnforcedGuardrailConfiguration(enforcedConfigRequest(version.getGuardrailId(), "1"), CALLER, REGION);

        PaginatedResult<EnforcedGuardrailConfiguration> first = service.listEnforcedGuardrailConfigurations(null, REGION);
        PaginatedResult<EnforcedGuardrailConfiguration> second =
                service.listEnforcedGuardrailConfigurations(first.nextToken(), REGION);

        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
        assertNotEquals(first.items().get(0).getConfigId(), second.items().get(0).getConfigId());
    }

    @Test
    void deletingAnEnforcedConfigurationRemovesItOnce() {
        Guardrail version = publishedGuardrail("enforced-delete");
        String configId = service.putEnforcedGuardrailConfiguration(
                enforcedConfigRequest(version.getGuardrailId(), "1"), CALLER, REGION).getConfigId();

        service.deleteEnforcedGuardrailConfiguration(configId, REGION);

        assertTrue(service.listEnforcedGuardrailConfigurations(null, REGION).items().isEmpty());
        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteEnforcedGuardrailConfiguration(configId, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}
